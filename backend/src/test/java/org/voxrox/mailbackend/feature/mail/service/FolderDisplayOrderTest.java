package org.voxrox.mailbackend.feature.mail.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.util.ArrayList;
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
        List<FolderResponse> listed = List.of(folder("Projects, old", FolderRole.USER),
                child("Projects/2024", "Projects"), folder("Projects", FolderRole.USER),
                child("Projects/2024/Q1", "Projects/2024"), child("Projects/2023", "Projects"),
                folder("Archive 2020", FolderRole.USER));

        assertThat(FolderDisplayOrder.sort(listed, Locale.ENGLISH))
                .extracting(FolderResponse::folderRef, FolderResponse::parentRef)
                .containsExactly(tuple("Archive 2020", null), tuple("Projects", null),
                        tuple("Projects/2023", "Projects"), tuple("Projects/2024", "Projects"),
                        tuple("Projects/2024/Q1", "Projects/2024"), tuple("Projects, old", null));
    }

    @Test
    @DisplayName("The inbox's subfolders come right below it, ahead of the other system folders")
    void inboxSubfoldersFollowTheInbox() {
        List<FolderResponse> listed = List.of(folder("Alpha", FolderRole.USER), child("Inbox/Zeta", "INBOX"),
                folder("Drafts", FolderRole.DRAFTS), child("INBOX/Clients/2024", "INBOX/Clients"),
                folder("INBOX", FolderRole.INBOX), child("INBOX/Clients", "INBOX"));

        assertThat(FolderDisplayOrder.sort(listed, Locale.ENGLISH))
                .extracting(FolderResponse::folderRef, FolderResponse::parentRef).containsExactly(tuple("INBOX", null),
                        tuple("INBOX/Clients", "INBOX"), tuple("INBOX/Clients/2024", "INBOX/Clients"),
                        tuple("Inbox/Zeta", "INBOX"), tuple("Drafts", null), tuple("Alpha", null));
    }

    @Test
    @DisplayName("Where every folder lives under INBOX, its subfolders are top-level and the user's own come after the system ones")
    void inboxAsNamespaceKeepsUserFoldersLast() {
        List<FolderResponse> listed = List.of(child("INBOX.Projects", "INBOX"),
                child("INBOX.Sent", "INBOX", FolderRole.SENT), folder("INBOX", FolderRole.INBOX),
                child("INBOX.Projects.2024", "INBOX.Projects"), child("INBOX.Archive 2020", "INBOX"),
                child("INBOX.Trash", "INBOX", FolderRole.TRASH));

        assertThat(FolderDisplayOrder.sort(listed, Locale.ENGLISH))
                .extracting(FolderResponse::folderRef, FolderResponse::parentRef).containsExactly(tuple("INBOX", null),
                        tuple("INBOX.Sent", null), tuple("INBOX.Trash", null), tuple("INBOX.Archive 2020", null),
                        tuple("INBOX.Projects", null), tuple("INBOX.Projects.2024", "INBOX.Projects"));
    }

    @Test
    @DisplayName("A system folder's subfolders follow it, not the user's folders")
    void systemFolderSubfoldersFollowIt() {
        List<FolderResponse> listed = List.of(folder("Alpha", FolderRole.USER), child("Sent/2023", "Sent"),
                folder("Zeta", FolderRole.USER), folder("Sent", FolderRole.SENT), folder("INBOX", FolderRole.INBOX));

        assertThat(FolderDisplayOrder.sort(listed, Locale.ENGLISH))
                .extracting(FolderResponse::folderRef, FolderResponse::parentRef).containsExactly(tuple("INBOX", null),
                        tuple("Sent", null), tuple("Sent/2023", "Sent"), tuple("Alpha", null), tuple("Zeta", null));
    }

    @Test
    @DisplayName("A system folder the server keeps under a user folder is shown at the top level, in its slot")
    void systemFolderIsAlwaysTopLevel() {
        List<FolderResponse> listed = List.of(child("Work/Junk", "Work", FolderRole.JUNK),
                folder("Work", FolderRole.USER), folder("INBOX", FolderRole.INBOX));

        assertThat(FolderDisplayOrder.sort(listed, Locale.ENGLISH))
                .extracting(FolderResponse::folderRef, FolderResponse::parentRef)
                .containsExactly(tuple("INBOX", null), tuple("Work/Junk", null), tuple("Work", null));
    }

    @Test
    @DisplayName("A folder whose parent is not in the list is shown at the top level")
    void folderWithUnlistedParentIsTopLevel() {
        List<FolderResponse> listed = List.of(child("Gone/Kept", "Gone"), folder("Alpha", FolderRole.USER));

        assertThat(FolderDisplayOrder.sort(listed, Locale.ENGLISH))
                .extracting(FolderResponse::folderRef, FolderResponse::parentRef)
                .containsExactly(tuple("Alpha", null), tuple("Gone/Kept", null));
    }

    @Test
    @DisplayName("A hierarchy as deep as the server makes it is placed without running out of stack")
    void deepHierarchyDoesNotOverflow() {
        int depth = 100_000;
        List<FolderResponse> listed = new ArrayList<>(depth);
        listed.add(folder("f0", FolderRole.USER));
        for (int i = 1; i < depth; i++) {
            listed.add(new FolderResponse("f" + i, "f" + i, 0, FolderRole.USER, "f" + (i - 1)));
        }

        List<FolderResponse> sorted = FolderDisplayOrder.sort(listed.reversed(), Locale.ENGLISH);

        assertThat(sorted).hasSize(depth);
        assertThat(sorted.getLast().folderRef()).isEqualTo("f" + (depth - 1));
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

    private static FolderResponse child(String ref, String parentRef) {
        return child(ref, parentRef, FolderRole.USER);
    }

    /**
     * A folder as ImapFolderService builds it: the display name is the part of the
     * ref after its parent and the delimiter.
     */
    private static FolderResponse child(String ref, String parentRef, FolderRole role) {
        return new FolderResponse(ref.substring(parentRef.length() + 1), ref, 0, role, parentRef);
    }
}
