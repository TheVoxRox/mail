package org.voxrox.mailbackend.core.validation;

import jakarta.mail.internet.AddressException;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import org.jspecify.annotations.Nullable;
import org.voxrox.mailbackend.util.HeaderAddresses;

/** Checks {@link EmailAddressList}. */
public class EmailAddressListValidator implements ConstraintValidator<EmailAddressList, String> {

    @Override
    public boolean isValid(@Nullable String value, ConstraintValidatorContext context) {
        if (value == null || value.isBlank()) {
            return true;
        }
        try {
            HeaderAddresses.parseAllValid(value);
            return true;
        } catch (AddressException e) {
            return false;
        }
    }
}
