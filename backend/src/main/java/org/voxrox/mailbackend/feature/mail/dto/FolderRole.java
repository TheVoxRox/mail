package org.voxrox.mailbackend.feature.mail.dto;

import java.util.Arrays;
import java.util.Locale;

import org.jspecify.annotations.Nullable;

public enum FolderRole {
    INBOX("\\Inbox"), SENT("\\Sent"), TRASH("\\Trash"), DRAFTS("\\Drafts"), JUNK("\\Junk"), ARCHIVE("\\Archive"),
    // NEWSLETTERS is non-standard (not in RFC 6154). Seznam.cz exposes a "Bulk"
    // folder for newsletters / Hromadné — name-based detection only.
    NEWSLETTERS(null), USER(null);

    private final @Nullable String attribute;

    FolderRole(@Nullable String attribute) {
        this.attribute = attribute;
    }

    public static FolderRole fromAttribute(String attr) {
        return Arrays.stream(values()).filter(role -> role.attribute != null && role.attribute.equalsIgnoreCase(attr))
                .findFirst().orElse(USER);
    }

    public static FolderRole fromNameFallback(String name) {
        if (name == null)
            return USER;
        String lower = name.toLowerCase(Locale.ROOT);
        return switch (lower) {
            case String s when s.contains("sent") || s.contains("odeslan") -> SENT;
            case String s when s.contains("trash") || s.contains("koš") || s.contains("bin") || s.contains("smazan") ->
                TRASH;
            case String s when s.contains("draft") || s.contains("rozeps") -> DRAFTS;
            case String s when s.contains("spam") || s.contains("junk") -> JUNK;
            case String s when s.contains("archive") || s.contains("archiv") -> ARCHIVE;
            case String s when s.contains("bulk") || s.contains("hromadn") || s.contains("newsletter") -> NEWSLETTERS;
            default -> USER;
        };
    }
}
