package org.voxrox.mailbackend.feature.contact.dto;

import java.util.List;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * Limit of 9 sources = after the merge there can be at most 10 contacts
 * combined (target + sources), matching the per-contact e-mail limit.
 */
public record ContactMergeRequest(
        @NotEmpty(message = "{validation.contactMerge.sourceRequired}") @Size(max = 9, message = "{validation.contactMerge.sourceMax9}") List<@NotNull(message = "{validation.notNull}") @Positive(message = "{validation.positive}") Long> source) {
}
