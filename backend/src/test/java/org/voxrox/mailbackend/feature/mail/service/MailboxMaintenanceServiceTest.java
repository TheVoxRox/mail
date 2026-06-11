package org.voxrox.mailbackend.feature.mail.service;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.voxrox.mailbackend.core.config.MailClientProperties;
import org.voxrox.mailbackend.core.config.mail.SyncProperties;
import org.voxrox.mailbackend.feature.mail.repository.MessageRepository;

@ExtendWith(MockitoExtension.class)
class MailboxMaintenanceServiceTest {

    @Mock
    private MessageRepository messageRepository;

    @Mock
    private MailClientProperties mailProps;

    @Test
    void enforceLocalWindowLimitAsyncShouldDeleteOlderMessagesWhenLimitReached() {
        MailboxMaintenanceService service = new MailboxMaintenanceService(messageRepository, mailProps);
        when(mailProps.sync()).thenReturn(syncProperties(3));
        when(messageRepository.findLatestUids(7L, "INBOX", 3)).thenReturn(List.of(30L, 20L, 10L));
        when(messageRepository.deleteOlderThan(7L, "INBOX", 10L)).thenReturn(5);

        service.enforceLocalWindowLimitAsync(7L, "INBOX");

        verify(messageRepository).deleteOlderThan(7L, "INBOX", 10L);
    }

    @Test
    void enforceLocalWindowLimitAsyncShouldSwallowRepositoryFailure() {
        MailboxMaintenanceService service = new MailboxMaintenanceService(messageRepository, mailProps);
        when(mailProps.sync()).thenReturn(syncProperties(3));
        when(messageRepository.findLatestUids(7L, "INBOX", 3)).thenThrow(new RuntimeException("db busy"));

        service.enforceLocalWindowLimitAsync(7L, "INBOX");

        verify(messageRepository, never()).deleteOlderThan(eq(7L), eq("INBOX"), anyInt());
    }

    private static SyncProperties syncProperties(int localWindowLimit) {
        return new SyncProperties(100, 200, Duration.ofMinutes(5), Duration.ofSeconds(10), 50, 30, localWindowLimit, 4,
                256, 200, Duration.ofMinutes(30), Duration.ofSeconds(30));
    }
}
