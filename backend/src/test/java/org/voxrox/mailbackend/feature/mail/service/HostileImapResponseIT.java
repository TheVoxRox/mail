package org.voxrox.mailbackend.feature.mail.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Comparator;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.util.AopTestUtils;
import org.voxrox.mailbackend.core.init.StorageContextInitializer;
import org.voxrox.mailbackend.feature.account.dto.AccountCreateRequest;
import org.voxrox.mailbackend.feature.account.dto.MailServerSettings;
import org.voxrox.mailbackend.feature.account.entity.AccountEntity;
import org.voxrox.mailbackend.feature.account.repository.AccountRepository;
import org.voxrox.mailbackend.feature.account.service.AccountService;

/**
 * A sync against a server that answers the folder open with a size meant to
 * exhaust the heap — IMAP/SMTP audit B1-3. Angus sizes an array from such a
 * number while it is still parsing the SELECT, before any of our code sees it,
 * so the only place to refuse it is the protocol layer
 * ({@link BoundedImapProtocol}); without that the pass ends in an
 * {@link OutOfMemoryError} thrown straight out of the sync.
 * <p>
 * The numbers are chosen so that allocation can never succeed, whatever heap
 * the test JVM has: they ask for an array at the VM's length limit, which fails
 * before any memory is taken. The test therefore costs nothing when the guard
 * is missing, beyond the failure it reports.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        // The only sync runs are the explicit ones below.
        "mail.client.sync.initial-delay=PT1H", "mail.client.imap.read-timeout=3s",
        "mail.client.imap.connection-timeout=3s", "mail.client.retry.initial-delay=100ms",
        "mail.client.retry.max-delay=300ms",
        // A context of its own, so the data dir above is the one in use.
        "mail.test-context=HostileImapResponseIT"})
@ContextConfiguration(initializers = StorageContextInitializer.class)
class HostileImapResponseIT {

    private static final Path DATA_DIR = Path.of("target", "test-tmp", "HostileImapResponseIT").toAbsolutePath()
            .normalize();

    private static final String EMAIL = "hostile-it@example.test";

    private static final HostileImapServer SERVER;

    static {
        try {
            // Before the server starts: it presents the test certificate, and the
            // backend trusts only that one.
            TestTls.install();
            deleteRecursively(DATA_DIR);
            Files.createDirectories(DATA_DIR.resolve("logs"));
            System.setProperty("app.data-dir", DATA_DIR.toString());
            System.setProperty("logging.file.name", DATA_DIR.resolve("logs").resolve("mail.log").toString());
            System.setProperty("spring.security.oauth2.client.registration.google.client-id", "dummy-client-id");
            System.setProperty("spring.security.oauth2.client.registration.google.client-secret",
                    "dummy-client-secret");
            System.setProperty("spring.security.oauth2.client.registration.microsoft.client-id", "dummy-client-id");
            SERVER = new HostileImapServer();
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @AfterAll
    static void tearDown() throws Exception {
        SERVER.close();
        System.clearProperty("app.data-dir");
        System.clearProperty("logging.file.name");
        System.clearProperty("spring.security.oauth2.client.registration.google.client-id");
        System.clearProperty("spring.security.oauth2.client.registration.google.client-secret");
        System.clearProperty("spring.security.oauth2.client.registration.microsoft.client-id");
    }

    @Autowired
    private AccountService accountService;
    @Autowired
    private AccountRepository accountRepository;
    @Autowired
    private MailSyncService mailSyncService;

    private AccountEntity account;

    @BeforeEach
    void setUp() {
        SERVER.answerOpenWith();
        account = accountRepository.findByEmail(EMAIL).orElseGet(() -> {
            MailServerSettings server = new MailServerSettings("127.0.0.1", SERVER.port(), true);
            accountService.createAccount(
                    new AccountCreateRequest("Hostile IT", null, EMAIL, null, server, server, "user", "password"));
            return accountRepository.findByEmail(EMAIL).orElseThrow();
        });
        accountRepository.clearLastError(account.getId(), LocalDateTime.now());
    }

    @Test
    @DisplayName("The test server is one the backend can sync from, so a failure below is the response's doing")
    void anOrdinaryAnswerSyncs() {
        assertThat(pass().getLastErrorCode()).isNull();
    }

    @Test
    @DisplayName("A folder open that claims more messages than an array can hold fails the pass instead of the heap")
    void anImplausibleMessageCountFailsThePass() {
        SERVER.answerOpenWith("* 2147483583 EXISTS");

        AccountEntity after = pass();

        assertThat(after.getLastErrorCode()).isNotNull();
        assertThat(after.getLastError()).contains("implausible IMAP response").contains("EXISTS");
    }

    @Test
    @DisplayName("A folder open that reports vanished UIDs nobody asked about fails the pass instead of the heap")
    void anUnaskedForVanishedRangeFailsThePass() {
        // The UIDNEXT lifts the ceiling Angus would otherwise clamp the range to.
        SERVER.answerOpenWith("* OK [UIDNEXT 4294967295] predicted", "* VANISHED (EARLIER) 1:2147483647");

        AccountEntity after = pass();

        assertThat(after.getLastErrorCode()).isNotNull();
        assertThat(after.getLastError()).contains("implausible IMAP response").contains("VANISHED");
    }

    @Test
    @DisplayName("A refused response costs one pass: the next ordinary answer syncs again")
    void theNextOrdinaryAnswerSyncsAgain() {
        SERVER.answerOpenWith("* 2147483583 EXISTS");
        assertThat(pass().getLastErrorCode()).isNotNull();

        SERVER.answerOpenWith();

        assertThat(pass().getLastErrorCode()).isNull();
    }

    /**
     * One whole-account pass, the one the scheduler runs, called on the service
     * itself rather than through its {@code @Async} proxy so it finishes — or
     * throws — before this returns.
     */
    private AccountEntity pass() {
        MailSyncService direct = AopTestUtils.getUltimateTargetObject(mailSyncService);
        direct.syncAllFolders(accountRepository.findById(account.getId()).orElseThrow(), SyncTrigger.SCHEDULED);
        return accountRepository.findById(account.getId()).orElseThrow();
    }

    private static void deleteRecursively(Path path) throws Exception {
        if (!Files.exists(path)) {
            return;
        }
        try (var walk = Files.walk(path)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        }
    }
}
