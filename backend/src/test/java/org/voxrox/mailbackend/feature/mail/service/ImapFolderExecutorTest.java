package org.voxrox.mailbackend.feature.mail.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import java.util.List;

import jakarta.mail.Folder;
import jakarta.mail.MessagingException;
import jakarta.mail.Store;
import jakarta.mail.UIDFolder;

import org.eclipse.angus.mail.imap.ResyncData;
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
        org.eclipse.angus.mail.imap.IMAPStore imapStore = imapStore();
        when(imapStore.hasCapability("CONDSTORE")).thenReturn(true);
        when(imapStore.hasCapability("QRESYNC")).thenReturn(true);
        return imapStore;
    }

    private org.eclipse.angus.mail.imap.IMAPStore imapStore() {
        org.eclipse.angus.mail.imap.IMAPStore imapStore = mock(org.eclipse.angus.mail.imap.IMAPStore.class);
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
        when(imapFolder.open(eq(Folder.READ_ONLY), any(ResyncData.class))).thenReturn(events);

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
        // No ENABLE advertised, so there is no CONDSTORE step in between.
        org.eclipse.angus.mail.imap.IMAPStore imapStore = qresyncStore();
        org.eclipse.angus.mail.imap.IMAPFolder imapFolder = mock(org.eclipse.angus.mail.imap.IMAPFolder.class);
        when(imapStore.getFolder("INBOX")).thenReturn(imapFolder);
        when(imapFolder.exists()).thenReturn(true);
        when(imapFolder.open(eq(Folder.READ_ONLY), any(ResyncData.class)))
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
    void withoutARequestTheFolderIsNotResynchronized() throws Exception {
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

    /**
     * The sync open's middle step, as Dovecot needs it: a plain SELECT there
     * reports no HIGHESTMODSEQ, so a folder opened without CONDSTORE never gets a
     * MODSEQ baseline and never resynchronizes. Still not a resynchronized open, so
     * the action is told null.
     */
    @Test
    void withoutARequestACondstoreServerIsOpenedWithCondstore() throws Exception {
        org.eclipse.angus.mail.imap.IMAPStore imapStore = qresyncStore();
        when(imapStore.hasCapability("ENABLE")).thenReturn(true);
        org.eclipse.angus.mail.imap.IMAPFolder imapFolder = mock(org.eclipse.angus.mail.imap.IMAPFolder.class);
        when(imapStore.getFolder("INBOX")).thenReturn(imapFolder);
        when(imapFolder.exists()).thenReturn(true);

        ImapFolderExecutor executor = new ImapFolderExecutor(connectionManager);

        Object seen = executor.executeReadOnlyResynced(7L, Lane.BACKGROUND, "INBOX", null,
                (f, uid, resyncEvents) -> resyncEvents);

        assertNull(seen);
        verify(imapFolder).open(Folder.READ_ONLY, ResyncData.CONDSTORE);
        verify(imapFolder, never()).open(anyInt());
    }

    /**
     * A rejected QRESYNC SELECT still leaves the folder worth a CONDSTORE one: the
     * cycle that follows needs HIGHESTMODSEQ to store a baseline the next QRESYNC
     * attempt can use.
     */
    @Test
    void rejectedQresyncFallsBackToACondstoreOpen() throws Exception {
        org.eclipse.angus.mail.imap.IMAPStore imapStore = qresyncStore();
        when(imapStore.hasCapability("ENABLE")).thenReturn(true);
        org.eclipse.angus.mail.imap.IMAPFolder imapFolder = mock(org.eclipse.angus.mail.imap.IMAPFolder.class);
        when(imapStore.getFolder("INBOX")).thenReturn(imapFolder);
        when(imapFolder.exists()).thenReturn(true);
        when(imapFolder.open(eq(Folder.READ_ONLY), any(ResyncData.class))).thenAnswer(invocation -> {
            if (!ResyncData.CONDSTORE.equals(invocation.getArgument(1))) {
                throw new MessagingException("Invalid QRESYNC parameters");
            }
            return null;
        });

        ImapFolderExecutor executor = new ImapFolderExecutor(connectionManager);

        Object seen = executor.executeReadOnlyResynced(7L, Lane.BACKGROUND, "INBOX",
                new ImapFolderExecutor.ResyncRequest(1L, 2L, 3L, 4L), (f, uid, resyncEvents) -> resyncEvents);

        assertNull(seen);
        verify(imapFolder).open(Folder.READ_ONLY, ResyncData.CONDSTORE);
        verify(imapFolder, never()).open(anyInt());
    }

    @Test
    void rejectedCondstoreFallsBackToAPlainOpen() throws Exception {
        org.eclipse.angus.mail.imap.IMAPStore imapStore = qresyncStore();
        when(imapStore.hasCapability("ENABLE")).thenReturn(true);
        org.eclipse.angus.mail.imap.IMAPFolder imapFolder = mock(org.eclipse.angus.mail.imap.IMAPFolder.class);
        when(imapStore.getFolder("INBOX")).thenReturn(imapFolder);
        when(imapFolder.exists()).thenReturn(true);
        when(imapFolder.open(Folder.READ_ONLY, ResyncData.CONDSTORE))
                .thenThrow(new MessagingException("ENABLE failed"));

        ImapFolderExecutor executor = new ImapFolderExecutor(connectionManager);

        Object seen = executor.executeReadOnlyResynced(7L, Lane.BACKGROUND, "INBOX", null,
                (f, uid, resyncEvents) -> resyncEvents);

        assertNull(seen);
        verify(imapFolder).open(Folder.READ_ONLY);
    }

    /**
     * Angus sends ENABLE before a CONDSTORE SELECT and, on a server that does not
     * advertise it, turns the refusal into a logout. Such a server is opened
     * plainly rather than reconnected every cycle.
     */
    @Test
    void aServerWithoutEnableIsOpenedPlainly() throws Exception {
        org.eclipse.angus.mail.imap.IMAPStore imapStore = qresyncStore();
        when(imapStore.hasCapability("ENABLE")).thenReturn(false);
        org.eclipse.angus.mail.imap.IMAPFolder imapFolder = mock(org.eclipse.angus.mail.imap.IMAPFolder.class);
        when(imapStore.getFolder("INBOX")).thenReturn(imapFolder);
        when(imapFolder.exists()).thenReturn(true);

        ImapFolderExecutor executor = new ImapFolderExecutor(connectionManager);

        executor.executeReadOnlyResynced(7L, Lane.BACKGROUND, "INBOX", null, (f, uid, resyncEvents) -> resyncEvents);

        verify(imapFolder).open(Folder.READ_ONLY);
        verify(imapFolder, never()).open(anyInt(), any());
    }

    /**
     * CONDSTORE stays a sync-cycle concern. Enabling it changes every later FETCH
     * response on the pooled connection, and a user opening a message has no use
     * for a MODSEQ.
     */
    @Test
    void anOrdinaryOpenNeverEnablesCondstore() throws Exception {
        org.eclipse.angus.mail.imap.IMAPStore imapStore = imapStore();
        // Lenient: the point is that the ordinary open does not even ask.
        lenient().when(imapStore.hasCapability(anyString())).thenReturn(true);
        org.eclipse.angus.mail.imap.IMAPFolder imapFolder = mock(org.eclipse.angus.mail.imap.IMAPFolder.class);
        when(imapStore.getFolder("INBOX")).thenReturn(imapFolder);
        when(imapFolder.exists()).thenReturn(true);

        ImapFolderExecutor executor = new ImapFolderExecutor(connectionManager);

        executor.executeReadOnly(7L, Lane.INTERACTIVE, "INBOX", (f, uid) -> null);

        verify(imapFolder).open(Folder.READ_ONLY);
        verify(imapFolder, never()).open(anyInt(), any());
    }
}
