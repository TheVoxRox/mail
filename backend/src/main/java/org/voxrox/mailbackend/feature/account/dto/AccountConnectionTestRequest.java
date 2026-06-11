package org.voxrox.mailbackend.feature.account.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record AccountConnectionTestRequest(@Positive(message = "{validation.positive}") Long accountId,

        @NotBlank(message = "{validation.email.required}") @Email(message = "{validation.email.invalid}") @Size(max = 255, message = "{validation.size.max}") String email,

        Long providerId,

        @Valid MailServerSettings imap,

        @Valid MailServerSettings smtp,

        @NotBlank(message = "{validation.username.required}") @Size(max = 255, message = "{validation.size.max}") String username,

        String password) {

    @AssertTrue(message = "{validation.account.providerOrCustom}")
    public boolean isProviderOrCustomServerConfigPresent() {
        boolean hasProvider = providerId != null;
        boolean hasCustom = imap != null && smtp != null;
        return hasProvider ^ hasCustom;
    }

    @AssertTrue(message = "{validation.account.testPasswordRequired}")
    public boolean isPasswordPresentForNewAccount() {
        return accountId != null || (password != null && !password.isBlank());
    }
}
