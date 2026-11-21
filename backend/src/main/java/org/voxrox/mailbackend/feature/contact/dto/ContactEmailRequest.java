package org.voxrox.mailbackend.feature.contact.dto;

import jakarta.validation.constraints.*;

import org.voxrox.mailbackend.core.validation.EmailAddress;
import org.voxrox.mailbackend.feature.contact.EmailLabel;
import org.voxrox.mailbackend.util.StoredText;

import io.swagger.v3.oas.annotations.media.Schema;

public record ContactEmailRequest(
        @NotBlank(message = "{validation.email.required}") @EmailAddress @Size(max = 255, message = "{validation.size.max}") String email,
        @Schema(description = "Address label (WORK/HOME/OTHER). Optional — send null or omit when no label.", nullable = true, example = "WORK") EmailLabel label) {

    /**
     * The address without the whitespace a paste brings ({@link StoredText#strip}),
     * before {@code @EmailAddress} reads it; the mapper adds the lower case.
     */
    public ContactEmailRequest {
        if (email != null) {
            email = StoredText.strip(email);
        }
    }
}
