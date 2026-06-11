package org.voxrox.mailbackend.exception;

import org.springframework.http.HttpStatus;

public final class DuplicateContactException extends AppException {
    public DuplicateContactException(Long accountId, String email) {
        super(ErrorCode.CONTACT_DUPLICATE,
                "Contact with e-mail " + email + " already exists for account " + accountId + ".", HttpStatus.CONFLICT,
                "error.contact.duplicate", accountId, email);
    }
}
