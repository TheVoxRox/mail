package org.voxrox.mailbackend.feature.mail.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.Properties;
import java.util.function.BooleanSupplier;

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
import org.voxrox.mailbackend.feature.mail.dto.DraftRequest;
import org.voxrox.mailbackend.feature.mail.entity.MessageEntity;
import org.voxrox.mailbackend.feature.mail.repository.MessageRepository;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetup;

/**
 * Integration test of the batch-3 draft-lifecycle backend contract (PR-A) over
 * a real IMAP server (GreenMail) — the wire-level complement to the Mockito
 * unit tests in {@code SmtpMessageServiceTest} / {@code DraftControllerTest},
 * which stub the protocol away and so cannot prove the parts that depend on a
 * live server: the UIDPLUS {@code APPENDUID} response, IMAP expunge of a
 * superseded revision, and the {@code RecipientType.BCC} read off a serialized
 * MIME body.
 *
 * <p>
 * Three phases share one Drafts folder and DB (like
 * {@code MailSyncGreenMailIT}): a single mailbox walked through the lifecycle
 * the redesign targets ({@code docs/COMPOSE_DRAFT_LIFECYCLE.md} §5).
 *
 * <ol>
 * <li><b>B1 identity at save time.</b> A save appends via
 * {@code appendUIDMessages} and, on the APPENDUID, upserts the local row
 * immediately under the deterministic stableId the controller already returned
 * — addressable without waiting for a sync.</li>
 * <li><b>B1 replaces chain.</b> Three revisions chained by {@code replaces=}
 * leave exactly one draft on the server and one local row. This only collapses
 * because the APPENDUID upsert made the previous revision's UID known
 * synchronously, so the replace could expunge it — the very mechanism under
 * test.</li>
 * <li><b>B3 Bcc round-trip.</b> The local row is dropped and the Drafts folder
 * re-synced, so the reopened draft is reconstructed purely from the server
 * MIME: it proves {@code MimeMessageBuilder} wrote the Bcc header, it survived
 * serialization, {@code MessageFetcher} reads {@code RecipientType.BCC}, and
 * the wire-derived stableId matches the save-time one (the drift guard).</li>
 * </ol>
 *
 * <p>
 * <b>Out of scope here (stays unit-level):</b> B2 send-supersede and the
 * failed-send recovery draft. Both hinge on a successful SMTP delivery, and
 * {@code SmtpTransportFactory} mandates verified TLS (STARTTLS-required or
 * implicit SSL with {@code checkserveridentity}); GreenMail's loopback SMTP
 * cannot satisfy that without weakening the production security posture, so the
 * delivery is stubbed in {@code SmtpMessageServiceTest}
 * (supersede-on-delivered, keep-on-failure, recovery-draft) instead.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        // Keep the background scheduler out of the test — draft saves and syncs are
        // explicit.
        "mail.client.sync.initial-delay=PT1H"})
@ContextConfiguration(initializers = StorageContextInitializer.class)
class DraftLifecycleGreenMailIT {

    private static final Path DATA_DIR = Path.of("target", "test-tmp", "DraftLifecycleGreenMailIT").toAbsolutePath()
            .normalize();

    private static final String EMAIL = "draft-it@greenmail.local";
    private static final String LOGIN = "draft-user";
    private static final String PASSWORD = "draft-password";
    private static final String DRAFTS = "Drafts";
    private static final String BCC_ADDRESS = "hidden@greenmail.local";

    /**
     * Async draft saves run on a real virtual-thread executor; poll their side
     * effects.
     */
    private static final Duration AWAIT_TIMEOUT = Duration.ofSeconds(10);

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
    @Autowired
    private SmtpMessageService smtpService;
    @Autowired
    private MessageService messageService;

    private AccountEntity account;

    @BeforeEach
    void setUpAccount() throws Exception {
        greenMail.setUser(EMAIL, LOGIN, PASSWORD);
        // The Drafts folder must exist before any backend folder listing so the
        // DRAFTS role resolves (GreenMail advertises no SPECIAL-USE — role detection
        // falls back to the "Drafts" name).
        createDraftsFolder();
        account = accountRepository.findByEmail(EMAIL).orElseGet(() -> {
            int imapPort = greenMail.getImap().getPort();
            MailServerSettings server = new MailServerSettings("127.0.0.1", imapPort, false);
            accountService.createAccount(
                    new AccountCreateRequest("Draft IT", null, EMAIL, null, server, server, LOGIN, PASSWORD));
            return accountRepository.findByEmail(EMAIL).orElseThrow();
        });
    }

    @Test
    @DisplayName("Draft lifecycle over live IMAP: APPENDUID upsert -> replaces chain collapses -> Bcc round-trips")
    void draftLifecycleOverLiveImap() throws Exception {
        Long accountId = account.getId();

        // --- Phase 1: identity minted at save time, upserted on the APPENDUID -------
        SmtpMessageService.DraftIdentity id1 = smtpService.prepareDraftIdentity(accountId);
        assertThat(id1.draftsFolder()).isEqualTo(DRAFTS);
        smtpService.saveDraftAsync(accountId, draft("Draft revision one"), "", id1);

        // The row becomes addressable without a sync — proof the APPENDUID upsert ran
        // and that the id the controller returned in the 202 is the id the row gets.
        await(() -> messageService.getByStableId(id1.stableId()).isPresent());
        MessageEntity row1 = messageService.getByStableId(id1.stableId()).orElseThrow();
        assertThat(row1.getFolderName()).isEqualTo(DRAFTS);
        assertThat(row1.getUid()).isNotNull();
        assertThat(serverDraftCount()).isEqualTo(1);

        // --- Phase 2: a replaces= chain of three revisions leaves exactly one draft --
        SmtpMessageService.DraftIdentity id2 = smtpService.prepareDraftIdentity(accountId);
        smtpService.saveDraftAsync(accountId, draft("Draft revision two"), id1.stableId(), id2);
        await(() -> messageService.getByStableId(id2.stableId()).isPresent()
                && messageService.getByStableId(id1.stableId()).isEmpty());

        SmtpMessageService.DraftIdentity id3 = smtpService.prepareDraftIdentity(accountId);
        smtpService.saveDraftAsync(accountId, draft("Draft revision three"), id2.stableId(), id3);
        await(() -> messageService.getByStableId(id3.stableId()).isPresent()
                && messageService.getByStableId(id2.stableId()).isEmpty());

        // Both the server and the local DB converge on the single latest revision.
        assertThat(serverDraftCount()).isEqualTo(1);
        assertThat(messageRepository.countByAccountIdAndFolderName(accountId, DRAFTS)).isEqualTo(1);

        // --- Phase 3: Bcc survives save -> server MIME -> re-sync -> reopen ----------
        // Drop the upsert echo so the next assertion can only pass if the row is
        // rebuilt from the wire: same deterministic stableId, Bcc read off the MIME.
        messageService.deleteByStableId(id3.stableId());
        assertThat(messageService.getByStableId(id3.stableId())).isEmpty();

        assertThat(mailSyncService.performFullSyncCycle(account, DRAFTS)).isTrue();

        MessageEntity reSynced = messageService.getByStableId(id3.stableId()).orElseThrow();
        assertThat(reSynced.getRecipientsBcc()).contains(BCC_ADDRESS);
        assertThat(messageRepository.countByAccountIdAndFolderName(accountId, DRAFTS)).isEqualTo(1);
    }

    // ---- helpers --------------------------------------------------------

    /** A draft carrying a Bcc, distinguished only by subject/body per revision. */
    private DraftRequest draft(String subject) {
        return new DraftRequest("to@greenmail.local", null, BCC_ADDRESS, subject, subject + " body", null, null, null);
    }

    /**
     * Counts messages currently in the Drafts folder over an independent session.
     */
    private int serverDraftCount() throws Exception {
        return withStore(store -> {
            Folder drafts = store.getFolder(DRAFTS);
            drafts.open(Folder.READ_ONLY);
            try {
                return drafts.getMessageCount();
            } finally {
                if (drafts.isOpen()) {
                    drafts.close(false);
                }
            }
        });
    }

    private void createDraftsFolder() throws Exception {
        withStore(store -> {
            Folder drafts = store.getFolder(DRAFTS);
            if (!drafts.exists()) {
                drafts.create(Folder.HOLDS_MESSAGES);
            }
            return null;
        });
    }

    @FunctionalInterface
    private interface StoreAction<R> {
        R apply(Store store) throws Exception;
    }

    /**
     * Runs an action against a fresh IMAP session, separate from the backend's
     * pooled connection — the test acts as an independent second client.
     */
    private <R> R withStore(StoreAction<R> action) throws Exception {
        Properties props = new Properties();
        props.put("mail.store.protocol", "imap");
        Session session = Session.getInstance(props);
        Store store = session.getStore("imap");
        store.connect("127.0.0.1", greenMail.getImap().getPort(), LOGIN, PASSWORD);
        try {
            return action.apply(store);
        } finally {
            store.close();
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
                throw new IllegalStateException("Interrupted while awaiting an async draft outcome", e);
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
