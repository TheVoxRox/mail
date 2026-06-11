package org.voxrox.mailbackend.exception;

/**
 * Central registry of all technical error codes in the application. The
 * frontend uses these codes to identify the error type regardless of the
 * message text.
 */
public enum ErrorCode {
    // Accounts
    ACCOUNT_NOT_FOUND, ACCOUNT_ALREADY_EXISTS,

    // Contacts
    CONTACT_NOT_FOUND, CONTACT_DUPLICATE,

    // E-mail protocols
    MAIL_CONNECTION_ERROR, MAIL_AUTHENTICATION_FAILED, MAIL_OAUTH2_IMAP_ACCESS_DENIED, MAIL_ACCOUNT_REQUIRES_REAUTH, PROVIDER_NOT_FOUND, RESOURCE_NOT_FOUND, FOLDER_ROLE_NOT_FOUND,

    // Generic and validation
    VALIDATION_ERROR, BAD_REQUEST, INTERNAL_ERROR
}
