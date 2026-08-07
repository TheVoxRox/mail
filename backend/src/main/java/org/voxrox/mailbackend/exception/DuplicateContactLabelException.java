package org.voxrox.mailbackend.exception;

import org.springframework.http.HttpStatus;

/**
 * The account already has a label with this name, compared case-insensitively.
 * The name is user-authored free text and not personal data, so unlike
 * {@link DuplicateContactException} it is not masked in the internal message —
 * the client needs to see which name collided to fix its form.
 */
public final class DuplicateContactLabelException extends AppException {
    public DuplicateContactLabelException(Long accountId, String name) {
        super(ErrorCode.CONTACT_LABEL_DUPLICATE,
                "Contact label \"" + name + "\" already exists for account " + accountId + ".", HttpStatus.CONFLICT,
                "error.contactLabel.duplicate", accountId, name);
    }
}
