package org.voxrox.mailbackend.feature.mail.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.UIDFolder;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionTemplate;
import org.voxrox.mailbackend.core.config.MailClientProperties;
import org.voxrox.mailbackend.core.config.mail.SyncProperties;
import org.voxrox.mailbackend.feature.account.entity.AccountEntity;
import org.voxrox.mailbackend.feature.mail.dto.MailDetailResponse;
import org.voxrox.mailbackend.feature.mail.entity.FolderSyncStateEntity;
import org.voxrox.mailbackend.feature.mail.entity.MessageEntity;
import org.voxrox.mailbackend.feature.mail.mapper.MessageMapper;
import org.voxrox.mailbackend.feature.mail.repository.MessageRepository;

@ExtendWith(MockitoExtension.class)
class MessageDownloaderTest {

    private static final Long ACCOUNT_ID = 7L;
    private static final String FOLDER = "[Gmail]/All Mail";

    @Mock
    private MessageRepository messageRepository;
    @Mock
    private MessageFetcher messageFetcher;
    @Mock
    private SyncStateService syncStateService;
    @Mock
    private TransactionTemplate transactionTemplate;
    @Mock
    private MailClientProperties mailProps;
    @Mock
    private MessageMapper messageMapper;
    @Mock
    private ThreadingService threadingService;
    @Mock
    private Folder folder;
    @Mock
    private UIDFolder uidFolder;

    private MessageDownloader downloader;
    private AccountEntity account;
    private FolderSyncStateEntity syncState;

    @BeforeEach
    void setUp() {
        downloader = new MessageDownloader(messageRepository, messageFetcher, syncStateService, transactionTemplate,
                mailProps, messageMapper, threadingService);

        account = new AccountEntity();
        account.setId(ACCOUNT_ID);
        syncState = new FolderSyncStateEntity(account, FOLDER);
        syncState.setUidValidity(1L);

        SyncProperties sync = new SyncProperties(100, 200, Duration.ofMinutes(5), Duration.ofSeconds(10), 50, 30, 300,
                4, 256, 200, Duration.ofMinutes(30), Duration.ofSeconds(30));
        // lenient — tests with an empty folder / no download never ask for sync
        // properties or the transaction template. Strict would flag them as unused.
        lenient().when(mailProps.sync()).thenReturn(sync);
        lenient().doAnswer(invocation -> {
            java.util.function.Consumer<org.springframework.transaction.TransactionStatus> callback = invocation
                    .getArgument(0);
            callback.accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
    }

    @Nested
    @DisplayName("syncNewMessages — initial sync (lastKnownUid = 0)")
    class InitialSync {

        @Test
        @DisplayName("Downloads the last N messages by sequence numbers (not by UID window)")
        void downloadsLatestWindowBySequenceNumbers() throws Exception {
            Message message = stubSequenceDownload(1, 1, 1001L, 500L);

            int downloaded = downloader.syncNewMessages(context());

            assertThat(downloaded).isEqualTo(1);
            verify(folder).getMessages(1, 1);
            verify(syncStateService).updateLastKnownUid(syncState.getId(), 500L);
            verify(syncStateService).updateLastKnownUid(syncState.getId(), 1000L);
            assertThat(syncState.getLastKnownUid()).isEqualTo(1000L);
            verify(uidFolder, never()).getMessagesByUID(any(Long.class), any(Long.class));
            assertThat(message).isNotNull();
        }

        @Test
        @DisplayName("UID gap: UIDNEXT high, but messages have low UIDs — download proceeds via sequence numbers")
        void downloadsMessagesEvenWhenUidGapExceedsWindowSize() throws Exception {
            Message message = stubSequenceDownload(1, 1, 58871L, 42L);

            int downloaded = downloader.syncNewMessages(context());

            assertThat(downloaded).isEqualTo(1);
            verify(folder).getMessages(1, 1);
            verify(syncStateService).updateLastKnownUid(syncState.getId(), 42L);
            verify(syncStateService).updateLastKnownUid(syncState.getId(), 58870L);
            assertThat(syncState.getLastKnownUid()).isEqualTo(58870L);
            assertThat(message).isNotNull();
        }

        @Test
        @DisplayName("Large INBOX: from 250 messages download the last 100 (seq. 151..250)")
        void downloadsOnlyLastWindowFromLargeMailbox() throws Exception {
            when(uidFolder.getUIDNext()).thenReturn(1001L);
            when(folder.getMessageCount()).thenReturn(250);
            Message message = mock(Message.class);
            when(folder.getMessages(151, 250)).thenReturn(new Message[]{message});
            when(uidFolder.getUID(message)).thenReturn(1000L);
            MailDetailResponse dto = newDto(1000L);
            when(messageFetcher.fetchBatch(any(), eq(uidFolder), eq(FOLDER))).thenReturn(List.of(dto));
            when(messageMapper.toEntity(dto, account, FOLDER, syncState.getUidValidity()))
                    .thenReturn(new MessageEntity());

            int downloaded = downloader.syncNewMessages(context());

            assertThat(downloaded).isEqualTo(1);
            verify(folder).getMessages(151, 250);
        }

        @Test
        @DisplayName("Empty folder: downloads nothing, advances lastKnownUid to UIDNEXT-1 so it does not loop")
        void emptyFolderAdvancesLastKnownUid() throws Exception {
            when(uidFolder.getUIDNext()).thenReturn(101L);
            when(folder.getMessageCount()).thenReturn(0);

            int downloaded = downloader.syncNewMessages(context());

            assertThat(downloaded).isZero();
            verify(syncStateService).updateLastKnownUid(syncState.getId(), 100L);
            assertThat(syncState.getLastKnownUid()).isEqualTo(100L);
        }

        @Test
        @DisplayName("Truly empty folder (UIDNEXT=1): no UID advance")
        void brandNewEmptyFolderDoesNothing() throws Exception {
            when(uidFolder.getUIDNext()).thenReturn(1L);
            when(folder.getMessageCount()).thenReturn(0);

            int downloaded = downloader.syncNewMessages(context());

            assertThat(downloaded).isZero();
            verify(syncStateService, never()).updateLastKnownUid(any(), any());
            assertThat(syncState.getLastKnownUid()).isZero();
        }
    }

    @Nested
    @DisplayName("syncNewMessages — incremental sync (lastKnownUid > 0)")
    class IncrementalSync {

        @Test
        @DisplayName("Continues from lastKnownUid + 1")
        void startsAfterLastKnownUid() throws Exception {
            syncState.setLastKnownUid(950L);
            Message message = mock(Message.class);
            when(uidFolder.getUIDNext()).thenReturn(1001L);
            when(uidFolder.getMessagesByUID(951L, 1000L)).thenReturn(new Message[]{message});
            when(uidFolder.getUID(message)).thenReturn(1000L);
            MailDetailResponse dto = newDto(1000L);
            when(messageFetcher.fetchBatch(any(), eq(uidFolder), eq(FOLDER))).thenReturn(List.of(dto));
            when(messageMapper.toEntity(dto, account, FOLDER, syncState.getUidValidity()))
                    .thenReturn(new MessageEntity());

            int downloaded = downloader.syncNewMessages(context());

            assertThat(downloaded).isEqualTo(1);
            verify(uidFolder).getMessagesByUID(951L, 1000L);
            assertThat(syncState.getLastKnownUid()).isEqualTo(1000L);
        }

        @Test
        @DisplayName("Server reports new UID but the range returns empty (messages moved) — advance lastKnownUid anyway")
        void emptyRangeAdvancesLastKnownUidToPreventLoop() throws Exception {
            syncState.setLastKnownUid(950L);
            when(uidFolder.getUIDNext()).thenReturn(1001L);
            when(uidFolder.getMessagesByUID(951L, 1000L)).thenReturn(new Message[0]);

            int downloaded = downloader.syncNewMessages(context());

            assertThat(downloaded).isZero();
            verify(syncStateService).updateLastKnownUid(syncState.getId(), 1000L);
            assertThat(syncState.getLastKnownUid()).isEqualTo(1000L);
        }

        @Test
        @DisplayName("No new UIDs (lastKnownUid >= maxUid): no-op")
        void noNewUidNoOp() throws Exception {
            syncState.setLastKnownUid(1000L);
            when(uidFolder.getUIDNext()).thenReturn(1001L);

            int downloaded = downloader.syncNewMessages(context());

            assertThat(downloaded).isZero();
            verify(syncStateService, never()).updateLastKnownUid(any(), any());
        }
    }

    @Nested
    @DisplayName("downloadSequenceRange (lazy page fetch)")
    class DownloadSequenceRange {

        @Test
        @DisplayName("Downloads the messages at the given sequence positions and saves them")
        void downloadsAndSavesRange() throws Exception {
            Message message = mock(Message.class);
            when(folder.getMessages(1491, 1690)).thenReturn(new Message[]{message});
            when(uidFolder.getUID(message)).thenReturn(500L);
            MailDetailResponse dto = newDto(500L);
            when(messageFetcher.fetchBatch(any(), eq(uidFolder), eq(FOLDER))).thenReturn(List.of(dto));
            when(messageMapper.toEntity(dto, account, FOLDER, syncState.getUidValidity()))
                    .thenReturn(new MessageEntity());

            int downloaded = downloader.downloadSequenceRange(context(), 1491, 1690);

            assertThat(downloaded).isEqualTo(1);
            verify(folder).getMessages(1491, 1690);
        }

        @Test
        @DisplayName("Empty range (endSeq < startSeq) returns 0 without touching the server")
        void emptyRangeIsNoop() throws Exception {
            int downloaded = downloader.downloadSequenceRange(context(), 100, 50);

            assertThat(downloaded).isZero();
            verify(folder, never()).getMessages(anyInt(), anyInt());
        }
    }

    private FolderSyncContext context() {
        return new FolderSyncContext(account, FOLDER, folder, uidFolder, syncState);
    }

    private Message stubSequenceDownload(int startSeq, int endSeq, long uidNext, long messageUid) throws Exception {
        Message message = mock(Message.class);
        when(uidFolder.getUIDNext()).thenReturn(uidNext);
        when(folder.getMessageCount()).thenReturn(endSeq);
        when(folder.getMessages(startSeq, endSeq)).thenReturn(new Message[]{message});
        when(uidFolder.getUID(message)).thenReturn(messageUid);
        MailDetailResponse dto = newDto(messageUid);
        when(messageFetcher.fetchBatch(any(), eq(uidFolder), eq(FOLDER))).thenReturn(List.of(dto));
        when(messageMapper.toEntity(dto, account, FOLDER, syncState.getUidValidity())).thenReturn(new MessageEntity());
        return message;
    }

    private static MailDetailResponse newDto(long uid) {
        return new MailDetailResponse("stable-" + uid, uid, "Subject", "from@example.com", "to@example.com", null, null,
                LocalDateTime.of(2026, 1, 1, 10, 0), false, false, false, null, null, null, false, List.of(), null,
                null);
    }
}
