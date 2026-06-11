package org.voxrox.mailbackend.feature.mail.listener;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.voxrox.mailbackend.feature.mail.dto.SyncNotification;
import org.voxrox.mailbackend.feature.mail.event.MailSyncCompletedEvent;
import org.voxrox.mailbackend.feature.mail.service.SseNotificationService;

@ExtendWith(MockitoExtension.class)
@DisplayName("MailSyncEventListener")
class MailSyncEventListenerTest {

    @Mock
    private SseNotificationService sseNotificationService;

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
    @DisplayName("event with 0 new messages -> broadcast not called")
    void noBroadcastWhenNoNewMessages() {
        var event = new MailSyncCompletedEvent(1L, "INBOX", 0, Instant.now());

        listener.handleSyncCompleted(event);

        verify(sseNotificationService, never()).broadcast(any());
    }

    @Test
    @DisplayName("broadcast exception does not propagate — isolation")
    void broadcastExceptionIsolated() {
        var event = new MailSyncCompletedEvent(1L, "INBOX", 5, Instant.now());
        doThrow(new RuntimeException("SSE broken")).when(sseNotificationService).broadcast(any());

        // Must not throw
        listener.handleSyncCompleted(event);
    }
}
