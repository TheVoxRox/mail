package org.voxrox.mailbackend.feature.contact.dto;

import java.util.List;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record BulkContactDeleteRequest(
        @NotEmpty(message = "{validation.ids.listRequired}") @Size(max = 100, message = "{validation.ids.max100}") List<@NotNull(message = "{validation.notNull}") @Positive(message = "{validation.positive}") Long> ids) {
}
