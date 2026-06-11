package org.voxrox.mailbackend.feature.account;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.jspecify.annotations.Nullable;

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
    // List.of() below is immutable; the checker only sees the List interface.
    @SuppressWarnings("ImmutableEnumChecker")
    private final List<String> argNames;

    AccountLastErrorCode(String messageKey, String... argNames) {
        this.messageKey = messageKey;
        this.argNames = List.of(argNames);
    }

    public String messageKey() {
        return messageKey;
    }

    public Object[] messageArgs(Map<String, String> args) {
        return argNames.stream().map(name -> args.getOrDefault(name, "")).toArray();
    }

    public static Optional<AccountLastErrorCode> fromCode(@Nullable String code) {
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
