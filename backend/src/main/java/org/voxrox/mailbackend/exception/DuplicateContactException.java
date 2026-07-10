package org.voxrox.mailbackend.exception;

import org.springframework.http.HttpStatus;

import org.voxrox.mailbackend.util.LogMasker;

public final class DuplicateContactException extends AppException {
    public DuplicateContactException(Long accountId, String email) {
        /*
         * The internal message flows into mail.log (GlobalExceptionHandler, bulk
         * per-item results), so the e-mail is masked here at the source; the localized
         * client response resolves from the message key with the raw argument.
         */
        super(ErrorCode.CONTACT_DUPLICATE,
                "Contact with e-mail " + LogMasker.maskEmail(email) + " already exists for account " + accountId + ".",
                HttpStatus.CONFLICT, "error.contact.duplicate", accountId, email);
    }
}
