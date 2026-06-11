package org.voxrox.mailbackend.feature.contact.dto;

import org.voxrox.mailbackend.feature.contact.EmailLabel;

import io.swagger.v3.oas.annotations.media.Schema;

public record ContactEmailResponse(Long id, String email,
        @Schema(description = "Address label. null = the contact did not set one.", nullable = true, example = "HOME") EmailLabel label,
        @Schema(description = "Exactly one e-mail of a contact is primary (is_primary=1). "
                + "Used for display and audit log.") boolean primary) {
}
