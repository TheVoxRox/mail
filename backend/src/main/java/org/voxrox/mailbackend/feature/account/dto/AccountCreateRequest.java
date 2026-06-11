package org.voxrox.mailbackend.feature.account.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

public record AccountCreateRequest(
        @NotBlank(message = "{validation.account.nameRequired}") @Size(max = 100, message = "{validation.account.nameTooLong}") String accountName,

        @Size(max = 100, message = "{validation.size.max}") String displayName,

        @NotBlank(message = "{validation.email.required}") @Email(message = "{validation.email.invalid}") @Size(max = 255, message = "{validation.size.max}") String email,

        /*
         * providerId is optional — either the user picks one from the seeded catalog
         * (Gmail/Seznam/Outlook), or leaves it null and supplies a custom imap/smtp
         * configuration. The cross-field guard is in
         * isProviderOrCustomServerConfigPresent().
         */
        Long providerId,

        @Valid MailServerSettings imap,

        @Valid MailServerSettings smtp,

        @NotBlank(message = "{validation.username.required}") @Size(max = 255, message = "{validation.size.max}") String username,

        @NotBlank(message = "{validation.password.required}")
        /*
         * No @Size(min=8) — accept the password as the user holds it at the provider.
         */
        String password) {

    /*
     * Rules out both "providerId and custom config both null" (the server would
     * have nothing to fill the NOT NULL embedded columns in accounts) and
     * "providerId and custom config both present" (contradictory input, cannot
     * decide which takes precedence). Validation runs after per-field constraints,
     * so if imap != null, its host/port/useSsl have already passed their own
     * annotations.
     */
    @AssertTrue(message = "{validation.account.providerOrCustom}")
    public boolean isProviderOrCustomServerConfigPresent() {
        boolean hasProvider = providerId != null;
        boolean hasCustom = imap != null && smtp != null;
        return hasProvider ^ hasCustom;
    }
}
