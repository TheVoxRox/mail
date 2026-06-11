package org.voxrox.mailbackend.feature.contact.dto;

import jakarta.validation.constraints.*;

import org.voxrox.mailbackend.feature.contact.EmailLabel;

import io.swagger.v3.oas.annotations.media.Schema;

public record ContactEmailRequest(
        @NotBlank(message = "{validation.email.required}") @Email(message = "{validation.email.invalid}") @Size(max = 255, message = "{validation.size.max}") String email,
        @Schema(description = "Address label (WORK/HOME/OTHER). Optional — send null or omit when no label.", nullable = true, example = "WORK") EmailLabel label) {
}
