package org.voxrox.mailbackend.feature.mail.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Properties;

import jakarta.mail.Flags;
import jakarta.mail.Folder;
import jakarta.mail.Session;
import jakarta.mail.Store;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ContextConfiguration;
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
 * Integration test of the sync layer against a real IMAP server (GreenMail).
 *
 * <p>
 * The Mockito-based unit tests around {@code MailSyncService} stub the protocol
 * away, so they cannot catch wire-level regressions: UID handling, flag
 * propagation, deletion cleanup, folder state. This IT walks one account
 * lifecycle over a live (in-process) IMAP connection through the real service
 * graph — {@code ImapConnectionManager} pool + per-account lock,
 * {@code MessageDownloader}, {@code FlagSyncService}, SQLite persistence.
 *
 * <p>
 * One sequential scenario instead of isolated test methods: the phases
 * deliberately share server + DB state (initial download → flag change →
 * server-side delete), which is exactly the lifecycle a real mailbox goes
 * through between two scheduler ticks.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        // Keep the background scheduler out of the test — sync runs are explicit.
        "mail.client.sync.initial-delay=PT1H"})
@ContextConfiguration(initializers = StorageContextInitializer.class)
class MailSyncGreenMailIT {

    private static final Path DATA_DIR = Path.of("target", "test-tmp", "MailSyncGreenMailIT").toAbsolutePath()
            .normalize();

    private static final String EMAIL = "it@greenmail.local";
    private static final String LOGIN = "it-user";
    private static final String PASSWORD = "it-password";
    private static final String INBOX = "INBOX";

    static {
        try {
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

    /** Plain IMAP on an ephemeral loopback port; started once for the class. */
    @RegisterExtension
    static GreenMailExtension greenMail = new GreenMailExtension(
            new ServerSetup(0, "127.0.0.1", ServerSetup.PROTOCOL_IMAP)).withPerMethodLifecycle(false);

    @AfterAll
    static void clearSystemProperties() {
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
    void setUpAccount() {
        user = greenMail.setUser(EMAIL, LOGIN, PASSWORD);
        account = accountRepository.findByEmail(EMAIL).orElseGet(() -> {
            int imapPort = greenMail.getImap().getPort();
            MailServerSettings server = new MailServerSettings("127.0.0.1", imapPort, false);
            accountService.createAccount(
                    new AccountCreateRequest("GreenMail IT", null, EMAIL, null, server, server, LOGIN, PASSWORD));
            return accountRepository.findByEmail(EMAIL).orElseThrow();
        });
    }

    @Test
    @DisplayName("Full sync lifecycle over live IMAP: download -> flag change -> server-side delete")
    void fullSyncLifecycle() throws Exception {
        // --- Phase 1: initial download -------------------------------------
        deliver("First message", "body one");
        deliver("Second message", "body two");

        assertThat(mailSyncService.performFullSyncCycle(account, INBOX)).isTrue();

        assertThat(messageRepository.countByAccountIdAndFolderName(account.getId(), INBOX)).isEqualTo(2);
        // GreenMail delivers as unseen — both must arrive unseen locally.
        assertThat(messageRepository.countByAccountIdAndFolderNameAndSeenFalse(account.getId(), INBOX)).isEqualTo(2);
        // A clean pass must not leave any last_error behind.
        assertThat(accountRepository.findById(account.getId()).orElseThrow().getLastError()).isNull();

        // --- Phase 2: another client marks one message as read --------------
        mutateInbox(folder -> {
            folder.getMessage(1).setFlag(Flags.Flag.SEEN, true);
            return null;
        });

        assertThat(mailSyncService.performFullSyncCycle(account, INBOX)).isTrue();

        assertThat(messageRepository.countByAccountIdAndFolderName(account.getId(), INBOX)).isEqualTo(2);
        assertThat(messageRepository.countByAccountIdAndFolderNameAndSeenFalse(account.getId(), INBOX)).isEqualTo(1);

        // --- Phase 3: another client deletes a message on the server --------
        mutateInbox(folder -> {
            folder.getMessage(1).setFlag(Flags.Flag.DELETED, true);
            return null;
        });

        assertThat(mailSyncService.performFullSyncCycle(account, INBOX)).isTrue();

        assertThat(messageRepository.countByAccountIdAndFolderName(account.getId(), INBOX)).isEqualTo(1);
    }

    private void deliver(String subject, String body) {
        user.deliver(GreenMailUtil.createTextEmail(EMAIL, "sender@example.com", subject, body,
                greenMail.getImap().getServerSetup()));
    }

    @FunctionalInterface
    private interface InboxMutation {
        Void apply(Folder inbox) throws Exception;
    }

    /**
     * Simulates a second mail client (phone, webmail): a separate IMAP session that
     * mutates the mailbox outside the backend's pooled connection. Closing with
     * {@code expunge=true} makes DELETED flags take effect immediately, like a real
     * client would.
     */
    private void mutateInbox(InboxMutation mutation) throws Exception {
        Properties props = new Properties();
        props.put("mail.store.protocol", "imap");
        Session session = Session.getInstance(props);
        Store store = session.getStore("imap");
        store.connect("127.0.0.1", greenMail.getImap().getPort(), LOGIN, PASSWORD);
        try {
            Folder inbox = store.getFolder(INBOX);
            inbox.open(Folder.READ_WRITE);
            try {
                mutation.apply(inbox);
            } finally {
                inbox.close(true);
            }
        } finally {
            store.close();
        }
    }

    private static void deleteRecursively(Path path) throws Exception {
        if (Files.notExists(path)) {
            return;
        }
        try (var stream = Files.walk(path)) {
            stream.sorted(Comparator.reverseOrder()).forEach(item -> {
                try {
                    Files.deleteIfExists(item);
                } catch (Exception e) {
                    throw new IllegalStateException("Failed to delete test path " + item, e);
                }
            });
        }
    }
}
