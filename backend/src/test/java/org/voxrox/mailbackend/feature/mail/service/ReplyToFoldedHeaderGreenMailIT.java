package org.voxrox.mailbackend.feature.mail.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.Properties;
import java.util.function.BooleanSupplier;

import jakarta.mail.Folder;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.internet.MimeMessage;

import org.junit.jupiter.api.AfterAll;
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
import org.voxrox.mailbackend.feature.mail.dto.DraftRequest;
import org.voxrox.mailbackend.feature.mail.dto.MailRequest;
import org.voxrox.mailbackend.feature.mail.entity.MessageEntity;
import org.voxrox.mailbackend.feature.mail.repository.MessageRepository;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.user.GreenMailUser;
import com.icegreen.greenmail.util.ServerSetup;

/**
 * A reply to a message whose {@code References} header the wire folded, over a
 * live IMAP connection (GreenMail): sync, reply prefill, draft save, the way
 * the app runs them. Found by the verification pass over IMAP/SMTP audit 1.42
 * (§3b): the sync stored the folded value with its line break, the reply
 * carried it into its own {@code References}, and the message builder refused
 * the line break, so no save of the reply reached the server and its send
 * failed the same way. RFC 5322 folds a header past 78 characters, which a
 * {@code References} of two Message-IDs usually is.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "mail.client.sync.initial-delay=PT1H", "mail.test-context=ReplyToFoldedHeaderGreenMailIT"})
@ContextConfiguration(initializers = StorageContextInitializer.class)
class ReplyToFoldedHeaderGreenMailIT {

    private static final Path DATA_DIR = Path.of("target", "test-tmp", "ReplyToFoldedHeaderGreenMailIT")
            .toAbsolutePath().normalize();

    private static final String EMAIL = "reply-it@greenmail.local";
    private static final String LOGIN = "reply-user";
    private static final String PASSWORD = "reply-password";
    private static final String INBOX = "INBOX";
    private static final String DRAFTS = "Drafts";
    private static final Duration AWAIT_TIMEOUT = Duration.ofSeconds(10);

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
    @Autowired
    private MailFacade mailFacade;
    @Autowired
    private DraftPersistenceService draftPersistenceService;
    @Autowired
    private MessageService messageService;

    @Test
    @DisplayName("A reply to a message with a folded References header is saved to the server")
    void aReplyToAFoldedReferencesHeaderIsSaved() throws Exception {
        GreenMailUser user = greenMail.setUser(EMAIL, LOGIN, PASSWORD);
        // The Drafts folder must exist before any backend folder listing so the
        // DRAFTS role resolves (GreenMail advertises no SPECIAL-USE).
        withStore(store -> {
            Folder drafts = store.getFolder(DRAFTS);
            if (!drafts.exists()) {
                drafts.create(Folder.HOLDS_MESSAGES);
            }
        });
        MailServerSettings server = new MailServerSettings("127.0.0.1", greenMail.getImaps().getPort(), true);
        accountService.createAccount(
                new AccountCreateRequest("Reply IT", null, EMAIL, null, server, server, LOGIN, PASSWORD));
        AccountEntity account = accountRepository.findByEmail(EMAIL).orElseThrow();
        Long accountId = account.getId();

        String raw = "From: Sender <sender@greenmail.local>\r\n" + "To: " + EMAIL + "\r\n" + "Subject: Re: a thread\r\n"
                + "Message-ID: <third@greenmail.local>\r\n" + "In-Reply-To: <second-in-the-thread@greenmail.local>\r\n"
                + "References: <first-in-the-thread@greenmail.local>\r\n"
                + " <second-in-the-thread@greenmail.local>\r\n" + "Date: Mon, 5 Oct 2026 10:00:00 +0200\r\n"
                + "MIME-Version: 1.0\r\n" + "Content-Type: text/plain; charset=UTF-8\r\n" + "\r\n" + "Hello\r\n";
        user.deliver(new MimeMessage(Session.getInstance(new Properties()),
                new ByteArrayInputStream(raw.getBytes(StandardCharsets.US_ASCII))));

        assertThat(mailSyncService.performFullSyncCycle(account, INBOX)).isTrue();
        MessageEntity original = messageRepository.findByAccountIdAndMessageId(accountId, "<third@greenmail.local>")
                .getFirst();
        assertThat(original.getReferences())
                .isEqualTo("<first-in-the-thread@greenmail.local> <second-in-the-thread@greenmail.local>");

        MailRequest reply = mailFacade.prepareReply(original.getStableId(), false);
        DraftRequest draft = new DraftRequest(reply.to(), reply.cc(), reply.bcc(), reply.subject(), reply.body(),
                reply.attachments(), reply.inReplyTo(), reply.references());
        DraftPersistenceService.DraftIdentity identity = draftPersistenceService.acceptDraftSave(accountId, draft,
                null);
        draftPersistenceService.saveDraftAsync(accountId, draft, null, identity);

        // The APPENDUID upsert makes the row addressable without a sync: the save was
        // built and stored.
        await(() -> messageService.getByStableId(identity.stableId()).isPresent());
        assertThat(serverDraftCount()).isEqualTo(1);
        assertThat(accountRepository.findById(accountId).orElseThrow().getLastErrorCode()).isNull();
    }

    private int serverDraftCount() throws Exception {
        int[] count = new int[1];
        withStore(store -> {
            Folder drafts = store.getFolder(DRAFTS);
            drafts.open(Folder.READ_ONLY);
            try {
                count[0] = drafts.getMessageCount();
            } finally {
                drafts.close(false);
            }
        });
        return count[0];
    }

    @FunctionalInterface
    private interface StoreAction {
        void apply(Store store) throws Exception;
    }

    /**
     * Runs an action against a fresh IMAP session, separate from the backend's
     * pooled connection — the test acts as an independent second client.
     */
    private void withStore(StoreAction action) throws Exception {
        Properties props = new Properties();
        props.put("mail.store.protocol", "imaps");
        try (Store store = Session.getInstance(props).getStore("imaps")) {
            store.connect("127.0.0.1", greenMail.getImaps().getPort(), LOGIN, PASSWORD);
            action.apply(store);
        }
    }

    private static void await(BooleanSupplier condition) {
        long deadline = System.nanoTime() + AWAIT_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while awaiting the async draft save", e);
            }
        }
        throw new AssertionError("Condition not met within " + AWAIT_TIMEOUT);
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
