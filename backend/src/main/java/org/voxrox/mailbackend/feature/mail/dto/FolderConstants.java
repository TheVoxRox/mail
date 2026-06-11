package org.voxrox.mailbackend.feature.mail.dto;

/**
 * "INBOX" is the only folder the current mail provider adapter can treat as a
 * universal entry folder. Other folders differ between providers — for those
 * see {@link FolderRole} and detection in the provider adapter.
 */
public final class FolderConstants {

    public static final String INBOX = "INBOX";

    private FolderConstants() {
    }
}
