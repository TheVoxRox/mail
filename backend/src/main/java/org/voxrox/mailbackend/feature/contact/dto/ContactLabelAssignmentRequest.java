package org.voxrox.mailbackend.feature.contact.dto;

import java.util.List;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import org.jspecify.annotations.Nullable;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Bulk label assignment over a contact selection. One request carries the whole
 * outcome of the "assign labels" dialog — every label the user ticked and every
 * one they unticked — so toggling five labels is one round trip and one
 * transaction, not five.
 * <p>
 * Both label lists are optional; sending neither is a validation error (the
 * request would be a no-op). A label already present on a contact, or absent
 * from one being removed, is silently fine — the operation is idempotent, which
 * is what makes it safe for a UI that does not track per-contact state.
 */
public record ContactLabelAssignmentRequest(
        @NotEmpty(message = "{validation.ids.listRequired}") @Size(max = 100, message = "{validation.ids.max100}") List<@NotNull(message = "{validation.notNull}") @Positive(message = "{validation.positive}") Long> contactIds,
        @Size(max = 50, message = "{validation.size.max}") @Schema(description = "Labels to add. Omit or send an empty list to only remove.") @Nullable List<@NotNull(message = "{validation.notNull}") @Positive(message = "{validation.positive}") Long> addLabelIds,
        @Size(max = 50, message = "{validation.size.max}") @Schema(description = "Labels to remove. Omit or send an empty list to only add.") @Nullable List<@NotNull(message = "{validation.notNull}") @Positive(message = "{validation.positive}") Long> removeLabelIds) {
}
