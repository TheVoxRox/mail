package org.voxrox.mailbackend.feature.account;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.jspecify.annotations.Nullable;
import org.voxrox.mailbackend.exception.MailFailureCause;

/**
 * Stable account-level error codes persisted in DB. Localized text is derived
 * at render time from {@link #messageKey()} and stored argument values.
 *
 * <p>
 * The one argument that is not the user's own data is {@value #CAUSE}: a
 * {@link MailFailureCause} name, never the text of the exception it was read
 * from, which is a library's, a server's or a database's and goes to the log
 * only (API surface audit, §3). It is rendered in the reader's language.
 */
public enum AccountLastErrorCode {
    // spotless:off
    OAUTH2_IMAP_ACCESS_DENIED("account.lastError.oauth2ImapAccessDenied"),
    OAUTH2_REFRESH_REJECTED("account.lastError.oauth2RefreshRejected", "provider"),
    MAIL_SYNC_CONNECTION_FAILED("account.lastError.mailSyncConnectionFailed"),
    MAIL_SYNC_AUTH_FAILED("account.lastError.mailSyncAuthFailed"),
    MAIL_SYNC_ACCOUNT_FAILED("account.lastError.mailSyncAccountFailed", "cause"),
    MAIL_SYNC_FOLDER_FAILED("account.lastError.mailSyncFolderFailed", "folder", "cause"),
    SMTP_SEND_FAILED("account.lastError.smtpSendFailed", "cause"),
    DRAFT_SAVE_FAILED("account.lastError.draftSaveFailed", "cause"),
    DRAFT_SEND_FAILED("account.lastError.draftSendFailed", "cause"),
    DRAFT_NOT_FOUND_ON_SERVER("account.lastError.draftNotFoundOnServer"),
    DRAFT_CHANGED_ON_SERVER("account.lastError.draftChangedOnServer");
    // spotless:on

    /**
     * Codes the mail-write pipeline (SMTP send, draft save/send) may have written —
     * and therefore the only codes a successful write may clear. {@code last_error}
     * is a single account-scoped slot shared with the sync pipeline: an
     * unconditional clear would erase a standing sync failure (the user would lose
     * the diagnostic for a persistently failing INBOX just because one e-mail went
     * out). Kept here as the single source of truth so a newly added write-pipeline
     * code cannot be missed by one of the clearing call sites
     * ({@code SmtpMessageService}, {@code DraftPersistenceService}).
     */
    public static final List<String> SEND_PIPELINE_CODES = List.of(SMTP_SEND_FAILED.name(), DRAFT_SAVE_FAILED.name(),
            DRAFT_SEND_FAILED.name(), DRAFT_NOT_FOUND_ON_SERVER.name(), DRAFT_CHANGED_ON_SERVER.name());

    /** The argument that names a {@link MailFailureCause}. */
    public static final String CAUSE = "cause";

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

    /**
     * The template's arguments, in its order. A cause is handed over as the
     * {@link MailFailureCause} itself, which the message source resolves in the
     * reader's locale; one missing or unknown reads as
     * {@link MailFailureCause#UNEXPECTED}.
     */
    public Object[] messageArgs(Map<String, String> args) {
        return argNames.stream()
                .map(name -> CAUSE.equals(name)
                        ? MailFailureCause.fromName(args.get(name)).orElse(MailFailureCause.UNEXPECTED)
                        : args.getOrDefault(name, ""))
                .toArray();
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
