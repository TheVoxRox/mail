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

import org.jspecify.annotations.Nullable;
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
 * A reply to a message whose header reaches the sync with a line break in it,
 * over a live IMAP connection (GreenMail): sync, reply prefill, draft save, the
 * way the app runs them. The reply carries the original's header into its own,
 * and the message builder refuses a line break in a header, so while the sync
 * stored one no save of the reply reached the server and its send failed the
 * same way (IMAP/SMTP audit §3b). Two shapes: a {@code References} the wire
 * folded, as RFC 5322 folds a header past 78 characters, found by the pass over
 * 1.42; and a subject whose encoded word decodes to a line break, found by the
 * pass over 1.44.
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

    /** The test account, created once: GreenMail and the context outlive a test. */
    private static @Nullable Long accountId;

    @Test
    @DisplayName("A reply to a message with a folded References header is saved to the server")
    void aReplyToAFoldedReferencesHeaderIsSaved() throws Exception {
        long account = account();

        MessageEntity original = deliverAndSync(account, "<third@greenmail.local>", "Subject: Re: a thread",
                "In-Reply-To: <second-in-the-thread@greenmail.local>",
                "References: <first-in-the-thread@greenmail.local>\r\n <second-in-the-thread@greenmail.local>");
        assertThat(original.getReferences())
                .isEqualTo("<first-in-the-thread@greenmail.local> <second-in-the-thread@greenmail.local>");

        assertReplyIsSaved(account, original);
    }

    @Test
    @DisplayName("A reply to a message whose subject decodes to a line break is saved to the server")
    void aReplyToASubjectDecodingToALineBreakIsSaved() throws Exception {
        long account = account();

        MessageEntity original = deliverAndSync(account, "<invoice@greenmail.local>",
                "Subject: =?UTF-8?Q?Invoice=0D=0Adue?=");
        assertThat(original.getSubject()).isEqualTo("Invoice due");

        assertReplyIsSaved(account, original);
    }

    private long account() throws Exception {
        Long id = accountId;
        if (id != null) {
            return id;
        }
        greenMail.setUser(EMAIL, LOGIN, PASSWORD);
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
        id = accountRepository.findByEmail(EMAIL).orElseThrow().getId();
        accountId = id;
        return id;
    }

    /** Delivers a message with the given header lines to the inbox and syncs it. */
    private MessageEntity deliverAndSync(long account, String messageId, String... headers) throws Exception {
        String raw = "From: Sender <sender@greenmail.local>\r\n" + "To: " + EMAIL + "\r\n"
                + String.join("\r\n", headers) + "\r\n" + "Message-ID: " + messageId + "\r\n"
                + "Date: Mon, 5 Oct 2026 10:00:00 +0200\r\n" + "MIME-Version: 1.0\r\n"
                + "Content-Type: text/plain; charset=UTF-8\r\n" + "\r\n" + "Hello\r\n";
        GreenMailUser user = greenMail.getUserManager().getUserByEmail(EMAIL);
        user.deliver(new MimeMessage(Session.getInstance(new Properties()),
                new ByteArrayInputStream(raw.getBytes(StandardCharsets.US_ASCII))));

        assertThat(mailSyncService.performFullSyncCycle(accountRepository.findById(account).orElseThrow(), INBOX))
                .isTrue();
        return messageRepository.findByAccountIdAndMessageId(account, messageId).getFirst();
    }

    /** Prefills a reply to the message and saves it as the app does. */
    private void assertReplyIsSaved(long account, MessageEntity original) throws Exception {
        int draftsBefore = serverDraftCount();

        MailRequest reply = mailFacade.prepareReply(original.getStableId(), false);
        DraftRequest draft = new DraftRequest(reply.to(), reply.cc(), reply.bcc(), reply.subject(), reply.body(),
                reply.attachments(), reply.inReplyTo(), reply.references());
        DraftPersistenceService.DraftIdentity identity = draftPersistenceService.acceptDraftSave(account, draft, null);
        draftPersistenceService.saveDraftAsync(account, draft, null, identity);

        // The APPENDUID upsert makes the row addressable without a sync: the save was
        // built and stored.
        await(() -> messageService.getByStableId(identity.stableId()).isPresent());
        assertThat(serverDraftCount()).isEqualTo(draftsBefore + 1);
        assertThat(accountRepository.findById(account).orElseThrow().getLastErrorCode()).isNull();
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
