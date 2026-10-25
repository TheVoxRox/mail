package org.voxrox.mailbackend.feature.mail.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.concurrent.atomic.AtomicInteger;

import jakarta.mail.Folder;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ContextConfiguration;
import org.voxrox.mailbackend.core.init.StorageContextInitializer;
import org.voxrox.mailbackend.feature.account.dto.AccountCreateRequest;
import org.voxrox.mailbackend.feature.account.dto.MailServerSettings;
import org.voxrox.mailbackend.feature.account.entity.AccountEntity;
import org.voxrox.mailbackend.feature.account.repository.AccountRepository;
import org.voxrox.mailbackend.feature.account.service.AccountService;
import org.voxrox.mailbackend.feature.mail.service.ImapConnectionManager.Lane;

/**
 * The account budget against a server that makes each open folder keep as much
 * as its own budget admits (IMAP/SMTP audit B1-14, B1-21). One folder stays
 * under the open-folder budget, so each connection on its own is admitted; what
 * the budget per account adds is that two of them at once are not, because they
 * belong to one account. Before it the account could hold the open-folder
 * budget once per connection, and a command's budget besides.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        // The only sync runs are the explicit ones below.
        "mail.client.sync.initial-delay=PT1H", "mail.client.imap.read-timeout=3s",
        "mail.client.imap.connection-timeout=3s", "mail.client.retry.initial-delay=100ms",
        "mail.client.retry.max-delay=300ms",
        // One folder keeps 12 MB and its command carries 9 MB more while it runs (see
        // RENAMES): under each folder's 16 MB and the account's 24 MB alone, past the
        // account's with a second folder open.
        "mail.client.imap.open-folder-budget=16MB", "mail.client.imap.account-budget=24MB",
        // A context of its own, so the data dir above is the one in use.
        "mail.test-context=AccountMemoryBudgetIT"})
@ContextConfiguration(initializers = StorageContextInitializer.class)
class AccountMemoryBudgetIT {

    private static final Path DATA_DIR = Path.of("target", "test-tmp", "AccountMemoryBudgetIT").toAbsolutePath()
            .normalize();

    private static final String EMAIL = "account-budget-it@example.test";

    /** The account budget configured above, in bytes, as the log states it. */
    private static final String BUDGET = String.valueOf(24L * 1024 * 1024);

    /**
     * Renamings of the folder's one message per command: some 400 bytes each
     * against the open-folder budget and some 300 against the command's, so one
     * command leaves its folder keeping 12 MB and carries 9 MB while it runs.
     */
    private static final int RENAMES = 30_000;

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
    private ImapFolderService imapFolderService;
    @Autowired
    private ImapConnectionManager connectionManager;

    private AccountEntity account;
    private long logMark;

    @BeforeEach
    void setUp() {
        SERVER.answerOpenWith("* 1 EXISTS");
        SERVER.listUidsOfOneMessage(RENAMES);
        account = accountRepository.findByEmail(EMAIL).orElseGet(() -> {
            MailServerSettings server = new MailServerSettings("127.0.0.1", SERVER.port(), true);
            accountService.createAccount(new AccountCreateRequest("Account budget IT", null, EMAIL, null, server,
                    server, "user", "password"));
            return accountRepository.findByEmail(EMAIL).orElseThrow();
        });
        accountRepository.clearLastError(account.getId(), LocalDateTime.now());
        // Fresh connections, so nothing a test before left open counts here.
        connectionManager.removeConnection(account.getId());
        logMark = logLength();
    }

    @Test
    @DisplayName("Two folders of one account open at once are refused together, though each fits its own budget")
    void twoFoldersOfOneAccountCountTogether() {
        AtomicInteger admitted = new AtomicInteger();

        assertThatThrownBy(() -> imapFolderService.executeInFolder(account.getId(), Lane.BACKGROUND, "INBOX",
                Folder.READ_ONLY, (folder, uidFolder) -> {
                    uidFolder.getMessageByUID(RENAMES + 1L);
                    admitted.incrementAndGet();
                    // The interactive lane is a connection of its own, with a folder of its
                    // own, while the sync's stays open with what it keeps.
                    imapFolderService.executeInFolder(account.getId(), Lane.INTERACTIVE, "INBOX", Folder.READ_ONLY,
                            (other, otherUids) -> {
                                otherUids.getMessageByUID(RENAMES + 2L);
                                admitted.incrementAndGet();
                                return null;
                            });
                    return null;
                })).isInstanceOf(RuntimeException.class);

        assertThat(admitted).hasValue(1);
        assertThat(logSinceMark()).contains("hold over half of their " + BUDGET + "-byte budget")
                .contains("past its budget of " + BUDGET + " bytes (mail.client.imap.account-budget)")
                .doesNotContain("passed its budget of");
    }

    /*
     * The sync's folder stays open after its command returns, holding 12 MB; the
     * command's 9 MB went with its return. A second folder whose command peaks at
     * some 7 MB fits beside the 12 (19 MB) and would not beside the 21 (28 MB).
     */
    @Test
    @DisplayName("A command that has returned counts only what its folder keeps, not its responses")
    void aReturnedCommandCountsOnlyWhatItsFolderKeeps() throws Exception {
        AtomicInteger admitted = new AtomicInteger();

        imapFolderService.executeInFolder(account.getId(), Lane.BACKGROUND, "INBOX", Folder.READ_ONLY,
                (folder, uidFolder) -> {
                    uidFolder.getMessageByUID(RENAMES + 1L);
                    SERVER.listUidsOfOneMessage(RENAMES / 3);
                    imapFolderService.executeInFolder(account.getId(), Lane.INTERACTIVE, "INBOX", Folder.READ_ONLY,
                            (other, otherUids) -> {
                                otherUids.getMessageByUID(RENAMES + 2L);
                                admitted.incrementAndGet();
                                return null;
                            });
                    return null;
                });

        assertThat(admitted).hasValue(1);
        assertThat(logSinceMark()).doesNotContain("past its budget of");
    }

    @Test
    @DisplayName("A folder that has closed no longer counts, so the next one of the account is admitted")
    void aClosedFolderNoLongerCounts() throws Exception {
        imapFolderService.executeInFolder(account.getId(), Lane.BACKGROUND, "INBOX", Folder.READ_ONLY,
                (folder, uidFolder) -> {
                    uidFolder.getMessageByUID(RENAMES + 1L);
                    return null;
                });

        imapFolderService.executeInFolder(account.getId(), Lane.INTERACTIVE, "INBOX", Folder.READ_ONLY,
                (folder, uidFolder) -> {
                    uidFolder.getMessageByUID(RENAMES + 2L);
                    return null;
                });

        assertThat(logSinceMark()).doesNotContain("past its budget of");
    }

    private static Path log() {
        return DATA_DIR.resolve("logs").resolve("mail.log");
    }

    private static long logLength() {
        try {
            return Files.exists(log()) ? Files.size(log()) : 0;
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private String logSinceMark() {
        try {
            byte[] bytes = Files.readAllBytes(log());
            return new String(bytes, (int) logMark, bytes.length - (int) logMark, StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
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
