package org.voxrox.mailbackend.feature.contact.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import org.jspecify.annotations.Nullable;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * PATCH semantics — only non-null fields are applied. If {@code emails} is
 * non-null, it replaces the entire list of contact addresses (replace
 * semantics); the same holds for {@code labelIds}, where an explicit empty list
 * clears the labels and omitting the field leaves them untouched.
 */
public record ContactPatchRequest(
        @Size(max = 10, message = "{validation.size.max}") @Valid List<ContactEmailRequest> emails,
        @Size(max = 50, message = "{validation.size.max}") @Schema(description = "Contact labels after the patch, by ID. Replace semantics when present; omit to keep the current labels.") @Nullable List<@NotNull(message = "{validation.notNull}") @Positive(message = "{validation.positive}") Long> labelIds,
        @Size(max = 255, message = "{validation.size.max}") String name,
        @Size(max = 255, message = "{validation.size.max}") String surname,
        @Size(max = 1000, message = "{validation.size.max}") String note) {
}
