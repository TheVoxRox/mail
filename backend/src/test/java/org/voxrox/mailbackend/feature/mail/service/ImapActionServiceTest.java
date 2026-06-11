package org.voxrox.mailbackend.feature.mail.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.voxrox.mailbackend.core.metrics.MailMetrics;
import org.voxrox.mailbackend.feature.mail.dto.MessageFlag;

@ExtendWith(MockitoExtension.class)
class ImapActionServiceTest {

    @Mock
    private ImapFolderExecutor folderExecutor;

    @Mock
    private ImapConnectionManager connectionManager;

    @Mock
    private MailMetrics metrics;

    @Test
    void moveOnServerAsyncShouldRecordFailureWhenExecutorThrowsBeforeAction() {
        ImapActionService service = new ImapActionService(folderExecutor, connectionManager, metrics);
        doThrow(new RuntimeException("connection down")).when(folderExecutor).executeReadWrite(eq(7L), eq("INBOX"),
                any());

        service.moveOnServerAsync(7L, "INBOX", "Archive", 42L);

        verify(metrics).recordMove(MailMetrics.OUTCOME_FAILURE);
    }

    @Test
    void updateFlagsOnServerAsyncShouldSwallowExecutorFailure() {
        ImapActionService service = new ImapActionService(folderExecutor, connectionManager, metrics);
        doThrow(new RuntimeException("connection down")).when(folderExecutor).executeReadWrite(eq(7L), eq("INBOX"),
                any());

        service.updateFlagsOnServerAsync(7L, "INBOX", 42L, MessageFlag.SEEN, true);

        verify(folderExecutor).executeReadWrite(eq(7L), eq("INBOX"), any());
    }

    @Test
    void moveOnServerAsyncShouldUseReadWriteSourceFolder() {
        ImapActionService service = new ImapActionService(folderExecutor, connectionManager, metrics);

        service.moveOnServerAsync(7L, "INBOX", "Archive", 42L);

        verify(folderExecutor).executeReadWrite(eq(7L), eq("INBOX"), any());
    }
}
