package org.voxrox.mailbackend.feature.mail.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import jakarta.mail.Folder;
import jakarta.mail.MessagingException;
import jakarta.mail.Store;

import org.eclipse.angus.mail.imap.IMAPFolder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.voxrox.mailbackend.exception.ErrorCode;
import org.voxrox.mailbackend.exception.MailOperationException;
import org.voxrox.mailbackend.feature.mail.dto.FolderResponse;
import org.voxrox.mailbackend.feature.mail.dto.FolderRole;
import org.voxrox.mailbackend.feature.mail.repository.FolderSyncStateRepository;
import org.voxrox.mailbackend.feature.mail.repository.MessageRepository;

@ExtendWith(MockitoExtension.class)
class ImapFolderServiceTest {

    private static final Long ACCOUNT_ID = 7L;

    @Mock
    private ImapConnectionManager imapConnectionManager;
    @Mock
    private ImapFolderExecutor imapFolderExecutor;
    @Mock
    private FolderSyncStateRepository folderSyncStateRepository;
    @Mock
    private MessageRepository messageRepository;

    private FolderListCache folderListCache;
    private ImapFolderService service;

    @BeforeEach
    void setUp() {
        folderListCache = new FolderListCache();
        service = new ImapFolderService(imapConnectionManager, imapFolderExecutor, folderSyncStateRepository,
                messageRepository, folderListCache);
    }

    @Nested
    @DisplayName("getFolders")
    class GetFolders {

        @Test
        @DisplayName("Unread count is taken from the IMAP server even before local folder sync")
        void usesServerUnreadCount() throws Exception {
            Folder folder = folder("Projects", "Projects", 4);
            mockFolderListing(folder);

            List<FolderResponse> result = service.getFolders(ACCOUNT_ID);

            assertThat(result).singleElement().satisfies(response -> {
                assertThat(response.folderRef()).isEqualTo("Projects");
                assertThat(response.unreadCount()).isEqualTo(4);
                assertThat(response.role()).isEqualTo(FolderRole.USER);
            });
            verifyNoInteractions(messageRepository);
        }

        @Test
        @DisplayName("Second call within the TTL is served from the cache — no second IMAP round-trip")
        void servesRepeatCallFromCache() throws Exception {
            mockFolderListing(folder("Projects", "Projects", 4));

            List<FolderResponse> first = service.getFolders(ACCOUNT_ID);
            List<FolderResponse> second = service.getFolders(ACCOUNT_ID);

            assertThat(second).isEqualTo(first);
            verify(imapConnectionManager, times(1)).executeWithLock(eq(ACCOUNT_ID), any());
        }

        @Test
        @DisplayName("Invalidation forces the next call back to IMAP")
        void invalidationForcesRefresh() throws Exception {
            mockFolderListing(folder("Projects", "Projects", 4));

            service.getFolders(ACCOUNT_ID);
            folderListCache.invalidate(ACCOUNT_ID);
            service.getFolders(ACCOUNT_ID);

            verify(imapConnectionManager, times(2)).executeWithLock(eq(ACCOUNT_ID), any());
        }

        @Test
        @DisplayName("When IMAP unread count is not available, fall back to local DB")
        void fallsBackToLocalUnreadCount() throws Exception {
            Folder folder = folder("Projects", "Projects", -1);
            mockFolderListing(folder);
            when(messageRepository.countByAccountIdAndFolderNameAndSeenFalse(ACCOUNT_ID, "Projects")).thenReturn(2L);

            List<FolderResponse> result = service.getFolders(ACCOUNT_ID);

            assertThat(result).singleElement().extracting(FolderResponse::unreadCount).isEqualTo(2);
            verify(messageRepository).countByAccountIdAndFolderNameAndSeenFalse(ACCOUNT_ID, "Projects");
        }

        @Test
        @DisplayName("MessagingException during unread count does not block the folder listing")
        void fallsBackWhenUnreadCountFails() throws Exception {
            Folder folder = folder("Projects", "Projects", 0);
            when(folder.getUnreadMessageCount()).thenThrow(new MessagingException("STATUS failed"));
            mockFolderListing(folder);
            when(messageRepository.countByAccountIdAndFolderNameAndSeenFalse(ACCOUNT_ID, "Projects")).thenReturn(3L);

            List<FolderResponse> result = service.getFolders(ACCOUNT_ID);

            assertThat(result).singleElement().extracting(FolderResponse::unreadCount).isEqualTo(3);
        }

        @Test
        @DisplayName("Folder named 'newsletters' tagged with \\Junk SPECIAL-USE resolves to NEWSLETTERS — name wins over attribute (seznam.cz scenario)")
        void newslettersByNameWinsOverJunkAttribute() throws Exception {
            IMAPFolder folder = mock(IMAPFolder.class);
            when(folder.getName()).thenReturn("newsletters");
            when(folder.getFullName()).thenReturn("newsletters");
            when(folder.getUnreadMessageCount()).thenReturn(0);
            when(folder.getType()).thenReturn(Folder.HOLDS_MESSAGES);
            // Lenient: name-based NEWSLETTERS detection wins before getAttributes is ever
            // consulted — the stub documents the antagonistic SPECIAL-USE we override.
            lenient().when(folder.getAttributes()).thenReturn(new String[]{"\\Junk"});
            mockFolderListing(folder);

            List<FolderResponse> result = service.getFolders(ACCOUNT_ID);

            assertThat(result).singleElement().extracting(FolderResponse::role).isEqualTo(FolderRole.NEWSLETTERS);
        }

        @Test
        @DisplayName("Plain spam folder (only \\Junk SPECIAL-USE) still resolves to JUNK")
        void spamFolderStillResolvesToJunk() throws Exception {
            IMAPFolder folder = mock(IMAPFolder.class);
            when(folder.getName()).thenReturn("Spam");
            when(folder.getFullName()).thenReturn("Spam");
            when(folder.getUnreadMessageCount()).thenReturn(0);
            when(folder.getType()).thenReturn(Folder.HOLDS_MESSAGES);
            when(folder.getAttributes()).thenReturn(new String[]{"\\Junk"});
            mockFolderListing(folder);

            List<FolderResponse> result = service.getFolders(ACCOUNT_ID);

            assertThat(result).singleElement().extracting(FolderResponse::role).isEqualTo(FolderRole.JUNK);
        }

        @Test
        @DisplayName("Name-fallback role does not impersonate a sibling already claimed via SPECIAL-USE")
        void nameFallbackYieldsToSpecialUseSibling() throws Exception {
            // System Gmail trash with \Trash SPECIAL-USE.
            IMAPFolder systemTrash = mock(IMAPFolder.class);
            when(systemTrash.getName()).thenReturn("Koš");
            when(systemTrash.getFullName()).thenReturn("[Gmail]/Koš");
            when(systemTrash.getUnreadMessageCount()).thenReturn(31);
            when(systemTrash.getType()).thenReturn(Folder.HOLDS_MESSAGES);
            when(systemTrash.getAttributes()).thenReturn(new String[]{"\\Trash"});

            // User-created label whose name contains "koš" — must not be misclassified
            // as TRASH because the system trash has already claimed the role.
            IMAPFolder userLabel = mock(IMAPFolder.class);
            when(userLabel.getName()).thenReturn("[Gmail]Koš");
            when(userLabel.getFullName()).thenReturn("[Gmail]Koš");
            when(userLabel.getUnreadMessageCount()).thenReturn(0);
            when(userLabel.getType()).thenReturn(Folder.HOLDS_MESSAGES);
            when(userLabel.getAttributes()).thenReturn(new String[0]);

            mockFolderListing(systemTrash, userLabel);

            List<FolderResponse> result = service.getFolders(ACCOUNT_ID);

            assertThat(result).hasSize(2);
            assertThat(result).filteredOn(r -> r.folderRef().equals("[Gmail]/Koš")).singleElement()
                    .extracting(FolderResponse::role).isEqualTo(FolderRole.TRASH);
            assertThat(result).filteredOn(r -> r.folderRef().equals("[Gmail]Koš")).singleElement()
                    .extracting(FolderResponse::role).isEqualTo(FolderRole.USER);
        }

        @Test
        @DisplayName("\\Noselect containers (e.g. Gmail's '[Gmail]' parent) are filtered out of the listing")
        void noselectContainersAreFiltered() throws Exception {
            // Gmail parent node — \Noselect, advertises HOLDS_FOLDERS only. Real
            // children sit underneath as [Gmail]/All Mail, [Gmail]/Sent Mail, …
            IMAPFolder gmailContainer = mock(IMAPFolder.class);
            when(gmailContainer.getType()).thenReturn(Folder.HOLDS_FOLDERS);

            Folder inbox = folder("INBOX", "INBOX", 0);

            mockFolderListing(gmailContainer, inbox);

            List<FolderResponse> result = service.getFolders(ACCOUNT_ID);

            assertThat(result).singleElement().extracting(FolderResponse::folderRef).isEqualTo("INBOX");
        }

        @Test
        @DisplayName("Name-fallback still applies when no SPECIAL-USE sibling has claimed the role")
        void nameFallbackAppliesWhenRoleUnclaimed() throws Exception {
            // Provider that does not advertise SPECIAL-USE — name fallback must still work.
            Folder trashByName = folder("Smazané", "Smazané", 5);
            mockFolderListing(trashByName);

            List<FolderResponse> result = service.getFolders(ACCOUNT_ID);

            assertThat(result).singleElement().extracting(FolderResponse::role).isEqualTo(FolderRole.TRASH);
        }
    }

    @Nested
    @DisplayName("findFolderNameByRoleOrThrow")
    class FindFolderNameByRoleOrThrow {

        @Test
        @DisplayName("Returns the folder name resolved from the DB without touching IMAP")
        void returnsFolderNameFromDb() {
            when(folderSyncStateRepository.findFolderNameByRole(ACCOUNT_ID, FolderRole.DRAFTS))
                    .thenReturn(Optional.of("[Gmail]/Drafts"));

            assertThat(service.findFolderNameByRoleOrThrow(ACCOUNT_ID, FolderRole.DRAFTS)).isEqualTo("[Gmail]/Drafts");

            verifyNoInteractions(imapConnectionManager);
        }

        @Test
        @DisplayName("Throws MailOperationException(FOLDER_ROLE_NOT_FOUND) when the role resolves nowhere")
        void throwsWhenRoleUnresolved() throws Exception {
            when(folderSyncStateRepository.findFolderNameByRole(ACCOUNT_ID, FolderRole.DRAFTS))
                    .thenReturn(Optional.empty());
            // No folder in the IMAP listing carries the DRAFTS role either.
            mockFolderListing(folder("INBOX", "INBOX", 0));

            assertThatThrownBy(() -> service.findFolderNameByRoleOrThrow(ACCOUNT_ID, FolderRole.DRAFTS))
                    .isInstanceOf(MailOperationException.class).extracting(e -> ((MailOperationException) e).getCode())
                    .isEqualTo(ErrorCode.FOLDER_ROLE_NOT_FOUND);
        }
    }

    private void mockFolderListing(Folder... folders) throws Exception {
        Store store = mock(Store.class);
        Folder defaultFolder = mock(Folder.class);
        when(store.getDefaultFolder()).thenReturn(defaultFolder);
        when(defaultFolder.list("*")).thenReturn(folders);
        when(imapConnectionManager.executeWithLock(eq(ACCOUNT_ID), any())).thenAnswer(invocation -> {
            ImapConnectionManager.StoreAction<List<FolderResponse>> action = invocation.getArgument(1);
            return action.execute(store);
        });
    }

    private static Folder folder(String name, String fullName, int unreadCount) throws MessagingException {
        Folder folder = mock(Folder.class);
        when(folder.getName()).thenReturn(name);
        when(folder.getFullName()).thenReturn(fullName);
        when(folder.getUnreadMessageCount()).thenReturn(unreadCount);
        when(folder.getType()).thenReturn(Folder.HOLDS_MESSAGES);
        return folder;
    }
}
