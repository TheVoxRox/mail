package org.voxrox.mailbackend.feature.mail.listener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.voxrox.mailbackend.feature.mail.dto.SyncCycleNotification;
import org.voxrox.mailbackend.feature.mail.dto.SyncNotification;
import org.voxrox.mailbackend.feature.mail.dto.SyncStatusNotification;
import org.voxrox.mailbackend.feature.mail.event.MailSyncCompletedEvent;
import org.voxrox.mailbackend.feature.mail.event.MailSyncCycleCompletedEvent;
import org.voxrox.mailbackend.feature.mail.event.MailSyncErrorStateChangedEvent;
import org.voxrox.mailbackend.feature.mail.service.FolderListCache;
import org.voxrox.mailbackend.feature.mail.service.SseNotificationService;

@ExtendWith(MockitoExtension.class)
@DisplayName("MailSyncEventListener")
class MailSyncEventListenerTest {

    @Mock
    private SseNotificationService sseNotificationService;

    @Mock
    private FolderListCache folderListCache;

    @InjectMocks
    private MailSyncEventListener listener;

    @Test
    @DisplayName("event with new messages -> broadcast is called")
    void broadcastOnNewMessages() {
        var event = new MailSyncCompletedEvent(1L, "INBOX", 3, Instant.now());

        listener.handleSyncCompleted(event);

        verify(sseNotificationService).broadcast(any(SyncNotification.class));
    }

    @Test
    @DisplayName("event with 0 new messages -> broadcast not called, folder cache still invalidated")
    void noBroadcastWhenNoNewMessages() {
        var event = new MailSyncCompletedEvent(1L, "INBOX", 0, Instant.now());

        listener.handleSyncCompleted(event);

        verify(sseNotificationService, never()).broadcast(any());
        // Flag/deletion sync changes unread counts even with 0 new messages.
        verify(folderListCache).invalidate(1L);
    }

    @Test
    @DisplayName("folder cache is invalidated before the broadcast — the client refresh must not hit a stale entry")
    void invalidatesCacheBeforeBroadcast() {
        var event = new MailSyncCompletedEvent(1L, "INBOX", 3, Instant.now());

        listener.handleSyncCompleted(event);

        var inOrder = org.mockito.Mockito.inOrder(folderListCache, sseNotificationService);
        inOrder.verify(folderListCache).invalidate(1L);
        inOrder.verify(sseNotificationService).broadcast(any(SyncNotification.class));
    }

    @Test
    @DisplayName("broadcast exception does not propagate — isolation")
    void broadcastExceptionIsolated() {
        var event = new MailSyncCompletedEvent(1L, "INBOX", 5, Instant.now());
        doThrow(new RuntimeException("SSE broken")).when(sseNotificationService).broadcast(any());

        // Must not throw
        listener.handleSyncCompleted(event);
    }

    @Test
    @DisplayName("finished pass -> sync_cycle_completed, and it carries a zero count rather than staying silent")
    void broadcastsCycleCompletionWithZeroCount() {
        var event = new MailSyncCycleCompletedEvent(1L, 0, Instant.now());

        listener.handleSyncCycleCompleted(event);

        var notification = ArgumentCaptor.forClass(SyncCycleNotification.class);
        verify(sseNotificationService).broadcast(notification.capture());
        assertThat(notification.getValue().type()).isEqualTo("sync_cycle_completed");
        assertThat(notification.getValue().accountId()).isEqualTo(1L);
        assertThat(notification.getValue().newMessagesCount()).isZero();
    }

    @Test
    @DisplayName("finished pass invalidates the folder cache before broadcasting — a flag-only pass moved unread counts")
    void cycleCompletionInvalidatesCacheBeforeBroadcast() {
        var event = new MailSyncCycleCompletedEvent(1L, 0, Instant.now());

        listener.handleSyncCycleCompleted(event);

        var inOrder = org.mockito.Mockito.inOrder(folderListCache, sseNotificationService);
        inOrder.verify(folderListCache).invalidate(1L);
        inOrder.verify(sseNotificationService).broadcast(any(SyncCycleNotification.class));
    }

    @Test
    @DisplayName("broadcast exception on a finished pass does not propagate either")
    void cycleCompletionBroadcastExceptionIsolated() {
        var event = new MailSyncCycleCompletedEvent(1L, 2, Instant.now());
        doThrow(new RuntimeException("SSE broken")).when(sseNotificationService).broadcast(any());

        listener.handleSyncCycleCompleted(event);
    }

    @Test
    @DisplayName("error state with a code -> sync_failed carrying that code")
    void broadcastsSyncFailed() {
        var event = new MailSyncErrorStateChangedEvent(1L, "MAIL_SYNC_FOLDER_FAILED", Instant.now());

        listener.handleErrorStateChanged(event);

        var notification = ArgumentCaptor.forClass(SyncStatusNotification.class);
        verify(sseNotificationService).broadcast(notification.capture());
        assertThat(notification.getValue().type()).isEqualTo("sync_failed");
        assertThat(notification.getValue().errorCode()).isEqualTo("MAIL_SYNC_FOLDER_FAILED");
        assertThat(notification.getValue().accountId()).isEqualTo(1L);
    }

    @Test
    @DisplayName("cleared error state -> sync_recovered with no code")
    void broadcastsSyncRecovered() {
        var event = new MailSyncErrorStateChangedEvent(1L, null, Instant.now());

        listener.handleErrorStateChanged(event);

        var notification = ArgumentCaptor.forClass(SyncStatusNotification.class);
        verify(sseNotificationService).broadcast(notification.capture());
        assertThat(notification.getValue().type()).isEqualTo("sync_recovered");
        assertThat(notification.getValue().errorCode()).isNull();
    }

    @Test
    @DisplayName("broadcast exception on an error-state event does not propagate either")
    void errorStateBroadcastExceptionIsolated() {
        var event = new MailSyncErrorStateChangedEvent(1L, "MAIL_SYNC_ACCOUNT_FAILED", Instant.now());
        doThrow(new RuntimeException("SSE broken")).when(sseNotificationService).broadcast(any());

        listener.handleErrorStateChanged(event);
    }
}
