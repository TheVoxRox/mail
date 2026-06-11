package org.voxrox.mailbackend.exception;

import org.springframework.http.HttpStatus;

public final class ContactNotFoundException extends AppException {
    public ContactNotFoundException(Long accountId, Long contactId) {
        super(ErrorCode.CONTACT_NOT_FOUND,
                "Contact with ID " + contactId + " for account " + accountId + " was not found.", HttpStatus.NOT_FOUND,
                "error.contact.notFound", accountId, contactId);
    }
}
