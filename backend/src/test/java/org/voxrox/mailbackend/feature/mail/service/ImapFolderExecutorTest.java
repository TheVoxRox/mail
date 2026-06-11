package org.voxrox.mailbackend.feature.mail.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

import jakarta.mail.Folder;
import jakarta.mail.MessagingException;
import jakarta.mail.Store;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.voxrox.mailbackend.exception.ErrorCode;
import org.voxrox.mailbackend.exception.MailOperationException;
import org.voxrox.mailbackend.exception.ResourceNotFoundException;
import org.voxrox.mailbackend.feature.mail.service.ImapConnectionManager.StoreAction;

@ExtendWith(MockitoExtension.class)
class ImapFolderExecutorTest {

    @Mock
    private ImapConnectionManager connectionManager;

    @Mock
    private Store store;

    @Mock
    private Folder folder;

    /**
     * Drives executeWithLock to actually run the StoreAction lambda against the
     * mock Store.
     */
    private void runActionAgainstStore() {
        when(connectionManager.executeWithLock(eq(7L), any())).thenAnswer(invocation -> {
            StoreAction<?> action = invocation.getArgument(1);
            return action.execute(store);
        });
    }

    @Test
    void messagingExceptionWithNullMessageIsTranslatedNotPropagatedAsNpe() throws Exception {
        // Regression: e.getMessage() can be null; the catch block must not NPE on
        // toLowerCase() and must surface a clean MailOperationException instead.
        runActionAgainstStore();
        when(store.getFolder("INBOX")).thenReturn(folder);
        when(folder.exists()).thenReturn(true);
        doThrow(new MessagingException()).when(folder).open(anyInt());

        ImapFolderExecutor executor = new ImapFolderExecutor(connectionManager);

        MailOperationException ex = assertThrows(MailOperationException.class,
                () -> executor.executeReadOnly(7L, "INBOX", (f, uid) -> null));
        assertEquals(ErrorCode.MAIL_CONNECTION_ERROR, ex.getCode());
    }

    @Test
    void notFoundMessageMapsToResourceNotFound() throws Exception {
        runActionAgainstStore();
        when(store.getFolder("INBOX")).thenReturn(folder);
        when(folder.exists()).thenReturn(true);
        doThrow(new MessagingException("Mailbox: no such folder")).when(folder).open(anyInt());

        ImapFolderExecutor executor = new ImapFolderExecutor(connectionManager);

        assertThrows(ResourceNotFoundException.class, () -> executor.executeReadOnly(7L, "INBOX", (f, uid) -> null));
    }
}
