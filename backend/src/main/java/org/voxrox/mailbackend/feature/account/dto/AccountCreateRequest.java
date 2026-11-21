package org.voxrox.mailbackend.feature.account.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import org.jspecify.annotations.Nullable;
import org.voxrox.mailbackend.core.validation.EmailAddress;
import org.voxrox.mailbackend.util.StoredText;

public record AccountCreateRequest(
        @NotBlank(message = "{validation.account.nameRequired}") @Size(max = 100, message = "{validation.account.nameTooLong}") String accountName,

        @Size(max = 100, message = "{validation.size.max}") @Nullable String displayName,

        @NotBlank(message = "{validation.email.required}") @EmailAddress @Size(max = 255, message = "{validation.size.max}") String email,

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

    /**
     * The text as it is stored ({@link StoredText}), before Bean Validation runs,
     * so the validation, the service and the entity all see one value. A required
     * field that is absent stays null for {@code @NotBlank} to name; the password
     * is left as typed, as a space in it is part of it.
     */
    public AccountCreateRequest {
        if (accountName != null) {
            accountName = StoredText.strip(accountName);
        }
        displayName = StoredText.stripToNull(displayName);
        if (email != null) {
            email = StoredText.strip(email);
        }
        if (username != null) {
            username = StoredText.strip(username);
        }
    }

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
