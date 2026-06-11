package org.voxrox.mailbackend.exception;

import org.springframework.http.HttpStatus;

public final class AccountAlreadyExistsException extends AppException {
    public AccountAlreadyExistsException(String email) {
        super(ErrorCode.ACCOUNT_ALREADY_EXISTS, "An account with e-mail " + email + " already exists.",
                HttpStatus.CONFLICT, "error.account.alreadyExists", email);
    }
}
