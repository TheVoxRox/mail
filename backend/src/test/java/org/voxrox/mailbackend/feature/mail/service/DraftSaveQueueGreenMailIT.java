package org.voxrox.mailbackend.feature.mail.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.ref.WeakReference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.function.BooleanSupplier;

import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.Store;

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
import org.voxrox.mailbackend.feature.mail.dto.MailRequest.AttachmentRequest;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetup;

/**
 * Autosaves of one draft queued behind a held IMAP lane, over a real server
 * (IMAP/SMTP audit B1-25). The 202 returns before the APPEND, the APPEND waits
 * for the account's background lane, and a sync or a slow upload holds that
 * lane; each save the user's pauses add in the meantime used to wait with its
 * content, attachments included, and to append a copy of the draft that the
 * next save could not see to delete. Here the lane is held the way a sync holds
 * it while the saves are accepted and dispatched exactly as
 * {@code DraftController} does, then released. The same queue with a draft
 * reopened from an older revision lost the newer save's edits (B1-26).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "mail.client.sync.initial-delay=PT1H", "mail.test-context=DraftSaveQueueGreenMailIT"})
@ContextConfiguration(initializers = StorageContextInitializer.class)
class DraftSaveQueueGreenMailIT {

    private static final Path DATA_DIR = Path.of("target", "test-tmp", "DraftSaveQueueGreenMailIT").toAbsolutePath()
            .normalize();

    private static final String PASSWORD = "queue-password";
    private static final String DRAFTS = "Drafts";
    /** Saves accepted while the lane is held: fewer than the user-mail permits. */
    private static final int QUEUED_SAVES = 6;
    private static final Duration AWAIT_TIMEOUT = Duration.ofSeconds(20);

    static {
        try {
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
    private DraftPersistenceService draftPersistenceService;
    @Autowired
    private ImapConnectionManager imapConnectionManager;
    @Autowired
    private MessageService messageService;

    @Test
    @DisplayName("Autosaves queued behind a held lane keep only the newest one's content and leave only it on the server")
    void queuedSavesKeepOnlyTheNewest() throws Exception {
        String login = "queue-user";
        Long accountId = createAccount(login);
        String stored = save(accountId, revision(0), null);
        await(() -> messageService.getByStableId(stored).isPresent());

        List<WeakReference<DraftRequest>> contents = new ArrayList<>();
        String replaces = stored;
        try (LaneHold _ = holdLane(accountId)) {
            for (int i = 1; i <= QUEUED_SAVES; i++) {
                DraftRequest request = revision(i);
                contents.add(new WeakReference<>(request));
                replaces = save(accountId, request, replaces);
            }
            // Until 1.79 every task waiting for the lane held its content, and with 25 MB
            // attached the packaged heap ran out at the fifth (B1-25, measured).
            List<WeakReference<DraftRequest>> followed = contents.subList(0, QUEUED_SAVES - 1);
            awaitCollected(followed);
            assertThat(contents.getLast().get()).as("the newest save's content, still to append").isNotNull();
        }

        String newest = replaces;
        await(() -> messageService.getByStableId(newest).isPresent());
        // Until 1.79 each queued save appended a copy of the draft and looked up the
        // revisions to delete before the previous one had a row, so four copies were
        // left after a queue of twelve until the next stored save (B1-25, measured).
        awaitServerDrafts(login, List.of("Revision " + QUEUED_SAVES));
        AccountEntity account = accountRepository.findById(accountId).orElseThrow();
        assertThat(account.getLastErrorCode()).as("no save is reported failed").isNull();
    }

    /**
     * The user leaves the composer while its save waits for the lane, and the
     * Drafts list, which gets the newer revision's row only once it is stored,
     * still shows the revision before it; the user opens that one again and edits
     * it. Until 1.82 the reopened save joined the draft's chain as its newest, so
     * the save it does not descend from was skipped and its edits were lost
     * (IMAP/SMTP audit B1-26, measured at 1.80).
     */
    @Test
    @DisplayName("A save of a reopened older revision keeps the newer save still waiting for the lane, as a draft of its own")
    void aReopenedRevisionKeepsTheWaitingSave() throws Exception {
        String login = "reopen-waiting";
        Long accountId = createAccount(login);
        String stored = save(accountId, draft("Stored"), null);
        await(() -> messageService.getByStableId(stored).isPresent());

        try (LaneHold _ = holdLane(accountId)) {
            save(accountId, draft("Left in the composer"), stored);
            save(accountId, draft("Reopened"), stored);
        }

        awaitServerDrafts(login, List.of("Left in the composer", "Reopened"));
        AccountEntity account = accountRepository.findById(accountId).orElseThrow();
        assertThat(account.getLastErrorCode()).as("no save is reported failed").isNull();
    }

    /**
     * The same reopening once the newer save is stored, from a list read before its
     * row appeared. Until 1.82 the reopened save, stored, deleted every earlier
     * revision of the chain it had joined, the newer one with them (IMAP/SMTP audit
     * B1-26).
     */
    @Test
    @DisplayName("A stored save of a reopened older revision leaves the newer revision it does not descend from on the server")
    void aReopenedRevisionLeavesTheNewerOneStored() throws Exception {
        String login = "reopen-stored";
        Long accountId = createAccount(login);
        String stored = save(accountId, draft("Stored"), null);
        await(() -> messageService.getByStableId(stored).isPresent());
        String newer = save(accountId, draft("Saved later"), stored);
        await(() -> messageService.getByStableId(newer).isPresent());

        String reopened = save(accountId, draft("Reopened"), stored);
        await(() -> messageService.getByStableId(reopened).isPresent());

        awaitServerDrafts(login, List.of("Reopened", "Saved later"));
        assertThat(messageService.getByStableId(newer)).as("the newer revision's row").isPresent();
    }

    /** One save the way {@code DraftController} dispatches it; its stableId. */
    private String save(Long accountId, DraftRequest request, String replaces) {
        DraftPersistenceService.DraftIdentity identity = draftPersistenceService.acceptDraftSave(accountId, request,
                replaces);
        draftPersistenceService.saveDraftAsync(accountId, replaces, identity);
        return identity.stableId();
    }

    private static DraftRequest revision(int revision) {
        byte[] data = new byte[256 * 1024];
        Arrays.fill(data, (byte) revision);
        AttachmentRequest attachment = new AttachmentRequest("podklady.bin", "application/octet-stream",
                Base64.getEncoder().encodeToString(data));
        return new DraftRequest("to@greenmail.local", null, null, "Revision " + revision, "Body " + revision,
                List.of(attachment), null, null);
    }

    private static DraftRequest draft(String subject) {
        return new DraftRequest("to@greenmail.local", null, null, subject, "Body of " + subject, null, null, null);
    }

    /**
     * Waits until the account's Drafts folder holds exactly {@code subjects}, in
     * any order, and fails with what it held last.
     */
    private void awaitServerDrafts(String login, List<String> subjects) throws InterruptedException {
        List<String> expected = subjects.stream().sorted().toList();
        long deadline = System.nanoTime() + AWAIT_TIMEOUT.toNanos();
        while (true) {
            List<String> held = serverDraftSubjects(login).stream().sorted().toList();
            if (held.equals(expected)) {
                return;
            }
            if (System.nanoTime() > deadline) {
                throw new AssertionError("Drafts holds " + held + " after " + AWAIT_TIMEOUT + ", not " + expected);
            }
            Thread.sleep(100);
        }
    }

    /** The account's background lane, held the way a sync holds it until closed. */
    private LaneHold holdLane(Long accountId) throws InterruptedException {
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread holder = Thread.ofVirtual().start(
                () -> imapConnectionManager.executeWithLock(accountId, ImapConnectionManager.Lane.BACKGROUND, store -> {
                    held.countDown();
                    try {
                        release.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return null;
                }));
        held.await();
        return new LaneHold(release, holder);
    }

    private record LaneHold(CountDownLatch release, Thread holder) implements AutoCloseable {
        @Override
        public void close() throws InterruptedException {
            release.countDown();
            holder.join();
        }
    }

    /**
     * An account on its own GreenMail user, so each test has a Drafts folder of its
     * own.
     */
    private Long createAccount(String login) throws Exception {
        String email = login + "@greenmail.local";
        greenMail.setUser(email, login, PASSWORD);
        withStore(login, store -> {
            Folder drafts = store.getFolder(DRAFTS);
            if (!drafts.exists()) {
                drafts.create(Folder.HOLDS_MESSAGES);
            }
            return null;
        });
        MailServerSettings server = new MailServerSettings("127.0.0.1", greenMail.getImaps().getPort(), true);
        accountService.createAccount(
                new AccountCreateRequest("Queue IT", null, email, null, server, server, login, PASSWORD));
        return accountRepository.findByEmail(email).orElseThrow().getId();
    }

    private List<String> serverDraftSubjects(String login) {
        try {
            return withStore(login, store -> {
                Folder drafts = store.getFolder(DRAFTS);
                drafts.open(Folder.READ_ONLY);
                try {
                    List<String> subjects = new ArrayList<>();
                    for (Message message : drafts.getMessages()) {
                        subjects.add(message.getSubject());
                    }
                    return subjects;
                } finally {
                    drafts.close(false);
                }
            });
        } catch (Exception e) {
            throw new IllegalStateException("Could not list the Drafts folder", e);
        }
    }

    @FunctionalInterface
    private interface StoreAction<R> {
        R apply(Store store) throws Exception;
    }

    /** A fresh session beside the backend's pooled one, as a second client. */
    private <R> R withStore(String login, StoreAction<R> action) throws Exception {
        Properties props = new Properties();
        props.put("mail.store.protocol", "imaps");
        Session session = Session.getInstance(props);
        try (Store store = session.getStore("imaps")) {
            store.connect("127.0.0.1", greenMail.getImaps().getPort(), login, PASSWORD);
            return action.apply(store);
        }
    }

    /** Collects until every reference is cleared, or fails at the timeout. */
    private static void awaitCollected(List<WeakReference<DraftRequest>> references) throws InterruptedException {
        long deadline = System.nanoTime() + AWAIT_TIMEOUT.toNanos();
        while (references.stream().anyMatch(reference -> reference.get() != null)) {
            if (System.nanoTime() > deadline) {
                long held = references.stream().filter(reference -> reference.get() != null).count();
                throw new AssertionError(held + " of the followed saves still hold their content while they wait");
            }
            System.gc();
            Thread.sleep(50);
        }
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + AWAIT_TIMEOUT.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("Condition not met within " + AWAIT_TIMEOUT);
            }
            Thread.sleep(100);
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
