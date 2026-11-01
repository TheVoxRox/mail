package org.voxrox.mailbackend.feature.mail.service;

import org.jspecify.annotations.Nullable;
import org.voxrox.mailbackend.feature.mail.dto.FolderResponse;
import org.voxrox.mailbackend.feature.mail.dto.FolderRole;

import module java.base;

/**
 * The order and the hierarchy the folder list is shown in: the inbox and its
 * subfolders, the other system folders in the fixed order of
 * {@link #SYSTEM_ORDER}, then the user's own folders. Each folder is followed
 * by its subfolders, and folders under one parent are ordered alphabetically by
 * the name they show, in the language of the request.
 *
 * <p>
 * Before it the list kept the order of the server's LIST response, which IMAP
 * leaves to the server: the inbox was not necessarily on top, and the same
 * folders came in a different order from each provider.
 *
 * <p>
 * The {@code parentRef} it returns is where the folder is shown, which is not
 * always where the server keeps it, see {@link #shownUnder}. The client renders
 * the hierarchy from it, so the order and the nesting cannot disagree.
 *
 * <p>
 * Folders that share a role keep the server's relative order, so "the first
 * folder with this role" means the same folder before and after the sort.
 */
final class FolderDisplayOrder {

    /**
     * Outlook's folder pane opens with Inbox, Drafts, Sent Items and Deleted Items.
     * Newsletters is Seznam's second folder of incoming mail, so it sits next to
     * the inbox; archive and junk follow the four. The inbox itself is not here,
     * because its subfolders come between it and the rest, and neither is
     * {@link FolderRole#USER}, which comes after all of them.
     */
    private static final List<FolderRole> SYSTEM_ORDER = List.of(FolderRole.NEWSLETTERS, FolderRole.DRAFTS,
            FolderRole.SENT, FolderRole.TRASH, FolderRole.ARCHIVE, FolderRole.JUNK);

    private static final int INBOX_GROUP = 0;
    private static final int FIRST_SYSTEM_GROUP = 1;
    private static final int USER_GROUP = FIRST_SYSTEM_GROUP + SYSTEM_ORDER.size();

    private FolderDisplayOrder() {
    }

    static List<FolderResponse> sort(List<FolderResponse> folders, Locale locale) {
        Collator collator = Collator.getInstance(locale);
        Map<String, FolderResponse> byRef = HashMap.newHashMap(folders.size());
        folders.forEach(folder -> byRef.putIfAbsent(folder.folderRef(), folder));
        String inboxRef = folders.stream().filter(folder -> folder.role() == FolderRole.INBOX)
                .map(FolderResponse::folderRef).findFirst().orElse(null);
        boolean inboxIsParent = inboxIsParent(folders, byRef, inboxRef);

        List<FolderResponse> roots = new ArrayList<>();
        Map<String, List<FolderResponse>> children = new HashMap<>();
        for (FolderResponse folder : folders) {
            String parentRef = shownUnder(folder, byRef, inboxRef, inboxIsParent);
            FolderResponse shown = Objects.equals(parentRef, folder.parentRef())
                    ? folder
                    : new FolderResponse(folder.displayName(), folder.folderRef(), folder.unreadCount(), folder.role(),
                            parentRef);
            if (parentRef == null) {
                roots.add(shown);
            } else {
                children.computeIfAbsent(parentRef, ref -> new ArrayList<>()).add(shown);
            }
        }

        Comparator<FolderResponse> byName = (a, b) -> collator.compare(a.displayName(), b.displayName());
        // List.sort is stable, which is what keeps a role's folders in the server's
        // order.
        roots.sort(Comparator.comparingInt(FolderDisplayOrder::group)
                .thenComparing((a, b) -> a.role() == FolderRole.USER ? byName.compare(a, b) : 0));
        /*
         * Each folder, then its subfolders, depth first. A stack of its own rather than
         * recursion: the depth is the server's to choose, and a hierarchy a few
         * thousand levels deep must not overflow the request thread's. Each list of
         * subfolders is taken out of the map as it is placed, so a folder appears once
         * even if the server listed its parent's name twice.
         */
        List<FolderResponse> sorted = new ArrayList<>(folders.size());
        Deque<FolderResponse> pending = new ArrayDeque<>(roots);
        while (!pending.isEmpty()) {
            FolderResponse folder = pending.pop();
            sorted.add(folder);
            List<FolderResponse> subfolders = children.remove(folder.folderRef());
            if (subfolders != null) {
                subfolders.sort(byName);
                subfolders.reversed().forEach(pending::push);
            }
        }
        return List.copyOf(sorted);
    }

    private static int group(FolderResponse folder) {
        return switch (folder.role()) {
            case INBOX -> INBOX_GROUP;
            case USER -> USER_GROUP;
            default -> FIRST_SYSTEM_GROUP + SYSTEM_ORDER.indexOf(folder.role());
        };
    }

    /**
     * The folder this one is shown under, or {@code null} for the top level.
     * Usually the parent the server keeps it under, with two exceptions.
     *
     * <p>
     * A system folder is always top-level, wherever the server keeps it: its place
     * is its slot in the fixed order. Its own subfolders still follow it.
     *
     * <p>
     * Where INBOX is the namespace every folder lives in, as on Courier and older
     * Dovecot ("INBOX.Sent", "INBOX.Trash"), the inbox's subfolders are shown at
     * the top level. Nested below it they would put the user's own folders above
     * Drafts and Sent and indent every folder of the account one level for nothing.
     * Their own subfolders stay nested.
     */
    private static @Nullable String shownUnder(FolderResponse folder, Map<String, FolderResponse> byRef,
            @Nullable String inboxRef, boolean inboxIsParent) {
        String parentRef = folder.parentRef();
        if (folder.role() != FolderRole.USER || parentRef == null || !byRef.containsKey(parentRef)) {
            return null;
        }
        return inboxIsParent || !parentRef.equals(inboxRef) ? parentRef : null;
    }

    /**
     * Whether the inbox's subfolders are shown below it, as in Outlook, where
     * people file mail into them. Not where INBOX is the namespace of every folder,
     * see {@link #shownUnder}; a system folder somewhere below INBOX is the sign of
     * that layout.
     */
    private static boolean inboxIsParent(List<FolderResponse> folders, Map<String, FolderResponse> byRef,
            @Nullable String inboxRef) {
        if (inboxRef == null) {
            return true;
        }
        for (FolderResponse folder : folders) {
            if (folder.role() != FolderRole.USER && folder.role() != FolderRole.INBOX
                    && isBelow(folder, inboxRef, byRef)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Walks up the server's hierarchy. The walk ends: ImapFolderService names a
     * listed ancestor as the parent, whose name is shorter than the folder's own.
     */
    private static boolean isBelow(FolderResponse folder, String ancestorRef, Map<String, FolderResponse> byRef) {
        FolderResponse current = folder;
        while (current != null && current.parentRef() != null) {
            if (ancestorRef.equals(current.parentRef())) {
                return true;
            }
            current = byRef.get(current.parentRef());
        }
        return false;
    }
}
