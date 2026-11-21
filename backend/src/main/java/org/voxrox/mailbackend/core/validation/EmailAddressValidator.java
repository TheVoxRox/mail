package org.voxrox.mailbackend.core.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import org.jspecify.annotations.Nullable;
import org.voxrox.mailbackend.util.EmailAddresses;

/** Checks {@link EmailAddress}. */
public class EmailAddressValidator implements ConstraintValidator<EmailAddress, String> {

    @Override
    public boolean isValid(@Nullable String value, ConstraintValidatorContext context) {
        return value == null || EmailAddresses.isValid(value);
    }
}
