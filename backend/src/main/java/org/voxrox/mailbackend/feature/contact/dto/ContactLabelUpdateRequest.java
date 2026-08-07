package org.voxrox.mailbackend.feature.contact.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import io.swagger.v3.oas.annotations.media.Schema;

/** Renames a label. Assignments to contacts are untouched. */
public record ContactLabelUpdateRequest(
        @NotBlank(message = "{validation.contactLabel.nameRequired}") @Size(max = 60, message = "{validation.size.max}") @Schema(example = "Klienti") String name) {
}
