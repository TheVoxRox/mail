package org.voxrox.mailbackend.feature.mail.service;

import java.util.Locale;

import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.Folder;
import jakarta.mail.MessagingException;
import jakarta.mail.UIDFolder;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.voxrox.mailbackend.exception.ErrorCode;
import org.voxrox.mailbackend.exception.MailOperationException;
import org.voxrox.mailbackend.exception.ResourceNotFoundException;
import org.voxrox.mailbackend.util.LogCategory;

@Component
public class ImapFolderExecutor {
    private static final Logger log = LoggerFactory.getLogger(ImapFolderExecutor.class);
    private final ImapConnectionManager connectionManager;

    public ImapFolderExecutor(ImapConnectionManager connectionManager) {
        this.connectionManager = connectionManager;
    }

    /**
     * Runs the action on a folder in read-only mode.
     */
    public <R> @Nullable R executeReadOnly(Long accountId, String folderName, ImapFolderAction<R> action) {
        return execute(accountId, folderName, Folder.READ_ONLY, action);
    }

    /**
     * Runs the action on a folder in read-write mode.
     */
    public <R> @Nullable R executeReadWrite(Long accountId, String folderName, ImapFolderAction<R> action) {
        return execute(accountId, folderName, Folder.READ_WRITE, action);
    }

    /**
     * Internal method wrapping the logic of acquiring the store, opening the folder
     * and handling errors. The lambda (store) -> { ... } now matches the
     * StoreAction interface in ImapConnectionManager.
     */
    private <R> @Nullable R execute(Long accountId, String folderName, int mode, ImapFolderAction<R> action) {
        return connectionManager.executeWithLock(accountId, store -> {
            Folder folder = null;
            try {
                /*
                 * Open the folder through connectionManager, which guarantees that the Store is
                 * connected.
                 */
                folder = store.getFolder(folderName);

                if (!folder.exists()) {
                    throw new ResourceNotFoundException("Folder '" + folderName + "' was not found on the server.");
                }

                folder.open(mode);

                if (!(folder instanceof UIDFolder uidFolder)) {
                    throw new MailOperationException(ErrorCode.INTERNAL_ERROR,
                            "Folder " + folderName + " does not support UID operations.");
                }

                return action.apply(folder, uidFolder);

            } catch (AuthenticationFailedException e) {
                /*
                 * Let it propagate out of the action lambda so that
                 * ImapConnectionManager.executeWithLock can refresh the OAuth token once and
                 * retry the whole action. After a failed retry ConnectionManager wraps it as
                 * MailConnectionException, which GlobalExceptionHandler translates into HTTP
                 * 503 / the precise error code.
                 */
                throw e;

            } catch (MessagingException e) {
                /*
                 * Log with the full stack trace — the exception is converted into a
                 * MailOperationException below (whose constructor carries no cause), so this is
                 * the last place the original stack is visible.
                 */
                log.error("{} IMAP error in folder {}: {}", LogCategory.IMAP, folderName, e.getMessage(), e);

                /*
                 * Best-effort classification from the server message. JavaMail does not expose
                 * structured IMAP result codes, so this is a substring heuristic — and the
                 * message is provider/locale-dependent and may be null. Guard the null before
                 * matching (mirrors ImapActionService); do not "improve" this by assuming a
                 * localized server reply.
                 */
                String errorMsg = e.getMessage() == null ? "" : e.getMessage().toLowerCase(Locale.ROOT);
                if (errorMsg.contains("not found") || errorMsg.contains("no such folder")) {
                    throw new ResourceNotFoundException("Folder '" + folderName + "' was not found on the server.");
                }

                throw new MailOperationException(ErrorCode.MAIL_CONNECTION_ERROR,
                        "Failed to communicate with the server: " + e.getMessage());

            } catch (ResourceNotFoundException | MailOperationException e) {
                // AppException subtypes — pass through to GlobalExceptionHandler unchanged
                throw e;
            } catch (TransientImapException e) {
                /*
                 * A transient connectivity blip raised by the caller's action (the sync cycle).
                 * Pass it through unchanged — mirroring the AuthenticationFailedException
                 * pass-through above — so the caller's bounded retry loop can reconnect and
                 * retry, instead of it being flattened into a generic MailOperationException
                 * and logged here as an "unexpected" error.
                 */
                throw e;
            } catch (Exception e) {
                log.error("{} Unexpected error: {}", LogCategory.IMAP, e.getMessage(), e);
                throw new MailOperationException(ErrorCode.INTERNAL_ERROR, "Unexpected IMAP error: " + e.getMessage());
            } finally {
                if (folder != null && folder.isOpen()) {
                    try {
                        /*
                         * Always close(boolean), never the inherited no-arg close(). Folder does
                         * implement AutoCloseable, so a try-with-resources compiles here — but
                         * Folder.close() is defined as close(TRUE), i.e. expunge. That would turn every
                         * folder close in this class into a permanent delete of whatever carries
                         * \Deleted, including on the READ_ONLY path where this code asks for no writes
                         * at all. Passing the flag explicitly is the reason this is a try/finally and
                         * not a resource block.
                         */
                        folder.close(mode == Folder.READ_WRITE);
                    } catch (MessagingException e) {
                        log.warn("{} Error while closing folder {}: {}", LogCategory.IMAP, folderName, e.getMessage());
                    }
                }
            }
        });
    }
}
