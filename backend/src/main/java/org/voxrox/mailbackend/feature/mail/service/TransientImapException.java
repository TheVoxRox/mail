package org.voxrox.mailbackend.feature.mail.service;

/**
 * Internal marker raised inside a sync action when a {@link TransientMailErrors
 * transient} IMAP connectivity failure occurs. It signals the bounded retry
 * loop in {@link MailSyncService#performFullSyncCycle} to drop the pooled
 * connection, back off and retry the folder cycle instead of recording the
 * failure as a hard {@code last_error}.
 *
 * <p>
 * It is deliberately passed through unchanged by {@link ImapFolderExecutor}
 * (the same way an {@link jakarta.mail.AuthenticationFailedException} is) so it
 * reaches the retry loop rather than being flattened into a generic
 * {@link org.voxrox.mailbackend.exception.MailOperationException}.
 */
class TransientImapException extends RuntimeException {

    private final Throwable originalCause;

    TransientImapException(String folderName, Throwable cause) {
        super("Transient IMAP failure in folder " + folderName + ": "
                + (cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage()), cause);
        this.originalCause = cause;
    }

    /**
     * The transient failure that triggered the retry. Unlike the inherited
     * {@link #getCause()} (typed {@code @Nullable}) this is guaranteed non-null —
     * the exception is only ever constructed around a concrete cause.
     */
    Throwable originalCause() {
        return originalCause;
    }
}
