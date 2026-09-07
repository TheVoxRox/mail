package org.voxrox.mailbackend.feature.mail.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import java.util.List;

import jakarta.mail.Folder;
import jakarta.mail.MessagingException;
import jakarta.mail.Store;
import jakarta.mail.UIDFolder;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.voxrox.mailbackend.exception.ErrorCode;
import org.voxrox.mailbackend.exception.MailOperationException;
import org.voxrox.mailbackend.exception.ResourceNotFoundException;
import org.voxrox.mailbackend.feature.mail.service.ImapConnectionManager.Lane;
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
        when(connectionManager.executeWithLock(eq(7L), any(), any())).thenAnswer(invocation -> {
            StoreAction<?> action = invocation.getArgument(2);
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
                () -> executor.executeReadOnly(7L, Lane.BACKGROUND, "INBOX", (f, uid) -> null));
        assertEquals(ErrorCode.MAIL_CONNECTION_ERROR, ex.getCode());
    }

    @Test
    void notFoundMessageMapsToResourceNotFound() throws Exception {
        runActionAgainstStore();
        when(store.getFolder("INBOX")).thenReturn(folder);
        when(folder.exists()).thenReturn(true);
        doThrow(new MessagingException("Mailbox: no such folder")).when(folder).open(anyInt());

        ImapFolderExecutor executor = new ImapFolderExecutor(connectionManager);

        assertThrows(ResourceNotFoundException.class,
                () -> executor.executeReadOnly(7L, Lane.BACKGROUND, "INBOX", (f, uid) -> null));
    }

    @Test
    void transientImapExceptionRaisedByActionIsPassedThroughUnchanged() throws Exception {
        // The bounded transient-retry loop in MailSyncService.performFullSyncCycle
        // relies on a TransientImapException raised inside the action reaching it
        // unchanged — exactly like the AuthenticationFailedException pass-through —
        // rather than being flattened into a generic MailOperationException by the
        // catch-all below. If the executor swallowed it, the retry would never fire
        // and a transient blip would surface as a hard last_error (bug D, #78).
        runActionAgainstStore();
        // The real IMAPFolder implements UIDFolder; a plain Folder mock would trip
        // the "does not support UID operations" guard before the action ever runs.
        Folder uidCapableFolder = mock(Folder.class, withSettings().extraInterfaces(UIDFolder.class));
        when(store.getFolder("INBOX")).thenReturn(uidCapableFolder);
        when(uidCapableFolder.exists()).thenReturn(true);

        ImapFolderExecutor executor = new ImapFolderExecutor(connectionManager);
        TransientImapException blip = new TransientImapException("INBOX",
                new MessagingException("failed to create new store connection"));

        TransientImapException thrown = assertThrows(TransientImapException.class,
                () -> executor.executeReadOnly(7L, Lane.BACKGROUND, "INBOX", (f, uid) -> {
                    throw blip;
                }));
        // Same instance — not re-wrapped, so the original cause survives to the retry
        // loop.
        assertSame(blip, thrown);
    }

    /**
     * A QRESYNC-capable store: {@link ImapCapabilities} only reads the capability
     * off an {@link org.eclipse.angus.mail.imap.IMAPStore}, so a plain Store mock
     * reports no QRESYNC and the resynced open degrades before it starts.
     */
    private org.eclipse.angus.mail.imap.IMAPStore qresyncStore() throws MessagingException {
        org.eclipse.angus.mail.imap.IMAPStore imapStore = mock(org.eclipse.angus.mail.imap.IMAPStore.class);
        when(imapStore.hasCapability("CONDSTORE")).thenReturn(true);
        when(imapStore.hasCapability("QRESYNC")).thenReturn(true);
        when(connectionManager.executeWithLock(eq(7L), any(), any())).thenAnswer(invocation -> {
            StoreAction<?> action = invocation.getArgument(2);
            return action.execute(imapStore);
        });
        return imapStore;
    }

    @Test
    void resyncedOpenHandsTheSelectsEventsToTheAction() throws Exception {
        org.eclipse.angus.mail.imap.IMAPStore imapStore = qresyncStore();
        org.eclipse.angus.mail.imap.IMAPFolder imapFolder = mock(org.eclipse.angus.mail.imap.IMAPFolder.class);
        when(imapStore.getFolder("INBOX")).thenReturn(imapFolder);
        when(imapFolder.exists()).thenReturn(true);
        List<jakarta.mail.event.MailEvent> events = List.of();
        when(imapFolder.open(eq(Folder.READ_ONLY), any(org.eclipse.angus.mail.imap.ResyncData.class)))
                .thenReturn(events);

        ImapFolderExecutor executor = new ImapFolderExecutor(connectionManager);

        Object seen = executor.executeReadOnlyResynced(7L, Lane.BACKGROUND, "INBOX",
                new ImapFolderExecutor.ResyncRequest(1L, 2L, 3L, 4L), (f, uid, resyncEvents) -> resyncEvents);
        assertSame(events, seen);
    }

    /**
     * Advertising QRESYNC and honouring it are two different things, and a folder
     * that can only be opened one way must still be syncable: without the
     * degradation the cycle would end in last_error, retry, and fail identically
     * forever. The action must be able to tell which happened — hence the null
     * event list rather than an empty one.
     */
    @Test
    void rejectedQresyncFallsBackToAPlainOpen() throws Exception {
        org.eclipse.angus.mail.imap.IMAPStore imapStore = qresyncStore();
        org.eclipse.angus.mail.imap.IMAPFolder imapFolder = mock(org.eclipse.angus.mail.imap.IMAPFolder.class);
        when(imapStore.getFolder("INBOX")).thenReturn(imapFolder);
        when(imapFolder.exists()).thenReturn(true);
        when(imapFolder.open(eq(Folder.READ_ONLY), any(org.eclipse.angus.mail.imap.ResyncData.class)))
                .thenThrow(new MessagingException("QRESYNC not enabled"));

        ImapFolderExecutor executor = new ImapFolderExecutor(connectionManager);

        Object seen = executor.executeReadOnlyResynced(7L, Lane.BACKGROUND, "INBOX",
                new ImapFolderExecutor.ResyncRequest(1L, 2L, 3L, 4L), (f, uid, resyncEvents) -> resyncEvents);

        assertNull(seen);
        verify(imapFolder).open(Folder.READ_ONLY);
    }

    /**
     * The other half of that contract: no request means no QRESYNC attempt at all,
     * so a caller that cannot resynchronize is never told "resynchronized, nothing
     * changed".
     */
    @Test
    void withoutARequestTheFolderIsOpenedPlainly() throws Exception {
        runActionAgainstStore();
        Folder uidCapableFolder = mock(Folder.class, withSettings().extraInterfaces(UIDFolder.class));
        when(store.getFolder("INBOX")).thenReturn(uidCapableFolder);
        when(uidCapableFolder.exists()).thenReturn(true);

        ImapFolderExecutor executor = new ImapFolderExecutor(connectionManager);

        Object seen = executor.executeReadOnlyResynced(7L, Lane.BACKGROUND, "INBOX", null,
                (f, uid, resyncEvents) -> resyncEvents);

        assertNull(seen);
        verify(uidCapableFolder).open(Folder.READ_ONLY);
    }
}
