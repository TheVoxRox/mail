package org.voxrox.mailbackend.feature.contact.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import org.jspecify.annotations.Nullable;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * PUT semantics — the emails list replaces all existing addresses, and so does
 * {@code labelIds}: omitting it (or sending an empty list) clears the contact's
 * labels, it does not keep them. Use PATCH to leave them alone.
 */
public record ContactUpdateRequest(
        @NotEmpty(message = "{validation.contact.emailRequired}") @Size(max = 10, message = "{validation.size.max}") @Valid List<ContactEmailRequest> emails,
        @Size(max = 50, message = "{validation.size.max}") @Schema(description = "Contact labels after the update, by ID. Replace semantics: omitting the field clears all labels.") @Nullable List<@NotNull(message = "{validation.notNull}") @Positive(message = "{validation.positive}") Long> labelIds,
        @Size(max = 255, message = "{validation.size.max}") String name,
        @Size(max = 255, message = "{validation.size.max}") String surname,
        @Size(max = 1000, message = "{validation.size.max}") String note) {
}
