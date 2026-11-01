package org.voxrox.mailbackend.feature.mail.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.voxrox.mailbackend.feature.mail.dto.FolderResponse;
import org.voxrox.mailbackend.feature.mail.dto.FolderRole;

class FolderDisplayOrderTest {

    private static final Locale CS = Locale.forLanguageTag("cs");

    @Test
    @DisplayName("System folders come first in the fixed order, whatever order the server listed them in")
    void systemFoldersFirstInFixedOrder() {
        List<FolderResponse> listed = List.of(folder("Spam", FolderRole.JUNK), folder("Alpha", FolderRole.USER),
                folder("Archive", FolderRole.ARCHIVE), folder("Trash", FolderRole.TRASH),
                folder("Sent", FolderRole.SENT), folder("Drafts", FolderRole.DRAFTS),
                folder("newsletters", FolderRole.NEWSLETTERS), folder("INBOX", FolderRole.INBOX));

        assertThat(FolderDisplayOrder.sort(listed, Locale.ENGLISH)).extracting(FolderResponse::role).containsExactly(
                FolderRole.INBOX, FolderRole.NEWSLETTERS, FolderRole.DRAFTS, FolderRole.SENT, FolderRole.TRASH,
                FolderRole.ARCHIVE, FolderRole.JUNK, FolderRole.USER);
    }

    @Test
    @DisplayName("User folders sort by the collation of the request's language")
    void userFoldersFollowTheLocaleCollation() {
        // "Ch" is a letter of its own in Czech, after H, and C with caron comes after
        // C. English sorts both as plain C. The word is "Cteni" with a caron on the C
        // and an acute on the i.
        String reading = Character.toString(0x10C) + "ten" + Character.toString(0xED);
        List<FolderResponse> listed = List.of(folder("Hory", FolderRole.USER), folder("Chata", FolderRole.USER),
                folder(reading, FolderRole.USER), folder("Cesty", FolderRole.USER));

        assertThat(FolderDisplayOrder.sort(listed, CS)).extracting(FolderResponse::folderRef).containsExactly("Cesty",
                reading, "Hory", "Chata");
        assertThat(FolderDisplayOrder.sort(listed, Locale.ENGLISH)).extracting(FolderResponse::folderRef)
                .containsExactly("Cesty", "Chata", reading, "Hory");
    }

    @Test
    @DisplayName("A subfolder follows its parent, also where a sibling's name would sort between them")
    void subfolderFollowsItsParent() {
        List<FolderResponse> listed = List.of(nested("Projects, old", '/'), nested("Projects/2024", '/'),
                nested("Projects", '/'), nested("Projects/2024/Q1", '/'), nested("Archive 2020", '/'));

        assertThat(FolderDisplayOrder.sort(listed, Locale.ENGLISH)).extracting(FolderResponse::folderRef)
                .containsExactly("Archive 2020", "Projects", "Projects/2024", "Projects/2024/Q1", "Projects, old");
    }

    @Test
    @DisplayName("A dot delimiter splits the path the same way")
    void dotDelimiterSplitsThePath() {
        List<FolderResponse> listed = List.of(nested("Work.Projects, old", '.'), nested("Work.Projects.2024", '.'),
                nested("Work.Projects", '.'));

        assertThat(FolderDisplayOrder.sort(listed, Locale.ENGLISH)).extracting(FolderResponse::folderRef)
                .containsExactly("Work.Projects", "Work.Projects.2024", "Work.Projects, old");
    }

    @Test
    @DisplayName("The inbox's subfolders come right below it, ahead of the other system folders, whatever case spells INBOX")
    void inboxSubfoldersFollowTheInbox() {
        List<FolderResponse> listed = List.of(folder("Alpha", FolderRole.USER), nested("Inbox/Zeta", '/'),
                folder("Drafts", FolderRole.DRAFTS), nested("INBOX/Clients/2024", '/'),
                folder("INBOX", FolderRole.INBOX), nested("INBOX/Clients", '/'));

        assertThat(FolderDisplayOrder.sort(listed, Locale.ENGLISH)).extracting(FolderResponse::folderRef)
                .containsExactly("INBOX", "INBOX/Clients", "INBOX/Clients/2024", "Inbox/Zeta", "Drafts", "Alpha");
    }

    @Test
    @DisplayName("Where every folder lives under INBOX, its subfolders are the user's own and come after the system ones")
    void inboxAsNamespaceKeepsUserFoldersLast() {
        List<FolderResponse> listed = List.of(nested("INBOX.Projects", '.'), nested("INBOX.Sent", '.', FolderRole.SENT),
                folder("INBOX", FolderRole.INBOX), nested("INBOX.Archive 2020", '.'),
                nested("INBOX.Trash", '.', FolderRole.TRASH));

        assertThat(FolderDisplayOrder.sort(listed, Locale.ENGLISH)).extracting(FolderResponse::folderRef)
                .containsExactly("INBOX", "INBOX.Sent", "INBOX.Trash", "INBOX.Archive 2020", "INBOX.Projects");
    }

    @Test
    @DisplayName("Folders that share a role keep the server's relative order")
    void sharedRoleKeepsServerOrder() {
        List<FolderResponse> listed = List.of(folder("Zeta", FolderRole.USER), folder("Trash", FolderRole.TRASH),
                folder("INBOX", FolderRole.INBOX), folder("Deleted Items", FolderRole.TRASH));

        assertThat(FolderDisplayOrder.sort(listed, Locale.ENGLISH)).extracting(FolderResponse::folderRef)
                .containsExactly("INBOX", "Trash", "Deleted Items", "Zeta");
    }

    private static FolderResponse folder(String ref, FolderRole role) {
        return new FolderResponse(ref, ref, 0, role);
    }

    private static FolderResponse nested(String ref, char delimiter) {
        return nested(ref, delimiter, FolderRole.USER);
    }

    /**
     * A folder as ImapFolderService builds it: the display name is the part of the
     * ref after the last delimiter.
     */
    private static FolderResponse nested(String ref, char delimiter, FolderRole role) {
        return new FolderResponse(ref.substring(ref.lastIndexOf(delimiter) + 1), ref, 0, role);
    }
}
