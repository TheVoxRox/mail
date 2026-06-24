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

import jakarta.mail.Address;
import jakarta.mail.FetchProfile;
import jakarta.mail.Flags;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.UIDFolder;
import jakarta.mail.internet.InternetAddress;
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
import org.voxrox.mailbackend.feature.mail.repository.MessageRepository;

/**
 * Integration test for the "malformed BODYSTRUCTURE" robustness path against a
 * real SQLite DB and the real persistence graph ({@code MessageDownloader} +
 * {@code MessageFetcher} + {@code MessageMapper} + {@code MessageRepository} +
 * {@code ThreadingService}).
 *
 * <p>
 * {@link MessageFetcherTest} already proves, at the unit level, that a message
 * whose lazy BODYSTRUCTURE fetch fails is turned into an envelope-only DTO
 * stub. What that unit test cannot show is the end-to-end guarantee the product
 * actually depends on: that the stub <em>survives persistence and shows up in
 * the folder listing</em> instead of being silently dropped. That is exactly
 * the Seznam.cz INBOX scenario from the backlog — Seznam occasionally returns a
 * malformed BODYSTRUCTURE for individual messages, and those messages must
 * still appear in the list (envelope-only) rather than vanish or break the
 * whole sync.
 *
 * <p>
 * The malformed condition is non-deterministic on a live Seznam mailbox (and we
 * have no such account in CI), so it is reproduced deterministically here: the
 * sync is driven through the real service graph with a Mockito-mocked
 * {@link Folder}/{@link UIDFolder} that serves one good message and one whose
 * {@code isMimeType(...)} throws — the precise seam where a malformed
 * BODYSTRUCTURE surfaces during the MIME walk. The assertions then read back
 * the real SQLite rows through the same queries the folder-listing endpoint
 * uses.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        // Keep the background scheduler out of the test — the sync run is explicit.
        "mail.client.sync.initial-delay=PT1H"})
@ContextConfiguration(initializers = StorageContextInitializer.class)
class MalformedBodyStructureSyncIT {

    private static final Path DATA_DIR = Path.of("target", "test-tmp", "MalformedBodyStructureSyncIT").toAbsolutePath()
            .normalize();

    private static final String EMAIL = "malformed-it@example.local";
    private static final String LOGIN = "malformed-user";
    private static final String PASSWORD = "malformed-password";
    private static final String INBOX = "INBOX";

    private static final long GOOD_UID = 1L;
    private static final long STUB_UID = 2L;

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

    /** Mock that implements both Folder and UIDFolder — matches IMAPFolder. */
    private Folder folder;
    private UIDFolder uidFolder;
    private final Map<Message, Long> uidMap = new HashMap<>();

    @BeforeEach
    void setUp() throws Exception {
        account = accountRepository.findByEmail(EMAIL).orElseGet(() -> {
            // Server settings are never used — the sync is driven through a mocked
            // folder, not a live connection — but the account row must exist for the FK.
            MailServerSettings server = new MailServerSettings("127.0.0.1", 993, true);
            accountService.createAccount(
                    new AccountCreateRequest("Malformed IT", null, EMAIL, null, server, server, LOGIN, PASSWORD));
            return accountRepository.findByEmail(EMAIL).orElseThrow();
        });

        uidMap.clear();
        folder = mock(Folder.class, withSettings().extraInterfaces(UIDFolder.class));
        uidFolder = (UIDFolder) folder;
        // fetch() is a no-op: the test messages already carry their headers / stubs.
        doNothing().when(folder).fetch(any(Message[].class), any(FetchProfile.class));
        when(uidFolder.getUID(any(Message.class))).thenAnswer(inv -> uidMap.getOrDefault(inv.getArgument(0), 0L));
    }

    @Test
    @DisplayName("Initial INBOX sync persists a malformed-BODYSTRUCTURE message as an envelope-only stub that still appears in the listing")
    void malformedMessageIsPersistedAsEnvelopeStubAndListed() throws Exception {
        Message good = registerUid(goodMessage(), GOOD_UID);
        Message stub = registerUid(malformedMessage(), STUB_UID);

        // Initial sync: lastKnownUid == 0 -> downloadInitialWindowBySequence, the path
        // a fresh Seznam INBOX hits on first connect.
        when(uidFolder.getUIDNext()).thenReturn(STUB_UID + 1);
        when(folder.getMessageCount()).thenReturn(2);
        when(folder.getMessages(1, 2)).thenReturn(new Message[]{good, stub});

        FolderSyncStateEntity syncState = syncStateService.getOrCreateState(account.getId(), INBOX, FolderRole.INBOX);
        // Real sync stamps UIDVALIDITY when the folder is opened (handleUidValidity);
        // the column is NOT NULL, so set it here since we bypass the open step.
        syncState.setUidValidity(1L);
        syncStateService.updateUidValidity(syncState.getId(), 1L);
        FolderSyncContext ctx = new FolderSyncContext(account, INBOX, folder, uidFolder, syncState);

        int downloaded = messageDownloader.syncNewMessages(ctx);

        // Neither message is dropped: the malformed one is downloaded too.
        assertThat(downloaded).isEqualTo(2);
        assertThat(messageRepository.countByAccountIdAndFolderName(account.getId(), INBOX)).isEqualTo(2);

        // The folder-listing query (the same one the list endpoint uses) returns both,
        // so the malformed message is visible to the user, not silently missing.
        Page<MailSummaryResponse> listing = messageRepository.findSummariesByAccountAndFolder(account.getId(), INBOX,
                PageRequest.of(0, 20));
        assertThat(listing.getTotalElements()).isEqualTo(2);
        assertThat(listing.getContent()).extracting(MailSummaryResponse::uid).containsExactlyInAnyOrder(GOOD_UID,
                STUB_UID);

        // The stub row: envelope metadata intact, but body unfetched (null) and no
        // attachments — exactly an "envelope-only stub".
        MessageEntity stubRow = rowByUid(STUB_UID);
        assertThat(stubRow.getSubject()).isEqualTo("Malformed newsletter");
        assertThat(stubRow.getSender()).contains("sender@example.com");
        assertThat(stubRow.getRecipientsTo()).contains("rcpt@example.com");
        assertThat(stubRow.getContent()).isNull();
        assertThat(stubRow.isHasAttachments()).isFalse();
        assertThat(stubRow.getAttachments()).isEmpty();

        // The good message persists normally alongside it.
        MessageEntity goodRow = rowByUid(GOOD_UID);
        assertThat(goodRow.getSubject()).isEqualTo("Good message");
        assertThat(goodRow.isHasAttachments()).isFalse();
    }

    private MessageEntity rowByUid(long uid) {
        String stableId = messageRepository.findByAccountIdAndFolderName(account.getId(), INBOX, PageRequest.of(0, 20))
                .stream().filter(m -> m.getUid() == uid).map(MessageEntity::getStableId).findFirst()
                .orElseThrow(() -> new AssertionError("No persisted message with uid " + uid));
        // Reload with JOIN FETCH so the lazy attachments collection is initialized for
        // assertions made outside any open session.
        return messageRepository.findByStableIdWithAttachments(stableId).orElseThrow();
    }

    private Message registerUid(Message msg, long uid) {
        uidMap.put(msg, uid);
        return msg;
    }

    private static MimeMessage goodMessage() throws MessagingException {
        String raw = "From: Alice <alice@example.com>\r\n" + "To: rcpt@example.com\r\n" + "Subject: Good message\r\n"
                + "Date: Thu, 15 Jan 2026 10:30:00 +0000\r\n" + "Message-ID: <good@example.com>\r\n"
                + "Content-Type: text/plain; charset=UTF-8\r\n" + "\r\n" + "Hello body\r\n";
        Session session = Session.getInstance(new Properties());
        return new MimeMessage(session, new ByteArrayInputStream(raw.getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * A message whose envelope (subject/from/to/flags) is readable but whose MIME
     * walk fails — {@code isMimeType("multipart/*")} throws, exactly like JavaMail
     * does when it cannot parse a malformed BODYSTRUCTURE returned by the server.
     */
    private static Message malformedMessage() throws MessagingException {
        Message msg = mock(Message.class);
        when(msg.getSubject()).thenReturn("Malformed newsletter");
        when(msg.getFrom()).thenReturn(new Address[]{new InternetAddress("sender@example.com")});
        when(msg.getRecipients(Message.RecipientType.TO))
                .thenReturn(new Address[]{new InternetAddress("rcpt@example.com")});
        when(msg.getRecipients(Message.RecipientType.CC)).thenReturn(null);
        when(msg.getReceivedDate()).thenReturn(null);
        when(msg.getSentDate()).thenReturn(null);
        when(msg.getFlags()).thenReturn(new Flags());
        when(msg.getHeader("In-Reply-To")).thenReturn(null);
        when(msg.getHeader("References")).thenReturn(null);
        when(msg.isMimeType("multipart/*")).thenThrow(new MessagingException("Unable to load BODYSTRUCTURE"));
        return msg;
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
