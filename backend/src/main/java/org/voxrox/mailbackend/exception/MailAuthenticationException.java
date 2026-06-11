package org.voxrox.mailbackend.exception;

import org.springframework.http.HttpStatus;

public final class MailAuthenticationException extends AppException {
    public MailAuthenticationException() {
        super(ErrorCode.MAIL_AUTHENTICATION_FAILED, "Invalid e-mail credentials.", HttpStatus.UNAUTHORIZED,
                "error.mail.authenticationFailed");
    }

    public MailAuthenticationException(Throwable cause) {
        super(ErrorCode.MAIL_AUTHENTICATION_FAILED, "Invalid e-mail credentials.", HttpStatus.UNAUTHORIZED, cause,
                "error.mail.authenticationFailed");
    }
}
