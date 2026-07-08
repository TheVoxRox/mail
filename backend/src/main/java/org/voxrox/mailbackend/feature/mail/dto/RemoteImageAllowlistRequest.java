package org.voxrox.mailbackend.feature.mail.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Adds a sender to the per-account remote-image allow-list. See
 * docs/CONTENT_RENDERING_AUDIT.md finding F2.
 */
public record RemoteImageAllowlistRequest(
        @Schema(description = "Account the allow decision belongs to.", example = "1", requiredMode = Schema.RequiredMode.REQUIRED) @NotNull(message = "{validation.notBlank}") @Positive(message = "{validation.positive}") Long accountId,

        @Schema(description = "Bare email address of the sender to trust, as returned by the content endpoint.", example = "newsletter@example.com", requiredMode = Schema.RequiredMode.REQUIRED) @NotBlank(message = "{validation.email.required}") @Email(message = "{validation.email.invalid}") @Size(max = 255, message = "{validation.size.max}") String senderEmail) {
}
