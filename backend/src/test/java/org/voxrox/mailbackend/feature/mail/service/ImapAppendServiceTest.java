package org.voxrox.mailbackend.feature.mail.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.Properties;

import jakarta.mail.Flags;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.UIDFolder;
import jakarta.mail.internet.MimeMessage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.voxrox.mailbackend.exception.ErrorCode;
import org.voxrox.mailbackend.exception.MailOperationException;
import org.voxrox.mailbackend.feature.mail.dto.FolderRole;

/**
 * Unit tests for {@link ImapAppendService}.
 *
 * <p>
 * Mocks of {@link ImapConnectionManager#executeWithLock} and
 * {@link ImapFolderService#executeInFolder} act as callback callers — in tests
 * we manually invoke the callback via {@link org.mockito.stubbing.Answer} so we
 * can verify what happens inside (markSeen, appendMessages, fetch UID).
 */
@ExtendWith(MockitoExtension.class)
class ImapAppendServiceTest {

    private static final Long ACCOUNT_ID = 11L;
    private static final String FOLDER_NAME = "[Gmail]/Sent";

    @Mock
    private ImapConnectionManager imapConnectionManager;
    @Mock
    private ImapFolderService imapFolderService;

    @InjectMocks
    private ImapAppendService service;

    @Nested
    @DisplayName("appendByRole — best-effort archive")
    class AppendByRole {

        @Mock
        private Store store;
        @Mock
        private Folder folder;
        @Mock
        private MimeMessage message;

        @BeforeEach
        void wireExecuteWithLock() throws Exception {
            // Default: the lock callback is invoked with our mock store. lenient()
            // because missingFolderRoleSkipsExecuteWithLock never reaches here
            // (findFolderNameByRoleOrThrow throws before the service takes the lock).
            lenient().when(imapConnectionManager.executeWithLock(eq(ACCOUNT_ID), any())).thenAnswer(inv -> {
                ImapConnectionManager.StoreAction<?> action = inv.getArgument(1);
                return action.execute(store);
            });
            lenient().when(store.getFolder(FOLDER_NAME)).thenReturn(folder);
        }

        @Test
        @DisplayName("Happy path — markSeen=true -> SEEN flag set + appendMessages with 1 message")
        void happyPathMarkSeenAppends() throws Exception {
            when(imapFolderService.findFolderNameByRoleOrThrow(ACCOUNT_ID, FolderRole.SENT)).thenReturn(FOLDER_NAME);
            when(folder.exists()).thenReturn(true);
            when(folder.isOpen()).thenReturn(true);

            assertThat(service.appendByRole(ACCOUNT_ID, FolderRole.SENT, message, true)).isTrue();

            verify(message).setFlag(Flags.Flag.SEEN, true);

            ArgumentCaptor<Message[]> appended = ArgumentCaptor.forClass(Message[].class);
            verify(folder).appendMessages(appended.capture());
            assertThat(appended.getValue()).hasSize(1).containsExactly(message);

            verify(folder).open(Folder.READ_WRITE);
            verify(folder).close(false);
        }

        @Test
        @DisplayName("markSeen=false (DRAFTS) — SEEN flag is NOT set, but append still happens")
        void draftsDoNotMarkSeen() throws Exception {
            when(imapFolderService.findFolderNameByRoleOrThrow(ACCOUNT_ID, FolderRole.DRAFTS)).thenReturn(FOLDER_NAME);
            when(folder.exists()).thenReturn(true);
            when(folder.isOpen()).thenReturn(true);

            assertThat(service.appendByRole(ACCOUNT_ID, FolderRole.DRAFTS, message, false)).isTrue();

            verify(message, never()).setFlag(eq(Flags.Flag.SEEN), eq(true));
            verify(folder, times(1)).appendMessages(any());
        }

        @Test
        @DisplayName("Folder does not exist on the server — log warn, no append, no exception")
        void folderDoesNotExistIsBestEffortNoOp() throws Exception {
            when(imapFolderService.findFolderNameByRoleOrThrow(ACCOUNT_ID, FolderRole.SENT)).thenReturn(FOLDER_NAME);
            when(folder.exists()).thenReturn(false);

            // Best-effort: no throw, and a non-existent folder reports failure (false)
            // so a draft-replace caller will not delete the previous revision.
            assertThat(service.appendByRole(ACCOUNT_ID, FolderRole.SENT, message, true)).isFalse();

            verify(folder, never()).open(anyInt());
            verify(folder, never()).appendMessages(any());
        }

        @Test
        @DisplayName("Folder role not detected — exception is swallowed (best-effort false), no IMAP lock")
        void missingFolderRoleSkipsExecuteWithLock() {
            when(imapFolderService.findFolderNameByRoleOrThrow(ACCOUNT_ID, FolderRole.SENT)).thenThrow(
                    new MailOperationException(ErrorCode.FOLDER_ROLE_NOT_FOUND, "No SENT folder for account"));

            assertThat(service.appendByRole(ACCOUNT_ID, FolderRole.SENT, message, true)).isFalse();

            verify(imapConnectionManager, never()).executeWithLock(any(), any());
        }
    }

    @Nested
    @DisplayName("fetchAndDetachMime — IMAP fetch + detach")
    class FetchAndDetachMime {

        private final Session session = Session.getInstance(new Properties());

        @Test
        @DisplayName("UID does not exist in IMAP — Optional.empty()")
        void missingUidReturnsEmpty() throws Exception {
            // executeInFolder invokes the action callback with mock folder/uidFolder.
            // The callback inside calls uidFolder.getMessageByUID(uid) -> null -> return
            // null
            // -> the service wraps it into Optional.empty().
            Folder folder = mock(Folder.class);
            UIDFolder uidFolder = mock(UIDFolder.class);
            when(uidFolder.getMessageByUID(123L)).thenReturn(null);

            when(imapFolderService.executeInFolder(eq(ACCOUNT_ID), eq(FOLDER_NAME), eq(Folder.READ_ONLY), any()))
                    .thenAnswer(inv -> {
                        ImapFolderAction<?> action = inv.getArgument(3);
                        return action.apply(folder, uidFolder);
                    });

            Optional<MimeMessage> result = service.fetchAndDetachMime(ACCOUNT_ID, FOLDER_NAME, 123L, session);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("Happy path — the returned MimeMessage is an independent detached copy (via writeTo + parse)")
        void happyPathReturnsDetachedMime() throws Exception {
            // Prepare a "server-side" message with its own Message-ID and content
            // that getMessageByUID will return.
            MimeMessage onServer = new MimeMessage(session);
            onServer.setSubject("Test draft");
            onServer.setText("draft body");
            onServer.saveChanges();
            onServer.setHeader("Message-ID", "<original@example.com>");

            Folder folder = mock(Folder.class);
            UIDFolder uidFolder = mock(UIDFolder.class);
            when(uidFolder.getMessageByUID(456L)).thenReturn(onServer);

            when(imapFolderService.executeInFolder(eq(ACCOUNT_ID), eq(FOLDER_NAME), eq(Folder.READ_ONLY), any()))
                    .thenAnswer(inv -> {
                        ImapFolderAction<?> action = inv.getArgument(3);
                        return action.apply(folder, uidFolder);
                    });

            Optional<MimeMessage> result = service.fetchAndDetachMime(ACCOUNT_ID, FOLDER_NAME, 456L, session);

            assertThat(result).isPresent();
            MimeMessage detached = result.get();
            assertThat(detached.getSubject()).isEqualTo("Test draft");
            assertThat(detached.getHeader("Message-ID")).containsExactly("<original@example.com>");
            // Detached: re-parsed instance, not the same object as onServer.
            assertThat(detached).isNotSameAs(onServer);
        }
    }
}
