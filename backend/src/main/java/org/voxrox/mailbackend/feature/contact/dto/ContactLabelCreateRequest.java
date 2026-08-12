package org.voxrox.mailbackend.feature.contact.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Creates a label. The name is trimmed and compared case-insensitively against
 * the existing labels — a collision is a 409, not a silent reuse, so the client
 * can tell "created" from "already there".
 */
public record ContactLabelCreateRequest(
        @NotBlank(message = "{validation.contactLabel.nameRequired}") @Size(max = 60, message = "{validation.size.max}") @Schema(example = "Rodina") String name) {
}
