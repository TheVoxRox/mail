package org.voxrox.mailbackend.exception;

import org.springframework.http.HttpStatus;

public final class ContactNotFoundException extends AppException {
    public ContactNotFoundException(Long contactId) {
        super(ErrorCode.CONTACT_NOT_FOUND, "Contact with ID " + contactId + " was not found.", HttpStatus.NOT_FOUND,
                "error.contact.notFound", contactId);
    }
}
