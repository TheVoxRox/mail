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
import org.voxrox.mailbackend.feature.contact.service.CorrespondentService;
import org.voxrox.mailbackend.feature.mail.dto.MailDetailResponse;
import org.voxrox.mailbackend.feature.mail.entity.FolderSyncStateEntity;
import org.voxrox.mailbackend.feature.mail.entity.MessageEntity;
import org.voxrox.mailbackend.feature.mail.mapper.MessageMapper;
import org.voxrox.mailbackend.feature.mail.mapper.MessageStableId;
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
    private CorrespondentService correspondentService;
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
                mailProps, messageMapper, threadingService, correspondentService);

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

    @Nested
    @DisplayName("concurrent sync idempotency")
    class ConcurrentSyncIdempotency {

        @Test
        @DisplayName("Skips uids a concurrent sync already persisted — inserts only the new ones")
        void skipsAlreadyPersistedUids() throws Exception {
            syncState.setLastKnownUid(950L);
            Message m1 = mock(Message.class);
            Message m2 = mock(Message.class);
            when(uidFolder.getUIDNext()).thenReturn(1002L);
            when(uidFolder.getMessagesByUID(951L, 1001L)).thenReturn(new Message[]{m1, m2});
            when(uidFolder.getUID(m2)).thenReturn(1001L);
            MailDetailResponse dto1 = newDto(1000L);
            MailDetailResponse dto2 = newDto(1001L);
            when(messageFetcher.fetchBatch(any(), eq(uidFolder), eq(FOLDER))).thenReturn(List.of(dto1, dto2));
            MessageEntity e1 = entityWithUid(1000L);
            MessageEntity e2 = entityWithUid(1001L);
            when(messageMapper.toEntity(dto1, account, FOLDER, syncState.getUidValidity())).thenReturn(e1);
            when(messageMapper.toEntity(dto2, account, FOLDER, syncState.getUidValidity())).thenReturn(e2);
            // uid 1000 was already inserted by a concurrent (e.g. send-triggered) sync.
            when(messageRepository.findExistingUids(ACCOUNT_ID, FOLDER, List.of(1000L, 1001L)))
                    .thenReturn(List.of(1000L));
            when(messageRepository.saveAll(List.of(e2))).thenReturn(List.of(e2));

            downloader.syncNewMessages(context());

            // Only the not-yet-persisted message is inserted — no (account, folder, uid)
            // unique-constraint failure, no aborted batch.
            verify(messageRepository).saveAll(List.of(e2));
        }
    }

    @Nested
    @DisplayName("duplicate Message-ID within one folder")
    class DuplicateMessageIdWithinFolder {

        /**
         * A trash folder collects deletions from every other folder, so deleting a
         * message from the inbox and its own copy from Sent leaves two IMAP messages
         * with different uids and one Message-ID — which the mapper derives one
         * stableId from. Both must persist.
         */
        @Test
        @DisplayName("Two copies of one Message-ID both persist — the higher uid moves onto the uid identity")
        void secondCopyFallsBackToUidIdentity() throws Exception {
            String shared = "shared-stable-id";
            MessageEntity older = entityWith(1000L, shared);
            MessageEntity newer = entityWith(1001L, shared);
            stubBatch(List.of(1000L, 1001L), List.of(older, newer));
            when(messageRepository.saveAll(List.of(older, newer))).thenReturn(List.of(older, newer));

            downloader.downloadSequenceRange(context(), 1, 2);

            // Neither is dropped, and the ids no longer collide.
            verify(messageRepository).saveAll(List.of(older, newer));
            assertThat(older.getStableId()).isEqualTo(shared);
            assertThat(newer.getStableId()).isEqualTo(MessageStableId.computeFromUid(ACCOUNT_ID, FOLDER, 1001L, 1L));
        }

        @Test
        @DisplayName("The lowest uid keeps the Message-ID identity regardless of the order the batch arrives in")
        void lowestUidWinsRegardlessOfBatchOrder() throws Exception {
            // The fetch loop walks windows downwards, so the higher uid can come first.
            String shared = "shared-stable-id";
            MessageEntity newer = entityWith(1001L, shared);
            MessageEntity older = entityWith(1000L, shared);
            stubBatch(List.of(1001L, 1000L), List.of(newer, older));
            when(messageRepository.saveAll(List.of(newer, older))).thenReturn(List.of(newer, older));

            downloader.downloadSequenceRange(context(), 1, 2);

            // Same assignment as the ascending batch above — otherwise a backfill would
            // hand the same two messages the opposite ids.
            assertThat(older.getStableId()).isEqualTo(shared);
            assertThat(newer.getStableId()).isEqualTo(MessageStableId.computeFromUid(ACCOUNT_ID, FOLDER, 1001L, 1L));
        }

        @Test
        @DisplayName("Collides with a committed row: the incoming copy moves onto the uid identity")
        void collisionAgainstAlreadyPersistedRow() throws Exception {
            String taken = "already-persisted-stable-id";
            MessageEntity incoming = entityWith(1001L, taken);
            stubBatch(List.of(1001L), List.of(incoming));
            // An earlier sync already persisted the other copy under this id.
            when(messageRepository.findExistingStableIds(List.of(taken))).thenReturn(List.of(taken));
            when(messageRepository.saveAll(List.of(incoming))).thenReturn(List.of(incoming));

            downloader.downloadSequenceRange(context(), 1, 1);

            verify(messageRepository).saveAll(List.of(incoming));
            assertThat(incoming.getStableId()).isEqualTo(MessageStableId.computeFromUid(ACCOUNT_ID, FOLDER, 1001L, 1L));
        }

        @Test
        @DisplayName("Both identities already taken: that copy is dropped and the rest of the batch still inserts")
        void unplaceableCopyIsDroppedWithoutLosingTheBatch() throws Exception {
            // Nothing a correct server produces — dropAlreadyPersisted removes a
            // committed uid before this runs, so a row can hold the uid identity only in
            // an inconsistent state. The guard exists so one such message cannot cost the
            // folder its whole sync the way the stable_id collision did.
            String shared = "shared-stable-id";
            String byUid = MessageStableId.computeFromUid(ACCOUNT_ID, FOLDER, 1001L, 1L);
            MessageEntity unplaceable = entityWith(1001L, shared);
            MessageEntity other = entityWith(1002L, "other-stable-id");
            stubBatch(List.of(1001L, 1002L), List.of(unplaceable, other));
            when(messageRepository.findExistingStableIds(List.of(shared, "other-stable-id")))
                    .thenReturn(List.of(shared, byUid));
            when(messageRepository.saveAll(List.of(other))).thenReturn(List.of(other));

            downloader.downloadSequenceRange(context(), 1, 2);

            verify(messageRepository).saveAll(List.of(other));
        }

        @Test
        @DisplayName("A null uid orders instead of throwing — the ordering must not become a way to lose the batch")
        void nullUidDoesNotAbortTheBatch() throws Exception {
            // getUid() is a boxed Long. The fetch path reads it as a primitive so this
            // is not reachable today, but unboxing null while ordering would throw
            // inside the batch transaction and roll the folder back — the very failure
            // this method prevents. Pinned so the comparator cannot go back to
            // comparingLong unnoticed.
            MessageEntity noUid = new MessageEntity();
            noUid.setStableId("no-uid-stable-id");
            MessageEntity normal = entityWith(1002L, "other-stable-id");
            stubBatch(List.of(1001L, 1002L), List.of(noUid, normal));
            when(messageRepository.saveAll(List.of(noUid, normal))).thenReturn(List.of(noUid, normal));

            downloader.downloadSequenceRange(context(), 1, 2);

            verify(messageRepository).saveAll(List.of(noUid, normal));
        }

        @Test
        @DisplayName("A null uid among two dropped copies orders too — the warn log is the method's other ordering")
        void droppedNullUidDoesNotAbortTheBatch() throws Exception {
            // The sibling of the case above, for the second place this method orders
            // uids: the warn line that names what it dropped. It kept a bare sorted()
            // when the batch ordering was given nullsFirst, and one dropped message
            // cannot expose that — sorting a single element never calls the comparator.
            // So it takes two, which is what this sets up.
            MessageEntity noUid = new MessageEntity();
            noUid.setStableId("dup-a");
            noUid.setUidValidity(1L);
            MessageEntity alsoUnplaceable = entityWith(1003L, "dup-b");
            MessageEntity survivor = entityWith(1002L, "other-stable-id");
            stubBatch(List.of(1001L, 1002L, 1003L), List.of(noUid, survivor, alsoUnplaceable));
            // Both are unplaceable: their Message-ID identity and their uid identity are
            // each already held by a committed row.
            when(messageRepository.findExistingStableIds(List.of("dup-a", "other-stable-id", "dup-b")))
                    .thenReturn(List.of("dup-a", "dup-b", MessageStableId.computeFromUid(ACCOUNT_ID, FOLDER, null, 1L),
                            MessageStableId.computeFromUid(ACCOUNT_ID, FOLDER, 1003L, 1L)));
            when(messageRepository.saveAll(List.of(survivor))).thenReturn(List.of(survivor));

            downloader.downloadSequenceRange(context(), 1, 3);

            // The two that cannot be placed are dropped, and the rest of the batch still
            // inserts — the log line must not be what costs the folder its sync.
            verify(messageRepository).saveAll(List.of(survivor));
        }

        /**
         * Drives one batch through the fetch/map seam: {@code uids} are the server's
         * uids in batch order, {@code entities} the rows the mapper returns for them.
         */
        private void stubBatch(List<Long> uids, List<MessageEntity> entities) throws Exception {
            List<Message> messages = uids.stream().map(uid -> mock(Message.class)).toList();
            when(folder.getMessages(1, uids.size())).thenReturn(messages.toArray(new Message[0]));
            when(uidFolder.getUID(messages.getLast())).thenReturn(uids.getLast());
            List<MailDetailResponse> dtos = uids.stream().map(MessageDownloaderTest::newDto).toList();
            when(messageFetcher.fetchBatch(any(), eq(uidFolder), eq(FOLDER))).thenReturn(dtos);
            for (int i = 0; i < uids.size(); i++) {
                when(messageMapper.toEntity(dtos.get(i), account, FOLDER, syncState.getUidValidity()))
                        .thenReturn(entities.get(i));
            }
        }
    }

    @Nested
    @DisplayName("reconcileServerOnlyUids (server-only holes)")
    class ReconcileServerOnlyUids {

        @Test
        @DisplayName("Empty hole list is a no-op — no server round-trip")
        void emptyHolesIsNoop() throws Exception {
            int reconciled = downloader.reconcileServerOnlyUids(context(), List.of());

            assertThat(reconciled).isZero();
            verify(uidFolder, never()).getMessagesByUID(any(long[].class));
        }

        @Test
        @DisplayName("Fetches exactly the hole UIDs, persists them, and never regresses lastKnownUid")
        void reconcilesInteriorHole() throws Exception {
            // An interior hole sits below the forward cursor by construction, so the
            // reconcile must fill it without moving lastKnownUid.
            syncState.setLastKnownUid(20L);
            Message m = mock(Message.class);
            when(uidFolder.getMessagesByUID(new long[]{11L})).thenReturn(new Message[]{m});
            when(uidFolder.getUID(m)).thenReturn(11L);
            MailDetailResponse dto = newDto(11L);
            when(messageFetcher.fetchBatch(any(), eq(uidFolder), eq(FOLDER))).thenReturn(List.of(dto));
            MessageEntity entity = entityWithUid(11L);
            when(messageMapper.toEntity(dto, account, FOLDER, syncState.getUidValidity())).thenReturn(entity);
            when(messageRepository.saveAll(List.of(entity))).thenReturn(List.of(entity));

            int reconciled = downloader.reconcileServerOnlyUids(context(), List.of(11L));

            assertThat(reconciled).isEqualTo(1);
            verify(uidFolder).getMessagesByUID(new long[]{11L});
            verify(messageRepository).saveAll(List.of(entity));
            // Filling an interior hole must not advance the forward cursor.
            verify(syncStateService, never()).updateLastKnownUid(any(), any());
            assertThat(syncState.getLastKnownUid()).isEqualTo(20L);
        }

        @Test
        @DisplayName("Null entries (UID expunged between enumeration and fetch) are dropped")
        void dropsNullEntriesFromServer() throws Exception {
            syncState.setLastKnownUid(20L);
            // The server no longer has UID 11 (expunged in the meantime) -> null slot.
            when(uidFolder.getMessagesByUID(new long[]{11L})).thenReturn(new Message[]{null});

            int reconciled = downloader.reconcileServerOnlyUids(context(), List.of(11L));

            assertThat(reconciled).isZero();
            verify(messageFetcher, never()).fetchBatch(any(), any(), any());
        }
    }

    private FolderSyncContext context() {
        return new FolderSyncContext(account, FOLDER, folder, uidFolder, syncState);
    }

    private static MessageEntity entityWithUid(long uid) {
        MessageEntity entity = new MessageEntity();
        entity.setUid(uid);
        return entity;
    }

    /** As {@link #entityWithUid}, plus the derived id the mapper would have set. */
    private static MessageEntity entityWith(long uid, String stableId) {
        MessageEntity entity = entityWithUid(uid);
        entity.setStableId(stableId);
        entity.setUidValidity(1L);
        return entity;
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
        return new MailDetailResponse("stable-" + uid, uid, FOLDER, "Subject", "from@example.com", "to@example.com",
                null, null, null, LocalDateTime.of(2026, 1, 1, 10, 0), false, false, false, null, null, null, false,
                List.of(), null, null);
    }
}
