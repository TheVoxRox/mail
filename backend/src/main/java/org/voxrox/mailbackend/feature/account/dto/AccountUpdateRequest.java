package org.voxrox.mailbackend.feature.account.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

public record AccountUpdateRequest(
        @NotBlank(message = "{validation.account.nameRequired}") @Size(max = 100, message = "{validation.account.nameTooLong}") String accountName,

        @NotBlank(message = "{validation.email.required}") @Email(message = "{validation.email.invalid}") @Size(max = 255, message = "{validation.size.max}") String email,

        @Size(max = 100, message = "{validation.size.max}") String displayName,

        /*
         * Optional per-account outgoing signature (RFC 3676 "-- " block). Nullable /
         * blank = no signature. Not @NotBlank for that reason; reuses the generic size
         * message rather than a dedicated i18n key.
         */
        @Size(max = 10000, message = "{validation.size.max}") String signature,

        /*
         * providerId is optional — either the user picks one from the seeded catalog,
         * or supplies a custom imap/smtp. The cross-field guard is in
         * isProviderOrCustomServerConfigPresent().
         */
        Long providerId,

        @Valid MailServerSettings imap,

        @Valid MailServerSettings smtp,

        @NotBlank(message = "{validation.username.required}") @Size(max = 255, message = "{validation.size.max}") String username,

        /*
         * Password stays optional — if null/empty, the service does not change it in
         * the DB. That is why there is NO @NotBlank here.
         */
        String password,

        boolean active) {

    @AssertTrue(message = "{validation.account.providerOrCustom}")
    public boolean isProviderOrCustomServerConfigPresent() {
        boolean hasProvider = providerId != null;
        boolean hasCustom = imap != null && smtp != null;
        return hasProvider ^ hasCustom;
    }
}
