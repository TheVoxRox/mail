package org.voxrox.mailbackend.feature.mail.dto;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

public enum FolderRole {
    INBOX("\\Inbox"), SENT("\\Sent"), TRASH("\\Trash"), DRAFTS("\\Drafts"), JUNK("\\Junk"), ARCHIVE("\\Archive"),
    // NEWSLETTERS is non-standard (not in RFC 6154). Seznam.cz exposes it as a
    // folder named "newsletters" (shown as Hromadne in its web UI) and tags it
    // \Junk — name-based detection only.
    NEWSLETTERS(null), USER(null);

    /**
     * Folder names that mean a role when the server does not say so with a
     * SPECIAL-USE attribute, compared whole after {@link #normalize}: case,
     * diacritics and runs of whitespace do not matter, the namespace prefix is
     * dropped, and nothing else is. English and Czech, plus the technical names the
     * providers the app is used with really return: Seznam.cz lists {@code sent},
     * {@code trash}, {@code drafts}, {@code spam} and {@code newsletters};
     * Outlook.com {@code Sent}, {@code Deleted}, {@code Drafts} and {@code Junk}.
     *
     * <p>
     * "Bulk" is spam, not newsletters: it is Yahoo's name for its spam folder,
     * which carries {@code \Junk}.
     */
    private static final Map<FolderRole, List<String>> NAMES_BY_ROLE = namesByRole();

    private static final Map<String, FolderRole> ROLE_BY_NAME = roleByName();

    /**
     * The personal-namespace prefixes a system folder can sit under: Courier and
     * older Dovecot put every folder below INBOX, and Gmail puts its system folders
     * below "[Gmail]" (or "[Google Mail]" in some countries). One level only, so
     * "INBOX.Projects.Sent" stays a user folder.
     */
    private static final List<String> NAMESPACE_PREFIXES = List.of("inbox.", "inbox/", "[gmail]/", "[google mail]/");

    private static final Pattern COMBINING_MARKS = Pattern.compile("\\p{M}+");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private final @Nullable String attribute;

    FolderRole(@Nullable String attribute) {
        this.attribute = attribute;
    }

    /**
     * Whether a download into this folder is mail that has just arrived for the
     * user, as opposed to a mirror of something else.
     *
     * <p>
     * Only {@code INBOX} and {@code NEWSLETTERS} qualify. A sync pass downloads
     * SENT, DRAFTS, JUNK and TRASH too, and counting those as "new messages" makes
     * the count say something it does not mean: the first pass over a fresh account
     * mirrors the whole window of every folder, and three messages sent from a
     * phone would otherwise be reported as three new ones.
     */
    public boolean deliversNewMail() {
        return this == INBOX || this == NEWSLETTERS;
    }

    public static FolderRole fromAttribute(String attr) {
        return Arrays.stream(values()).filter(role -> role.attribute != null && role.attribute.equalsIgnoreCase(attr))
                .findFirst().orElse(USER);
    }

    /**
     * The role a folder's name alone implies, or {@link #USER}.
     *
     * <p>
     * The whole name has to be a known system folder name. It used to be enough for
     * the name to contain one, and a role decides what happens to mail: a folder
     * the user called "Robinson" contained "bin" and became the trash when the
     * server marked none, so deleting a message moved it there, and deleting one of
     * the folder's own messages expunged it for good. "Presentations" was Sent,
     * "Archiv 2024" Archive and "Newsletter from Dr Novak" Newsletters.
     */
    public static FolderRole fromNameFallback(@Nullable String name) {
        if (name == null) {
            return USER;
        }
        return ROLE_BY_NAME.getOrDefault(normalize(name), USER);
    }

    /**
     * Lower case without diacritics, whitespace collapsed, and one namespace prefix
     * removed: "INBOX.Odeslane Polozky" becomes "odeslane polozky".
     */
    private static String normalize(String name) {
        String withoutMarks = COMBINING_MARKS.matcher(Normalizer.normalize(name, Normalizer.Form.NFD)).replaceAll("");
        String folded = WHITESPACE.matcher(withoutMarks.strip()).replaceAll(" ").toLowerCase(Locale.ROOT);
        for (String prefix : NAMESPACE_PREFIXES) {
            if (folded.startsWith(prefix)) {
                return folded.substring(prefix.length());
            }
        }
        return folded;
    }

    private static Map<FolderRole, List<String>> namesByRole() {
        Map<FolderRole, List<String>> names = new EnumMap<>(FolderRole.class);
        names.put(SENT, List.of("sent", "sent items", "sent mail", "sent messages", "odeslane", "odeslana posta",
                "odeslane polozky"));
        names.put(TRASH, List.of("trash", "deleted", "deleted items", "deleted messages", "bin", "kos", "smazane",
                "smazane polozky", "odstranena posta", "odstranene polozky"));
        names.put(DRAFTS, List.of("drafts", "draft", "koncepty", "rozepsane"));
        names.put(JUNK, List.of("spam", "junk", "junk e-mail", "junk email", "junk mail", "bulk", "bulk mail",
                "nevyzadana posta"));
        names.put(ARCHIVE, List.of("archive", "archives", "archiv"));
        names.put(NEWSLETTERS, List.of("newsletters", "newsletter", "hromadne", "hromadna posta"));
        return names;
    }

    private static Map<String, FolderRole> roleByName() {
        Map<String, FolderRole> byName = new HashMap<>();
        NAMES_BY_ROLE.forEach((role, names) -> names.forEach(name -> {
            FolderRole previous = byName.put(name, role);
            if (previous != null) {
                throw new IllegalStateException(
                        "Folder name '" + name + "' is listed for both " + previous + " and " + role + ".");
            }
        }));
        return Map.copyOf(byName);
    }
}
