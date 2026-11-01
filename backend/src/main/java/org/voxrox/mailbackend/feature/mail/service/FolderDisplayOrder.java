package org.voxrox.mailbackend.feature.mail.service;

import org.voxrox.mailbackend.feature.mail.dto.FolderConstants;
import org.voxrox.mailbackend.feature.mail.dto.FolderResponse;
import org.voxrox.mailbackend.feature.mail.dto.FolderRole;

import module java.base;

/**
 * The order the folder list is shown in: the inbox and its subfolders, the
 * other system folders in the fixed order of {@link #SYSTEM_ORDER}, then the
 * user's own folders. Subfolders are ordered alphabetically by path in the
 * language of the request, so each one follows its parent.
 *
 * <p>
 * Before it the list kept the order of the server's LIST response, which IMAP
 * leaves to the server: the inbox was not necessarily on top, and the same
 * folders came in a different order from each provider.
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
    private static final int INBOX_SUBFOLDER_GROUP = 1;
    private static final int FIRST_SYSTEM_GROUP = 2;
    private static final int USER_GROUP = FIRST_SYSTEM_GROUP + SYSTEM_ORDER.size();

    private FolderDisplayOrder() {
    }

    static List<FolderResponse> sort(List<FolderResponse> folders, Locale locale) {
        Collator collator = Collator.getInstance(locale);
        boolean inboxIsParent = inboxIsParent(folders);
        Comparator<FolderResponse> order = Comparator
                .comparingInt((FolderResponse folder) -> group(folder, inboxIsParent))
                .thenComparing((a, b) -> a.role() == FolderRole.USER ? comparePaths(path(a), path(b), collator) : 0);
        // Stream.sorted is stable on a list, which is what keeps a role's folders in
        // the server's order.
        return folders.stream().sorted(order).toList();
    }

    private static int group(FolderResponse folder, boolean inboxIsParent) {
        return switch (folder.role()) {
            case INBOX -> INBOX_GROUP;
            case USER -> inboxIsParent && isUnderInbox(folder) ? INBOX_SUBFOLDER_GROUP : USER_GROUP;
            default -> FIRST_SYSTEM_GROUP + SYSTEM_ORDER.indexOf(folder.role());
        };
    }

    /**
     * Whether the inbox's subfolders go right below it, as in Outlook, where people
     * file mail into them. Not where INBOX is the namespace every folder lives in,
     * as on Courier and older Dovecot ("INBOX.Sent", "INBOX.Trash"): there every
     * folder is a subfolder of the inbox, and keeping them below it would put the
     * user's own folders above Drafts and Sent. A system folder below INBOX is the
     * sign of that layout.
     */
    private static boolean inboxIsParent(List<FolderResponse> folders) {
        return folders.stream().noneMatch(folder -> folder.role() != FolderRole.USER
                && folder.role() != FolderRole.INBOX && isUnderInbox(folder));
    }

    private static boolean isUnderInbox(FolderResponse folder) {
        List<String> path = path(folder);
        return path.size() > 1 && path.getFirst().equals(FolderConstants.INBOX);
    }

    /**
     * The folder's path, split at the server's hierarchy delimiter. The response
     * does not carry the delimiter and does not need to: {@code displayName} is
     * {@code Folder.getName()}, the part of the full name after the last delimiter,
     * so the character in front of it in {@code folderRef} is the delimiter. A
     * folder whose name is the whole ref (top level, or a server without hierarchy)
     * is a path of one.
     *
     * <p>
     * INBOX is case-insensitive in IMAP, so a subfolder may spell it otherwise. The
     * path spells it one way, or the collation would part "Inbox/Clients" from
     * "INBOX/Bills" on the case of the inbox's name.
     */
    private static List<String> path(FolderResponse folder) {
        String ref = folder.folderRef();
        String name = folder.displayName();
        int delimiterAt = ref.length() - name.length() - 1;
        if (delimiterAt < 0 || !ref.endsWith(name)) {
            return List.of(ref);
        }
        String[] segments = ref.split(Pattern.quote(String.valueOf(ref.charAt(delimiterAt))), -1);
        if (segments[0].equalsIgnoreCase(FolderConstants.INBOX)) {
            segments[0] = FolderConstants.INBOX;
        }
        return List.of(segments);
    }

    /**
     * Segment by segment rather than the whole ref at once: compared whole,
     * "Projects, old" falls between "Projects" and "Projects/2024", because the
     * comma sorts before the delimiter, and the subfolder ends up away from its
     * parent.
     */
    private static int comparePaths(List<String> a, List<String> b, Collator collator) {
        for (int i = 0; i < Math.min(a.size(), b.size()); i++) {
            int bySegment = collator.compare(a.get(i), b.get(i));
            if (bySegment != 0) {
                return bySegment;
            }
        }
        // One path is the start of the other: the parent goes first.
        return Integer.compare(a.size(), b.size());
    }
}
