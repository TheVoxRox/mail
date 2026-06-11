package org.voxrox.mailbackend.feature.mail.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * {@code folderRef} is a provider-opaque reference that the client only passes
 * back to the backend when operating on the folder.
 */
public record FolderResponse(String displayName,
        @Schema(description = "Opaque folder reference. The client does not parse it, only passes it back to other mail endpoints.") String folderRef,
        int unreadCount, FolderRole role) {

    public FolderResponse {
        if (unreadCount < 0)
            unreadCount = 0;
        if (displayName == null || displayName.isBlank())
            displayName = "Unnamed folder";
        if (folderRef == null || folderRef.isBlank())
            folderRef = displayName;
        if (role == null)
            role = FolderRole.USER;
    }

    public FolderResponse(String displayName, String folderRef, FolderRole role) {
        this(displayName, folderRef, 0, role);
    }

    public FolderResponse(String displayName, String folderRef, boolean holdsMessages) {
        this(displayName, folderRef, 0, holdsMessages ? FolderRole.USER : FolderRole.ARCHIVE);
    }

}
