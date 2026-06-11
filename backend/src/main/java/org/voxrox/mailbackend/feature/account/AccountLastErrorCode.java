package org.voxrox.mailbackend.feature.account;

import java.util.Arrays;
import java.util.Map;
import java.util.Optional;

/**
 * Stable account-level error codes persisted in DB. Localized text is derived
 * at render time from {@link #messageKey()} and stored argument values.
 */
public enum AccountLastErrorCode {
    // spotless:off
    OAUTH2_IMAP_ACCESS_DENIED("account.lastError.oauth2ImapAccessDenied"),
    OAUTH2_REFRESH_REJECTED("account.lastError.oauth2RefreshRejected", "provider"),
    MAIL_SYNC_ACCOUNT_FAILED("account.lastError.mailSyncAccountFailed", "errorClass", "detail"),
    MAIL_SYNC_FOLDER_FAILED("account.lastError.mailSyncFolderFailed", "folder", "errorClass", "detail"),
    SMTP_SEND_FAILED("account.lastError.smtpSendFailed", "detail"),
    DRAFT_SAVE_FAILED("account.lastError.draftSaveFailed", "detail"),
    DRAFT_SEND_FAILED("account.lastError.draftSendFailed", "detail"),
    DRAFT_NOT_FOUND_ON_SERVER("account.lastError.draftNotFoundOnServer");
    // spotless:on

    private final String messageKey;
    private final String[] argNames;

    AccountLastErrorCode(String messageKey, String... argNames) {
        this.messageKey = messageKey;
        this.argNames = argNames.clone();
    }

    public String messageKey() {
        return messageKey;
    }

    public Object[] messageArgs(Map<String, String> args) {
        return Arrays.stream(argNames).map(name -> args.getOrDefault(name, "")).toArray();
    }

    public static Optional<AccountLastErrorCode> fromCode(String code) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(AccountLastErrorCode.valueOf(code));
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }
}
