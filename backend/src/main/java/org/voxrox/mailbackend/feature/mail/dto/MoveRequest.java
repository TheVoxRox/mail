package org.voxrox.mailbackend.feature.mail.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * {@code folderRef} is the opaque folder reference as returned by {@code GET
 * /api/v1/accounts/{accountId}/folders}. The frontend does not parse the value,
 * it only passes it back to the backend.
 */
public record MoveRequest(
        @Schema(description = "Opaque reference to the target folder obtained from the folder list.", example = "[Gmail]/Archive", requiredMode = Schema.RequiredMode.REQUIRED) @NotBlank(message = "{validation.mail.targetFolderRequired}") @Size(max = 255, message = "{validation.size.max}") String folderRef) {
}
