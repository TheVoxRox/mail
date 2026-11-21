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
 * The value is an email address by
 * {@link org.voxrox.mailbackend.util.EmailAddresses}, the rule the frontend
 * validates by too. Used where Hibernate's {@code @Email} was: that one
 * accepted addresses the send path refuses ({@code jan@moje_firma.cz}) and
 * refused ones the form let through, so the form, the request and the send each
 * answered differently. {@code null} is valid; {@code @NotBlank} says whether a
 * value is required.
 */
@Documented
@Constraint(validatedBy = EmailAddressValidator.class)
@Target({METHOD, FIELD, ANNOTATION_TYPE, CONSTRUCTOR, PARAMETER, TYPE_USE})
@Retention(RUNTIME)
public @interface EmailAddress {

    String message() default "{validation.email.invalid}";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
