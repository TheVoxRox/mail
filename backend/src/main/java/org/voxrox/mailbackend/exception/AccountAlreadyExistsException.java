package org.voxrox.mailbackend.exception;

import org.springframework.http.HttpStatus;

import org.voxrox.mailbackend.util.LogMasker;

public final class AccountAlreadyExistsException extends AppException {
    public AccountAlreadyExistsException(String email) {
        /*
         * The internal message flows into mail.log (GlobalExceptionHandler), so the
         * e-mail is masked here at the source; the localized client response resolves
         * from the message key with the raw argument.
         */
        super(ErrorCode.ACCOUNT_ALREADY_EXISTS,
                "An account with e-mail " + LogMasker.maskEmail(email) + " already exists.", HttpStatus.CONFLICT,
                "error.account.alreadyExists", email);
    }
}
