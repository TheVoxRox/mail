package org.voxrox.mailbackend.feature.account.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import org.jspecify.annotations.Nullable;
import org.voxrox.mailbackend.core.validation.EmailAddress;
import org.voxrox.mailbackend.util.StoredText;

public record AccountUpdateRequest(
        @NotBlank(message = "{validation.account.nameRequired}") @Size(max = 100, message = "{validation.account.nameTooLong}") String accountName,

        @NotBlank(message = "{validation.email.required}") @EmailAddress @Size(max = 255, message = "{validation.size.max}") String email,

        @Size(max = 100, message = "{validation.size.max}") @Nullable String displayName,

        /*
         * Optional per-account outgoing signature (RFC 3676 "-- " block). Nullable /
         * blank = no signature. Not @NotBlank for that reason; reuses the generic size
         * message rather than a dedicated i18n key.
         */
        @Size(max = 10000, message = "{validation.size.max}") @Nullable String signature,

        /*
         * Whether new-message / mailto compose auto-inserts the signature above.
         * Primitive boolean: PUT is a full replacement, so the client always sends the
         * current toggle state (the form defaults it to true).
         */
        boolean signatureAutoInsert,

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

    /**
     * The text as it is stored ({@link StoredText}), before Bean Validation runs,
     * so the validation, the service and the entity all see one value: the service
     * compares the username with the stored one to tell a credentials change, and a
     * space around it used to read as one. The signature is a block
     * ({@link StoredText#stripBlankLinesToNull}); the password is left as typed.
     */
    public AccountUpdateRequest {
        if (accountName != null) {
            accountName = StoredText.strip(accountName);
        }
        if (email != null) {
            email = StoredText.strip(email);
        }
        displayName = StoredText.stripToNull(displayName);
        signature = StoredText.stripBlankLinesToNull(signature);
        if (username != null) {
            username = StoredText.strip(username);
        }
    }

    @AssertTrue(message = "{validation.account.providerOrCustom}")
    public boolean isProviderOrCustomServerConfigPresent() {
        boolean hasProvider = providerId != null;
        boolean hasCustom = imap != null && smtp != null;
        return hasProvider ^ hasCustom;
    }
}
