package org.voxrox.mailbackend.feature.account.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record MailServerSettings(
        @NotBlank(message = "{validation.notBlank}") @Size(max = 255, message = "{validation.size.max}") String host,
        @NotNull(message = "{validation.notNull}") @Min(value = 1, message = "{validation.min}") @Max(value = 65535, message = "{validation.max}") Integer port,
        @NotNull(message = "{validation.notNull}") Boolean useSsl) {
}
