package org.voxrox.mailbackend.feature.mail.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Comparator;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
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
import org.voxrox.mailbackend.feature.mail.repository.MessageRepository;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.user.GreenMailUser;
import com.icegreen.greenmail.util.GreenMailUtil;
import com.icegreen.greenmail.util.ServerSetup;

/**
 * What the machine's daily cycle does to a sync, on the wire: the part of
 * RELEASE_CHECKLIST §8.2 that a network can be made to do on demand. The
 * backend reaches GreenMail through {@link TcpFaultProxy}, so each fault is a
 * real socket failure in the real service graph — the pool, the transient
 * retry, the error bookkeeping and SQLite — rather than a mocked exception.
 * <p>
 * The timeouts and the retry budget are shortened so a fault costs seconds; the
 * production values are longer, and what is asserted does not depend on them.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        // The only sync runs are the explicit ones below.
        "mail.client.sync.initial-delay=PT1H", "mail.client.imap.read-timeout=3s",
        "mail.client.imap.connection-timeout=3s", "mail.client.retry.initial-delay=100ms",
        "mail.client.retry.max-delay=300ms",
        // A context of its own, so the data dir above is the one in use.
        "mail.test-context=SyncConnectionFaultGreenMailIT"})
@ContextConfiguration(initializers = StorageContextInitializer.class)
class SyncConnectionFaultGreenMailIT {

    private static final Path DATA_DIR = Path.of("target", "test-tmp", "SyncConnectionFaultGreenMailIT")
            .toAbsolutePath().normalize();

    private static final String EMAIL = "fault-it@greenmail.local";
    private static final String LOGIN = "fault-it-user";
    private static final String PASSWORD = "fault-it-password";
    private static final String INBOX = "INBOX";

    /** Well above what one faulted cycle takes with the budgets above. */
    private static final Duration HANG_LIMIT = Duration.ofSeconds(60);

    static {
        try {
            // Before the extension below opens GreenMail's TLS listener: the backend
            // connects to it over TLS only (audit B1-4).
            TestTls.install();
            deleteRecursively(DATA_DIR);
            Files.createDirectories(DATA_DIR.resolve("logs"));
            System.setProperty("app.data-dir", DATA_DIR.toString());
            System.setProperty("logging.file.name", DATA_DIR.resolve("logs").resolve("mail.log").toString());
            System.setProperty("spring.security.oauth2.client.registration.google.client-id", "dummy-client-id");
            System.setProperty("spring.security.oauth2.client.registration.google.client-secret",
                    "dummy-client-secret");
            System.setProperty("spring.security.oauth2.client.registration.microsoft.client-id", "dummy-client-id");
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @RegisterExtension
    static GreenMailExtension greenMail = new GreenMailExtension(
            new ServerSetup(0, "127.0.0.1", ServerSetup.PROTOCOL_IMAPS)).withPerMethodLifecycle(false);

    private static TcpFaultProxy proxy;

    @AfterAll
    static void tearDown() throws Exception {
        if (proxy != null) {
            proxy.close();
        }
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
    private MessageRepository messageRepository;
    @Autowired
    private MailSyncService mailSyncService;

    private GreenMailUser user;
    private AccountEntity account;

    @BeforeEach
    void setUp() throws Exception {
        if (proxy == null) {
            proxy = new TcpFaultProxy("127.0.0.1", greenMail.getImaps().getPort());
        }
        proxy.restore();
        user = greenMail.setUser(EMAIL, LOGIN, PASSWORD);
        account = accountRepository.findByEmail(EMAIL).orElseGet(() -> {
            MailServerSettings server = new MailServerSettings("127.0.0.1", proxy.port(), true);
            accountService.createAccount(
                    new AccountCreateRequest("Fault IT", null, EMAIL, null, server, server, LOGIN, PASSWORD));
            return accountRepository.findByEmail(EMAIL).orElseThrow();
        });
        // Each test starts from a clean account; a failure test leaves its error
        // behind.
        accountRepository.clearLastError(account.getId(), LocalDateTime.now());
    }

    @Test
    @DisplayName("Connections that died between passes are replaced, and the next pass succeeds cleanly")
    void deadPooledConnectionIsReplaced() {
        deliver("Before the sleep");
        assertThat(passWithin(HANG_LIMIT)).as("error after the first pass").isNull();
        // What a pass opens when the pooled connection is alive, as the baseline.
        int beforeQuietPass = proxy.acceptedConnections();
        assertThat(passWithin(HANG_LIMIT)).isNull();
        int quietPass = proxy.acceptedConnections() - beforeQuietPass;
        long before = messages();

        // The machine slept: the server closed the session, the pool still holds it.
        proxy.dropConnections();
        deliver("After the sleep");
        int beforeWakePass = proxy.acceptedConnections();

        assertThat(passWithin(HANG_LIMIT)).as("error after the pass that met a dead connection").isNull();
        assertThat(proxy.acceptedConnections() - beforeWakePass).as("connections opened after the drop")
                .isGreaterThan(quietPass);
        assertThat(messages()).isEqualTo(before + 1);
        assertThat(reload().isRequiresReauth()).isFalse();
    }

    @Test
    @DisplayName("An unreachable server fails the pass with a recorded error, and the next reachable pass clears it")
    void unreachableServerIsRecordedAndRecovered() {
        assertThat(passWithin(HANG_LIMIT)).isNull();
        proxy.refuse();

        assertThat(passWithin(HANG_LIMIT)).as("error while the server is unreachable").isNotNull();
        // A network failure is not a credential failure.
        assertThat(reload().isRequiresReauth()).isFalse();

        proxy.restore();
        deliver("After the outage");
        long before = messages();
        assertThat(passWithin(HANG_LIMIT)).as("error once the server is back").isNull();
        assertThat(messages()).isEqualTo(before + 1);
    }

    @Test
    @DisplayName("A network that goes quiet fails the pass within the read timeout instead of hanging it")
    void silentNetworkDoesNotHangThePass() {
        assertThat(passWithin(HANG_LIMIT)).isNull();
        proxy.silence();

        assertThat(passWithin(HANG_LIMIT)).as("error while the network is silent").isNotNull();
        assertThat(reload().isRequiresReauth()).isFalse();

        proxy.restore();
        deliver("After the silence");
        long before = messages();
        assertThat(passWithin(HANG_LIMIT)).as("error once the network speaks again").isNull();
        assertThat(messages()).isEqualTo(before + 1);
    }

    /**
     * One whole-account pass, the one the scheduler runs, called on the service
     * itself rather than through its {@code @Async} proxy so it finishes before
     * this returns. Returns the account's error code afterwards.
     */
    private @Nullable String passWithin(Duration limit) {
        MailSyncService direct = AopTestUtils.getUltimateTargetObject(mailSyncService);
        long started = System.nanoTime();
        direct.syncAllFolders(reload(), SyncTrigger.SCHEDULED);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).as("time one pass took").isLessThan(limit);
        return accountRepository.findLastErrorCode(account.getId()).orElse(null);
    }

    private long messages() {
        return messageRepository.countByAccountIdAndFolderName(account.getId(), INBOX);
    }

    private AccountEntity reload() {
        return accountRepository.findById(account.getId()).orElseThrow();
    }

    private void deliver(String subject) {
        user.deliver(GreenMailUtil.createTextEmail(EMAIL, "sender@example.com", subject, "body",
                greenMail.getImaps().getServerSetup()));
    }

    private static void deleteRecursively(Path path) throws Exception {
        if (Files.notExists(path)) {
            return;
        }
        try (var stream = Files.walk(path)) {
            for (Path item : stream.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(item);
            }
        }
    }
}
