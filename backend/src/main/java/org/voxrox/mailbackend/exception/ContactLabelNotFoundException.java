package org.voxrox.mailbackend.exception;

import org.springframework.http.HttpStatus;

/** No label with this ID exists in the address book. */
public final class ContactLabelNotFoundException extends AppException {
    public ContactLabelNotFoundException(Long labelId) {
        super(ErrorCode.CONTACT_LABEL_NOT_FOUND, "Contact label with ID " + labelId + " was not found.",
                HttpStatus.NOT_FOUND, "error.contactLabel.notFound", labelId);
    }
}
