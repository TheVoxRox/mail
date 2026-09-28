package org.voxrox.mailbackend.feature.mail.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;

import jakarta.activation.DataHandler;
import jakarta.mail.Flags;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.util.ByteArrayDataSource;

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
import org.voxrox.mailbackend.feature.mail.entity.MessageEntity;
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
 * through between two scheduler ticks. The one test beside it works in a folder
 * of its own.
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

    /**
     * IMAPS with the shared test certificate on an ephemeral loopback port; started
     * once for the class.
     */
    @RegisterExtension
    static GreenMailExtension greenMail = new GreenMailExtension(
            new ServerSetup(0, "127.0.0.1", ServerSetup.PROTOCOL_IMAPS)).withPerMethodLifecycle(false);

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
            int imapPort = greenMail.getImaps().getPort();
            MailServerSettings server = new MailServerSettings("127.0.0.1", imapPort, true);
            accountService.createAccount(
                    new AccountCreateRequest("GreenMail IT", null, EMAIL, null, server, server, LOGIN, PASSWORD));
            return accountRepository.findByEmail(EMAIL).orElseThrow();
        });
    }

    @Test
    @DisplayName("Full sync lifecycle over live IMAP: download -> flag change -> delete -> reconcile server-only hole")
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

        // --- Phase 4: a server-only message sits in a hole inside the window --
        // Deliver two more messages so the mirror spans a range with an interior.
        deliver("Third message", "body three");
        deliver("Fourth message", "body four");

        assertThat(mailSyncService.performFullSyncCycle(account, INBOX)).isTrue();
        assertThat(messageRepository.countByAccountIdAndFolderName(account.getId(), INBOX)).isEqualTo(3);

        // Punch a hole in the middle of the local mirror while the server keeps the
        // message — the state a purge/trash bug leaves behind (finding 2026-07-18). The
        // middle UID is whatever findUidsByAccountAndFolder returns as the median of
        // the three local rows; it is strictly between the min and max, so it is an
        // interior hole and the forward cursor (already advanced past it) can never
        // re-fetch it.
        List<Long> localUids = messageRepository.findUidsByAccountAndFolder(account.getId(), INBOX);
        assertThat(localUids).hasSize(3);
        long holeUid = localUids.get(1);
        messageRepository.deleteAllByAccountIdAndFolderNameAndUidIn(account.getId(), INBOX, List.of(holeUid));
        assertThat(messageRepository.countByAccountIdAndFolderName(account.getId(), INBOX)).isEqualTo(2);
        assertThat(messageRepository.findUidsByAccountAndFolder(account.getId(), INBOX)).doesNotContain(holeUid);

        // A plain sync must heal the hole: deletion cleanup detects the server-only
        // interior UID and the reconcile re-downloads it, so the mirror is contiguous
        // again and the message is visible.
        assertThat(mailSyncService.performFullSyncCycle(account, INBOX)).isTrue();

        assertThat(messageRepository.countByAccountIdAndFolderName(account.getId(), INBOX)).isEqualTo(3);
        assertThat(messageRepository.findUidsByAccountAndFolder(account.getId(), INBOX)).contains(holeUid);
    }

    /**
     * B1-10. A message whose structure nests past
     * {@link BoundedImapProtocol#MAX_NESTING} has its FETCH response dropped
     * unparsed. Angus then loads that message's envelope with a FETCH of its own
     * and fails to load its structure, which the sync keeps as an envelope-only
     * stub, and the message beside it syncs whole. A folder of its own, so the
     * lifecycle above keeps its counts.
     */
    @Test
    @DisplayName("A message nested past the bound is kept by its envelope, and the folder syncs on")
    void aMessageNestedPastTheBoundIsKeptAsAStub() throws Exception {
        String folderName = "Nested";
        withFolder(folderName, folder -> {
            folder.create(Folder.HOLDS_MESSAGES);
            folder.open(Folder.READ_WRITE);
            try {
                folder.appendMessages(
                        new Message[]{nestedMessage(BoundedImapProtocol.MAX_NESTING + 40), messageWithAttachment()});
            } finally {
                folder.close(false);
            }
        });

        assertThat(mailSyncService.performFullSyncCycle(account, folderName)).isTrue();

        MessageEntity nested = storedByMessageId("<nested@greenmail.local>");
        assertThat(nested.getSubject()).isEqualTo("Nested deep");
        assertThat(nested.getSender()).isEqualTo("sender@example.com");
        assertThat(nested.isHasAttachments()).isFalse();
        MessageEntity ordinary = storedByMessageId("<ordinary@greenmail.local>");
        assertThat(ordinary.isHasAttachments()).isTrue();
        assertThat(accountRepository.findById(account.getId()).orElseThrow().getLastError()).isNull();
    }

    private MessageEntity storedByMessageId(String messageId) {
        List<MessageEntity> rows = messageRepository.findByAccountIdAndMessageId(account.getId(), messageId);
        assertThat(rows).as(messageId).hasSize(1);
        return rows.getFirst();
    }

    /**
     * A message of {@code levels} multiparts one inside the next around a PDF, the
     * attachment the extractor would report were the structure readable.
     */
    private static MimeMessage nestedMessage(int levels) throws Exception {
        StringBuilder mime = new StringBuilder();
        mime.append("From: sender@example.com\r\nTo: ").append(EMAIL).append("\r\nSubject: Nested deep\r\n")
                .append("Message-ID: <nested@greenmail.local>\r\nMIME-Version: 1.0\r\n");
        for (int level = 0; level < levels; level++) {
            mime.append("Content-Type: multipart/mixed; boundary=\"b").append(level).append("\"\r\n\r\n--b")
                    .append(level).append("\r\n");
        }
        mime.append("Content-Type: application/pdf; name=\"deep.pdf\"\r\n")
                .append("Content-Disposition: attachment; filename=\"deep.pdf\"\r\n\r\n%PDF-1.7\r\n");
        for (int level = levels - 1; level >= 0; level--) {
            mime.append("--b").append(level).append("--\r\n");
        }
        return new MimeMessage(Session.getInstance(new Properties()),
                new ByteArrayInputStream(mime.toString().getBytes(StandardCharsets.US_ASCII)));
    }

    private static MimeMessage messageWithAttachment() throws Exception {
        MimeBodyPart text = new MimeBodyPart();
        text.setText("See the attachment.", StandardCharsets.UTF_8.name());
        MimeBodyPart pdf = new MimeBodyPart();
        pdf.setDataHandler(new DataHandler(
                new ByteArrayDataSource("%PDF-1.7".getBytes(StandardCharsets.US_ASCII), "application/pdf")));
        pdf.setFileName("ordinary.pdf");
        pdf.setDisposition(Part.ATTACHMENT);
        MimeMultipart multipart = new MimeMultipart(text, pdf);
        MimeMessage message = new MimeMessage(Session.getInstance(new Properties())) {
            @Override
            protected void updateMessageID() throws MessagingException {
                setHeader("Message-ID", "<ordinary@greenmail.local>");
            }
        };
        message.setFrom("sender@example.com");
        message.setRecipients(Message.RecipientType.TO, EMAIL);
        message.setSubject("Ordinary");
        message.setContent(multipart);
        message.saveChanges();
        return message;
    }

    private void deliver(String subject, String body) {
        user.deliver(GreenMailUtil.createTextEmail(EMAIL, "sender@example.com", subject, body,
                greenMail.getImaps().getServerSetup()));
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
        withFolder(INBOX, inbox -> {
            inbox.open(Folder.READ_WRITE);
            try {
                mutation.apply(inbox);
            } finally {
                inbox.close(true);
            }
        });
    }

    @FunctionalInterface
    private interface FolderAction {
        void apply(Folder folder) throws Exception;
    }

    /** The second client of {@link #mutateInbox}, on any folder, left unopened. */
    private void withFolder(String name, FolderAction action) throws Exception {
        Properties props = new Properties();
        props.put("mail.store.protocol", "imaps");
        Session session = Session.getInstance(props);
        Store store = session.getStore("imaps");
        store.connect("127.0.0.1", greenMail.getImaps().getPort(), LOGIN, PASSWORD);
        try {
            action.apply(store.getFolder(name));
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
