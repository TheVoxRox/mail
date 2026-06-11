package org.voxrox.mailbackend.feature.mail.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;

import jakarta.mail.FetchProfile;
import jakarta.mail.Flags;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.UIDFolder;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import org.voxrox.mailbackend.core.config.MailClientProperties;
import org.voxrox.mailbackend.core.config.mail.SyncProperties;
import org.voxrox.mailbackend.feature.account.entity.AccountEntity;
import org.voxrox.mailbackend.feature.mail.entity.FolderSyncStateEntity;
import org.voxrox.mailbackend.feature.mail.repository.MessageRepository;

/**
 * Unit tests for {@link FlagSyncService}.
 *
 * Mocks: - {@link Folder}, {@link UIDFolder}, {@link Message} (jakarta.mail
 * interfaces — Mockito can handle them) - {@link MessageRepository},
 * {@link SyncStateService}, {@link MailboxMaintenanceService} -
 * {@link TransactionTemplate} via an anonymous subclass that calls the lambda
 * inline.
 *
 * Covers: - handleUidValidity: no local state (first run) -> stores the server
 * one - handleUidValidity: match -> passes - handleUidValidity: mismatch ->
 * CRITICAL: clearLocalCache + reset lastKnownUid + store the new validity,
 * returns false - syncMessageFlagsBatched: empty local set -> no-op -
 * syncMessageFlagsBatched: batching by batch-size - processFlagUpdates: flag
 * change -> updateFlagsIfChanged is called - processFlagUpdates: missing server
 * msg for uid -> skip (must not throw) - processFlagUpdates: MessagingException
 * while reading flags -> log, continue - cleanupDeletedInWindow: empty local
 * set -> no-op - cleanupDeletedInWindow: deleted UIDs -> delete -
 * cleanupDeletedInWindow: everything still exists -> no delete.
 */
@ExtendWith(MockitoExtension.class)
class FlagSyncServiceTest {

    private static final Long ACCOUNT_ID = 42L;
    private static final String FOLDER = "INBOX";

    @Mock
    private MessageRepository messageRepository;

    @Mock
    private SyncStateService syncStateService;

    @Mock
    private MailboxMaintenanceService maintenanceService;

    @Mock
    private Folder folder;

    @Mock
    private UIDFolder uidFolder;

    private FlagSyncService service;
    private FolderSyncStateEntity syncState;
    private FolderSyncContext ctx;

    /**
     * Inline TransactionTemplate — the lambda is invoked synchronously without a
     * real transaction.
     */
    private static TransactionTemplate inlineTransactionTemplate() {
        return new TransactionTemplate() {
            @Override
            public void executeWithoutResult(
                    java.util.function.Consumer<org.springframework.transaction.TransactionStatus> action) {
                action.accept(null);
            }

            @Override
            public <T> T execute(TransactionCallback<T> action) {
                return action.doInTransaction(null);
            }
        };
    }

    private MailClientProperties propsWithBatchSize(int batchSize) {
        SyncProperties sync = new SyncProperties(100, batchSize, java.time.Duration.ofMinutes(5),
                java.time.Duration.ofSeconds(10), 50, 30, 300, 4, 256, 200, java.time.Duration.ofMinutes(30),
                java.time.Duration.ofSeconds(30));
        // FlagSyncService reads only mailProps.sync().batchSize() — imap/smtp are
        // not needed, we pass null. Validation does not run in a unit test.
        return new MailClientProperties(null, null, sync, null);
    }

    private static final Long SYNC_STATE_ID = 555L;

    @BeforeEach
    void setUp() {
        service = new FlagSyncService(messageRepository, syncStateService, maintenanceService,
                inlineTransactionTemplate(), propsWithBatchSize(2));

        AccountEntity account = new AccountEntity();
        account.setId(ACCOUNT_ID);

        syncState = spy(new FolderSyncStateEntity());
        // The ID on the entity is read-only (no setter) — we stub the getter so
        // the dedicated UPDATE queries (updateUidValidity, resetForUidValidityChange)
        // see a concrete ID. lenient() — only the handleUidValidity branch reads
        // the getter, other nested groups do not invoke it.
        lenient().when(syncState.getId()).thenReturn(SYNC_STATE_ID);
        syncState.setLastKnownUid(100L);

        ctx = new FolderSyncContext(account, FOLDER, folder, uidFolder, syncState);
    }

    @Nested
    @DisplayName("handleUidValidity")
    class HandleUidValidity {

        @Test
        void firstSyncWithNoLocalValidityShouldStoreServerValidity() throws Exception {
            when(uidFolder.getUIDValidity()).thenReturn(123456L);
            syncState.setUidValidity(null);

            boolean ok = service.handleUidValidity(ctx);

            assertThat(ok).isTrue();
            assertThat(syncState.getUidValidity()).isEqualTo(123456L);
            assertThat(syncState.getLastKnownUid()).isEqualTo(100L); // unchanged
            verify(syncStateService).updateUidValidity(SYNC_STATE_ID, 123456L);
            verify(syncStateService, never()).saveSyncState(any());
            verify(maintenanceService, never()).clearLocalCache(anyLong(), anyString());
        }

        @Test
        void matchingValidityShouldPassWithoutDbWrite() throws Exception {
            // No-change branch — no UPDATE, no save. This avoids an unnecessary
            // version bump and an optimistic-lock conflict in later steps of the
            // cycle (saveMessagesBatchAtomic / lastSyncAt).
            when(uidFolder.getUIDValidity()).thenReturn(123456L);
            syncState.setUidValidity(123456L);

            boolean ok = service.handleUidValidity(ctx);

            assertThat(ok).isTrue();
            assertThat(syncState.getLastKnownUid()).isEqualTo(100L);
            verify(maintenanceService, never()).clearLocalCache(anyLong(), anyString());
            verifyNoInteractions(syncStateService);
        }

        @Test
        void changedValidityMustResetLocalCache() throws Exception {
            // CRITICAL: UIDValidity change = the server renumbered all UIDs. If we did
            // not clear the cache, the local DB would reference non-existing / foreign
            // messages.
            when(uidFolder.getUIDValidity()).thenReturn(999999L);
            syncState.setUidValidity(123456L);
            syncState.setLastKnownModseq(42L);

            boolean ok = service.handleUidValidity(ctx);

            assertThat(ok).isFalse(); // sync must be interrupted
            assertThat(syncState.getLastKnownUid()).isEqualTo(0L); // reset (in-memory mirror)
            assertThat(syncState.getUidValidity()).isEqualTo(999999L); // new validity (in-memory mirror)
            assertThat(syncState.getLastKnownModseq()).isNull(); // modseq is reset as well
            verify(maintenanceService).clearLocalCache(ACCOUNT_ID, FOLDER);
            verify(syncStateService).resetForUidValidityChange(SYNC_STATE_ID, 999999L);
            verify(syncStateService, never()).saveSyncState(any());
        }
    }

    @Nested
    @DisplayName("CONDSTORE flag sync — early return + fallback")
    class CondstoreEarlyReturnAndFallback {

        @Test
        @DisplayName("Non-IMAPFolder ctx -> fall back to full sweep (syncMessageFlagsBatched)")
        void nonImapFolderFallsBackToBatched() throws Exception {
            // The folder mock is not an IMAPFolder, so it should hit the fallback branch.
            // Empty localUids skips further fetch logic — enough to cover the fallback.
            when(messageRepository.findUidsByAccountAndFolder(ACCOUNT_ID, FOLDER)).thenReturn(List.of());

            service.syncMessageFlagsCondstore(ctx);

            verify(messageRepository).findUidsByAccountAndFolder(ACCOUNT_ID, FOLDER);
            verify(messageRepository, never()).updateFlagsIfChanged(anyLong(), anyString(), anyLong(),
                    org.mockito.ArgumentMatchers.anyBoolean(), org.mockito.ArgumentMatchers.anyBoolean(),
                    org.mockito.ArgumentMatchers.anyBoolean());
        }
    }

    @Nested
    @DisplayName("Cleanup deleted — UID enumeration fallback")
    class CleanupFallback {

        @Test
        @DisplayName("Non-IMAPFolder ctx -> fall back to cleanupDeletedInWindow (legacy)")
        void nonImapFolderFallsBackToLegacyCleanup() throws Exception {
            // Same fallback pattern as for flag sync.
            when(messageRepository.findUidsByAccountAndFolder(ACCOUNT_ID, FOLDER)).thenReturn(List.of());

            service.cleanupDeletedViaUidEnumeration(ctx);

            verify(messageRepository).findUidsByAccountAndFolder(ACCOUNT_ID, FOLDER);
        }
    }

    @Nested
    @DisplayName("syncMessageFlagsBatched")
    class SyncFlags {

        @Test
        void emptyLocalUidsShouldBeNoOp() throws Exception {
            when(messageRepository.findUidsByAccountAndFolder(ACCOUNT_ID, FOLDER)).thenReturn(List.of());

            service.syncMessageFlagsBatched(ctx);

            verify(folder, never()).fetch(any(), any());
            verify(uidFolder, never()).getMessagesByUID(anyLong(), anyLong());
        }

        @Test
        void shouldBatchByConfiguredSize() throws Exception {
            // batch-size = 2, 5 UIDs -> 3 batches (2, 2, 1)
            when(messageRepository.findUidsByAccountAndFolder(ACCOUNT_ID, FOLDER))
                    .thenReturn(List.of(10L, 11L, 12L, 13L, 14L));
            when(uidFolder.getMessagesByUID(anyLong(), anyLong())).thenReturn(new Message[0]);

            service.syncMessageFlagsBatched(ctx);

            // Folder.fetch called 3x (one per batch)
            verify(folder, times(3)).fetch(any(Message[].class), any(FetchProfile.class));
        }

        @Test
        void shouldUseFirstAndLastUidOfBatchAsRange() throws Exception {
            when(messageRepository.findUidsByAccountAndFolder(ACCOUNT_ID, FOLDER)).thenReturn(List.of(10L, 11L));
            when(uidFolder.getMessagesByUID(anyLong(), anyLong())).thenReturn(new Message[0]);

            service.syncMessageFlagsBatched(ctx);

            verify(uidFolder).getMessagesByUID(10L, 11L);
        }
    }

    @Nested
    @DisplayName("flag update semantics (via syncMessageFlagsBatched)")
    class ProcessFlags {

        @Test
        void shouldUpdateFlagsForMatchingUid() throws Exception {
            Message m = mockMessage(true, false, true);
            when(messageRepository.findUidsByAccountAndFolder(ACCOUNT_ID, FOLDER)).thenReturn(List.of(10L));
            when(uidFolder.getMessagesByUID(10L, 10L)).thenReturn(new Message[]{m});
            when(uidFolder.getUID(m)).thenReturn(10L);

            service.syncMessageFlagsBatched(ctx);

            verify(messageRepository).updateFlagsIfChanged(ACCOUNT_ID, FOLDER, 10L, true, false, true);
        }

        @Test
        void missingServerMessageForUidShouldBeSkipped() throws Exception {
            // The server does not return a message for UID 99 -> absent from serverMap
            // -> skip (must not throw NPE or call the repo).
            when(messageRepository.findUidsByAccountAndFolder(ACCOUNT_ID, FOLDER)).thenReturn(List.of(99L));
            when(uidFolder.getMessagesByUID(99L, 99L)).thenReturn(new Message[0]);

            service.syncMessageFlagsBatched(ctx);

            verify(messageRepository, never()).updateFlagsIfChanged(anyLong(), anyString(), anyLong(),
                    any(boolean.class), any(boolean.class), any(boolean.class));
        }

        @Test
        void messagingExceptionDuringFlagReadShouldBeSwallowed() throws Exception {
            Message m = org.mockito.Mockito.mock(Message.class);
            when(m.isSet(Flags.Flag.SEEN)).thenThrow(new MessagingException("flag read failed"));
            when(messageRepository.findUidsByAccountAndFolder(ACCOUNT_ID, FOLDER)).thenReturn(List.of(10L));
            when(uidFolder.getMessagesByUID(10L, 10L)).thenReturn(new Message[]{m});
            when(uidFolder.getUID(m)).thenReturn(10L);

            // Must not throw — log + continue with the next UID.
            service.syncMessageFlagsBatched(ctx);

            verify(messageRepository, never()).updateFlagsIfChanged(anyLong(), anyString(), anyLong(),
                    any(boolean.class), any(boolean.class), any(boolean.class));
        }

        @Test
        void shouldProcessMultipleUidsIndependently() throws Exception {
            Message m1 = mockMessage(true, true, false);
            Message m2 = mockMessage(false, false, false);
            when(messageRepository.findUidsByAccountAndFolder(ACCOUNT_ID, FOLDER)).thenReturn(List.of(10L, 11L));
            when(uidFolder.getMessagesByUID(10L, 11L)).thenReturn(new Message[]{m1, m2});
            when(uidFolder.getUID(m1)).thenReturn(10L);
            when(uidFolder.getUID(m2)).thenReturn(11L);

            service.syncMessageFlagsBatched(ctx);

            verify(messageRepository).updateFlagsIfChanged(ACCOUNT_ID, FOLDER, 10L, true, true, false);
            verify(messageRepository).updateFlagsIfChanged(ACCOUNT_ID, FOLDER, 11L, false, false, false);
        }
    }

    @Nested
    @DisplayName("cleanupDeletedInWindow")
    class Cleanup {

        @Test
        void emptyLocalUidsShouldBeNoOp() throws Exception {
            when(messageRepository.findUidsByAccountAndFolder(ACCOUNT_ID, FOLDER)).thenReturn(List.of());

            service.cleanupDeletedInWindow(ctx);

            verify(uidFolder, never()).getMessagesByUID(anyLong(), anyLong());
            verify(messageRepository, never()).deleteAllByAccountIdAndFolderNameAndUidIn(anyLong(), anyString(), any());
        }

        @Test
        void shouldDeleteUidsMissingOnServer() throws Exception {
            // Locally we have UIDs 10, 11, 12; the server returns only 10 and 12 -> delete
            // 11.
            when(messageRepository.findUidsByAccountAndFolder(ACCOUNT_ID, FOLDER)).thenReturn(List.of(10L, 11L, 12L));

            Message m10 = mockMessageOnly();
            Message m12 = mockMessageOnly();
            when(uidFolder.getMessagesByUID(10L, 12L)).thenReturn(new Message[]{m10, m12});
            when(uidFolder.getUID(m10)).thenReturn(10L);
            when(uidFolder.getUID(m12)).thenReturn(12L);

            service.cleanupDeletedInWindow(ctx);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<Long>> captor = ArgumentCaptor.forClass(List.class);
            verify(messageRepository).deleteAllByAccountIdAndFolderNameAndUidIn(eq(ACCOUNT_ID), eq(FOLDER),
                    captor.capture());
            assertThat(captor.getValue()).containsExactly(11L);
        }

        @Test
        void shouldNotCallDeleteWhenAllUidsStillExist() throws Exception {
            when(messageRepository.findUidsByAccountAndFolder(ACCOUNT_ID, FOLDER)).thenReturn(List.of(10L, 11L));

            Message m10 = mockMessageOnly();
            Message m11 = mockMessageOnly();
            when(uidFolder.getMessagesByUID(10L, 11L)).thenReturn(new Message[]{m10, m11});
            when(uidFolder.getUID(m10)).thenReturn(10L);
            when(uidFolder.getUID(m11)).thenReturn(11L);

            service.cleanupDeletedInWindow(ctx);

            verify(messageRepository, never()).deleteAllByAccountIdAndFolderNameAndUidIn(anyLong(), anyString(), any());
        }

        @Test
        void messagingExceptionDuringGetUidShouldSkipDeletionFailSafe() throws Exception {
            // Fail-safe: if MessagingException is thrown while reading the UID of a
            // server message, we cannot reliably determine what is missing on the
            // server. A missing UID in serverUids would erroneously mark the local
            // message as deleted and the cleanup would drop it even though it still
            // exists on the server. So we'd rather skip deleting in the whole window —
            // the next sync cycle will retry.
            when(messageRepository.findUidsByAccountAndFolder(ACCOUNT_ID, FOLDER)).thenReturn(List.of(10L, 11L));

            Message m10 = mockMessageOnly();
            Message m11broken = mockMessageOnly();
            when(uidFolder.getMessagesByUID(10L, 11L)).thenReturn(new Message[]{m10, m11broken});
            when(uidFolder.getUID(m10)).thenReturn(10L);
            when(uidFolder.getUID(m11broken)).thenThrow(new MessagingException("uid read failed"));

            service.cleanupDeletedInWindow(ctx);

            verify(messageRepository, never()).deleteAllByAccountIdAndFolderNameAndUidIn(anyLong(), anyString(), any());
        }
    }

    // ---- helpers ----

    private Message mockMessage(boolean seen, boolean flagged, boolean answered) throws MessagingException {
        Message m = org.mockito.Mockito.mock(Message.class);
        when(m.isSet(Flags.Flag.SEEN)).thenReturn(seen);
        when(m.isSet(Flags.Flag.FLAGGED)).thenReturn(flagged);
        when(m.isSet(Flags.Flag.ANSWERED)).thenReturn(answered);
        return m;
    }

    private Message mockMessageOnly() {
        return org.mockito.Mockito.mock(Message.class);
    }
}
