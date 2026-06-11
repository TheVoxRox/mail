package org.voxrox.mailbackend.exception;

import org.springframework.http.HttpStatus;

public final class AccountNotFoundException extends AppException {
    public AccountNotFoundException(Long id) {
        super(ErrorCode.ACCOUNT_NOT_FOUND, "E-mail account with ID " + id + " was not found.", HttpStatus.NOT_FOUND,
                "error.account.notFound", id);
    }
}
