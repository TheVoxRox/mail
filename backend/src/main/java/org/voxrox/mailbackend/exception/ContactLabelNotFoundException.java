package org.voxrox.mailbackend.exception;

import org.springframework.http.HttpStatus;

/**
 * A label ID does not exist within the account. Also covers an ID that exists
 * but belongs to a different account — from the caller's side the two are the
 * same, and saying so would leak that another account owns it.
 */
public final class ContactLabelNotFoundException extends AppException {
    public ContactLabelNotFoundException(Long accountId, Long labelId) {
        super(ErrorCode.CONTACT_LABEL_NOT_FOUND,
                "Contact label with ID " + labelId + " for account " + accountId + " was not found.",
                HttpStatus.NOT_FOUND, "error.contactLabel.notFound", accountId, labelId);
    }
}
