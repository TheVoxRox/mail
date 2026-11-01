package org.voxrox.mailbackend.feature.mail.dto;

import org.jspecify.annotations.Nullable;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * {@code folderRef} is a provider-opaque reference that the client only passes
 * back to the backend when operating on the folder. {@code parentRef} is the
 * {@code folderRef} of another folder in the same list, so the client can show
 * the hierarchy without parsing either of them.
 */
public record FolderResponse(String displayName,
        @Schema(description = "Opaque folder reference. The client does not parse it, only passes it back to other mail endpoints.") String folderRef,
        int unreadCount, FolderRole role,
        @Schema(nullable = true, description = "folderRef of the folder this one is shown under, always one in the same list. "
                + "Null for a top-level folder, which includes a folder whose parent cannot hold messages and is not listed.") @Nullable String parentRef) {

    public FolderResponse {
        if (unreadCount < 0)
            unreadCount = 0;
        if (displayName == null || displayName.isBlank())
            displayName = "Unnamed folder";
        if (folderRef == null || folderRef.isBlank())
            folderRef = displayName;
        if (role == null)
            role = FolderRole.USER;
        if (parentRef != null && (parentRef.isBlank() || parentRef.equals(folderRef)))
            parentRef = null;
    }

    /** A top-level folder. */
    public FolderResponse(String displayName, String folderRef, int unreadCount, FolderRole role) {
        this(displayName, folderRef, unreadCount, role, null);
    }

    public FolderResponse(String displayName, String folderRef, FolderRole role) {
        this(displayName, folderRef, 0, role);
    }

    public FolderResponse(String displayName, String folderRef, boolean holdsMessages) {
        this(displayName, folderRef, 0, holdsMessages ? FolderRole.USER : FolderRole.ARCHIVE);
    }

}
