package org.voxrox.mailbackend.core.validation;

import static java.lang.annotation.ElementType.ANNOTATION_TYPE;
import static java.lang.annotation.ElementType.CONSTRUCTOR;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.ElementType.TYPE_USE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

/**
 * The value is a recipient field every token of which is an email address, read
 * the way the send path reads it
 * ({@link org.voxrox.mailbackend.util.HeaderAddresses#parseAllValid}). On a
 * send request it answers before the send starts: the send itself runs in the
 * background after the SMTP connection is open, where a mistyped recipient used
 * to fail as an unexpected error and mark the account as failing. A blank value
 * is valid; {@code @NotBlank} says whether one is required.
 */
@Documented
@Constraint(validatedBy = EmailAddressListValidator.class)
@Target({METHOD, FIELD, ANNOTATION_TYPE, CONSTRUCTOR, PARAMETER, TYPE_USE})
@Retention(RUNTIME)
public @interface EmailAddressList {

    String message() default "{validation.mail.recipientInvalid}";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
