package org.voxrox.mailbackend.feature.mail.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import jakarta.mail.FetchProfile;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.UIDFolder;
import jakarta.mail.internet.MimeMessage;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ContextConfiguration;
import org.voxrox.mailbackend.core.init.StorageContextInitializer;
import org.voxrox.mailbackend.feature.account.dto.AccountCreateRequest;
import org.voxrox.mailbackend.feature.account.dto.MailServerSettings;
import org.voxrox.mailbackend.feature.account.entity.AccountEntity;
import org.voxrox.mailbackend.feature.account.repository.AccountRepository;
import org.voxrox.mailbackend.feature.account.service.AccountService;
import org.voxrox.mailbackend.feature.mail.dto.FolderRole;
import org.voxrox.mailbackend.feature.mail.dto.MailSummaryResponse;
import org.voxrox.mailbackend.feature.mail.entity.FolderSyncStateEntity;
import org.voxrox.mailbackend.feature.mail.entity.MessageEntity;
import org.voxrox.mailbackend.feature.mail.mapper.MessageStableId;
import org.voxrox.mailbackend.feature.mail.repository.MessageRepository;

/**
 * Integration test for two copies of one Message-ID inside a single folder,
 * against a real SQLite DB and the real persistence graph
 * ({@code MessageDownloader} + {@code MessageMapper} +
 * {@code MessageRepository} + {@code ThreadingService}).
 *
 * <p>
 * {@code stableId} is derived from the RFC 5322 Message-ID, which identifies
 * the <em>content</em> of a message rather than a particular copy of it. A
 * trash folder collects deletions from every other folder, so deleting a
 * message from the inbox and its own copy from Sent leaves two distinct IMAP
 * messages — different uids, identical Message-ID — in the same folder, and the
 * derivation hands both the same id.
 *
 * <p>
 * What that cost in production is the reason this test exists at the
 * persistence seam rather than in
 * {@link org.voxrox.mailbackend.feature.mail.mapper.MessageStableIdTest}: the
 * pair reached {@code saveAll} together, the {@code stable_id} unique
 * constraint aborted the <em>whole</em> 100-message batch, and because the
 * rollback left {@code lastKnownUid} at 0 every later sync re-downloaded the
 * same window and failed identically. The folder did not degrade — it stayed
 * empty permanently. So the guarantee under test is not "the id is unique" but
 * "both copies persist and the rest of the batch survives".
 *
 * <p>
 * Reproduced deterministically through a Mockito-mocked {@link Folder} /
 * {@link UIDFolder} serving real {@link MimeMessage}s, since the condition
 * needs a mailbox shaped a certain way rather than a live server (and there is
 * no Seznam.cz account in CI). Assertions read the real SQLite rows back
 * through the same query the folder-listing endpoint uses.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        // Keep the background scheduler out of the test — the sync run is explicit.
        "mail.client.sync.initial-delay=PT1H"})
@ContextConfiguration(initializers = StorageContextInitializer.class)
class DuplicateMessageIdSyncIT {

    private static final Path DATA_DIR = Path.of("target", "test-tmp", "DuplicateMessageIdSyncIT").toAbsolutePath()
            .normalize();

    private static final String EMAIL = "duplicate-mid-it@example.local";
    private static final String LOGIN = "duplicate-mid-user";
    private static final String PASSWORD = "duplicate-mid-password";
    private static final long UID_VALIDITY = 153L;

    /** The Message-ID both deleted copies carry. */
    private static final String SHARED_MID = "<shared@example.com>";
    private static final long INBOX_COPY_UID = 500L;
    private static final long SENT_COPY_UID = 900L;
    private static final long UNRELATED_UID = 950L;
    /**
     * The late copy's uid in the incremental test. Deliberately {@code +1} on the
     * first one: the download loop walks the range in {@code window-size} chunks,
     * so a one-uid range is the only one that stays a single, predictable
     * {@code getMessagesByUID} call whatever that setting is.
     */
    private static final long LATE_COPY_UID = INBOX_COPY_UID + 1;

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
    private MessageDownloader messageDownloader;
    @Autowired
    private SyncStateService syncStateService;

    private AccountEntity account;
    private String folder;

    /** Mock that implements both Folder and UIDFolder — matches IMAPFolder. */
    private Folder imapFolder;
    private UIDFolder uidFolder;
    private final Map<Message, Long> uidMap = new HashMap<>();

    @BeforeEach
    void setUp() throws Exception {
        account = accountRepository.findByEmail(EMAIL).orElseGet(() -> {
            // Server settings are never used — the sync is driven through a mocked
            // folder, not a live connection — but the account row must exist for the FK.
            MailServerSettings server = new MailServerSettings("127.0.0.1", 993, true);
            accountService.createAccount(
                    new AccountCreateRequest("Duplicate MID IT", null, EMAIL, null, server, server, LOGIN, PASSWORD));
            return accountRepository.findByEmail(EMAIL).orElseThrow();
        });
        // Each test names its own folder: the Spring context (and so the DB) is shared
        // across the class, and the rows of one test would otherwise be the
        // "already persisted" state of the next.
        uidMap.clear();
        imapFolder = mock(Folder.class, withSettings().extraInterfaces(UIDFolder.class));
        uidFolder = (UIDFolder) imapFolder;
        // fetch() is a no-op: the test messages already carry their headers.
        doNothing().when(imapFolder).fetch(any(Message[].class), any(FetchProfile.class));
        when(uidFolder.getUID(any(Message.class))).thenAnswer(inv -> uidMap.getOrDefault(inv.getArgument(0), 0L));
    }

    @Test
    @DisplayName("Both copies of one Message-ID persist in one batch, and the rest of the batch survives with them")
    void bothCopiesPersistAndTheBatchSurvives() throws Exception {
        folder = "trash-one-batch";
        Message inboxCopy = registerUid(message(SHARED_MID, "Deleted twice"), INBOX_COPY_UID);
        Message sentCopy = registerUid(message(SHARED_MID, "Deleted twice"), SENT_COPY_UID);
        Message unrelated = registerUid(message("<other@example.com>", "Something else"), UNRELATED_UID);

        // Initial sync: lastKnownUid == 0 -> downloadInitialWindowBySequence, the path
        // a freshly added Seznam account hits on first connect.
        when(uidFolder.getUIDNext()).thenReturn(UNRELATED_UID + 1);
        when(imapFolder.getMessageCount()).thenReturn(3);
        when(imapFolder.getMessages(1, 3)).thenReturn(new Message[]{inboxCopy, sentCopy, unrelated});

        int downloaded = messageDownloader.syncNewMessages(context());

        assertThat(downloaded).isEqualTo(3);
        assertThat(messageRepository.countByAccountIdAndFolderName(account.getId(), folder)).isEqualTo(3);

        // The unrelated message is the one the incident actually lost: a single
        // colliding pair aborted the batch it happened to travel in.
        Page<MailSummaryResponse> listing = messageRepository.findSummariesByAccountAndFolder(account.getId(), folder,
                PageRequest.of(0, 20));
        assertThat(listing.getContent()).extracting(MailSummaryResponse::uid).containsExactlyInAnyOrder(INBOX_COPY_UID,
                SENT_COPY_UID, UNRELATED_UID);

        // The lower uid keeps the Message-ID identity; the second copy moves onto the
        // uid identity, so the two rows are addressable apart.
        assertThat(rowByUid(INBOX_COPY_UID).getStableId())
                .isEqualTo(MessageStableId.compute(account.getId(), folder, SHARED_MID, INBOX_COPY_UID, UID_VALIDITY));
        assertThat(rowByUid(SENT_COPY_UID).getStableId())
                .isEqualTo(MessageStableId.computeFromUid(account.getId(), folder, SENT_COPY_UID, UID_VALIDITY));

        // Both copies are still the same conversation.
        assertThat(rowByUid(SENT_COPY_UID).getThreadId()).isEqualTo(rowByUid(INBOX_COPY_UID).getThreadId());
    }

    @Test
    @DisplayName("A copy arriving in a later batch collides with the committed row and takes the uid identity")
    void secondCopyArrivingLaterTakesTheUidIdentity() throws Exception {
        folder = "trash-two-batches";
        Message inboxCopy = registerUid(message(SHARED_MID, "Deleted twice"), INBOX_COPY_UID);

        when(uidFolder.getUIDNext()).thenReturn(INBOX_COPY_UID + 1);
        when(imapFolder.getMessageCount()).thenReturn(1);
        when(imapFolder.getMessages(1, 1)).thenReturn(new Message[]{inboxCopy});
        assertThat(messageDownloader.syncNewMessages(context())).isEqualTo(1);

        // The second copy is deleted later and arrives on the incremental path, where
        // the colliding id sits in a committed row rather than in the same batch.
        Message lateCopy = registerUid(message(SHARED_MID, "Deleted twice"), LATE_COPY_UID);
        when(uidFolder.getUIDNext()).thenReturn(LATE_COPY_UID + 1);
        when(uidFolder.getMessagesByUID(LATE_COPY_UID, LATE_COPY_UID)).thenReturn(new Message[]{lateCopy});

        assertThat(messageDownloader.syncNewMessages(context())).isEqualTo(1);

        assertThat(messageRepository.countByAccountIdAndFolderName(account.getId(), folder)).isEqualTo(2);
        // The first copy keeps the id the client already holds — the whole point of a
        // deterministic stableId is that an existing reference stays valid.
        assertThat(rowByUid(INBOX_COPY_UID).getStableId())
                .isEqualTo(MessageStableId.compute(account.getId(), folder, SHARED_MID, INBOX_COPY_UID, UID_VALIDITY));
        assertThat(rowByUid(LATE_COPY_UID).getStableId())
                .isEqualTo(MessageStableId.computeFromUid(account.getId(), folder, LATE_COPY_UID, UID_VALIDITY));
    }

    private FolderSyncContext context() {
        FolderSyncStateEntity syncState = syncStateService.getOrCreateState(account.getId(), folder, FolderRole.TRASH);
        // Real sync stamps UIDVALIDITY when the folder is opened (handleUidValidity);
        // the column is NOT NULL, so set it here since we bypass the open step.
        syncState.setUidValidity(UID_VALIDITY);
        syncStateService.updateUidValidity(syncState.getId(), UID_VALIDITY);
        return new FolderSyncContext(account, folder, imapFolder, uidFolder, syncState);
    }

    private MessageEntity rowByUid(long uid) {
        return messageRepository.findByAccountIdAndFolderName(account.getId(), folder, PageRequest.of(0, 20)).stream()
                .filter(m -> m.getUid() == uid).findFirst()
                .orElseThrow(() -> new AssertionError("No persisted message with uid " + uid));
    }

    private Message registerUid(Message msg, long uid) {
        uidMap.put(msg, uid);
        return msg;
    }

    /**
     * A plain message carrying the given Message-ID. Two of these with the same
     * header are what a trash folder holds after the inbox copy and the Sent copy
     * of one message are both deleted.
     */
    private static MimeMessage message(String messageId, String subject) throws MessagingException {
        String raw = """
                From: Alice <alice@example.com>\r
                To: rcpt@example.com\r
                Subject: %s\r
                Date: Thu, 15 Jan 2026 10:30:00 +0000\r
                Message-ID: %s\r
                Content-Type: text/plain; charset=UTF-8\r
                \r
                Hello body\r
                """.formatted(subject, messageId);
        Session session = Session.getInstance(new Properties());
        return new MimeMessage(session, new ByteArrayInputStream(raw.getBytes(StandardCharsets.UTF_8)));
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
