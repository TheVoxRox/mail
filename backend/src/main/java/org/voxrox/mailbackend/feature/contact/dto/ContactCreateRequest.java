package org.voxrox.mailbackend.feature.contact.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import org.jspecify.annotations.Nullable;

import io.swagger.v3.oas.annotations.media.Schema;

public record ContactCreateRequest(
        @NotEmpty(message = "{validation.contact.emailRequired}") @Size(max = 10, message = "{validation.size.max}") @Valid List<ContactEmailRequest> emails,
        @Size(max = 50, message = "{validation.size.max}") @Schema(description = "Contact labels to attach, by ID. Omit or send an empty list for no labels; every ID must belong to the same account.") @Nullable List<@NotNull(message = "{validation.notNull}") @Positive(message = "{validation.positive}") Long> labelIds,
        @Size(max = 255, message = "{validation.size.max}") String name,
        @Size(max = 255, message = "{validation.size.max}") String surname,
        @Size(max = 1000, message = "{validation.size.max}") String note) {
}
