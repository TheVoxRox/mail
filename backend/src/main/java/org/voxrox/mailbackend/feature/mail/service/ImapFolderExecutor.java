package org.voxrox.mailbackend.feature.mail.service;

import java.util.List;
import java.util.Locale;

import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.Folder;
import jakarta.mail.MessagingException;
import jakarta.mail.Store;
import jakarta.mail.UIDFolder;
import jakarta.mail.event.MailEvent;

import org.eclipse.angus.mail.imap.IMAPFolder;
import org.eclipse.angus.mail.imap.ResyncData;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.voxrox.mailbackend.exception.ErrorCode;
import org.voxrox.mailbackend.exception.MailOperationException;
import org.voxrox.mailbackend.exception.ResourceNotFoundException;
import org.voxrox.mailbackend.feature.mail.service.ImapConnectionManager.Lane;
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
     *
     * <p>
     * Every caller states its {@link Lane} — there is no default. A wrong lane is
     * not a compile error and shows up only as latency (a user's fetch queued
     * behind a sync, or a sync monopolizing the connection the user's work needs),
     * which is the kind of mistake nothing downstream would catch.
     */
    public <R> @Nullable R executeReadOnly(Long accountId, Lane lane, String folderName, ImapFolderAction<R> action) {
        return execute(accountId, lane, folderName, Folder.READ_ONLY, action);
    }

    /**
     * Runs the action on a folder in read-write mode.
     */
    public <R> @Nullable R executeReadWrite(Long accountId, Lane lane, String folderName, ImapFolderAction<R> action) {
        return execute(accountId, lane, folderName, Folder.READ_WRITE, action);
    }

    /**
     * Read-only variant that asks the server to resynchronize the caller's known
     * window during SELECT (RFC 7162 QRESYNC) instead of leaving the client to
     * enumerate every UID afterwards.
     *
     * <p>
     * The action receives what the SELECT reported: a {@code VANISHED (EARLIER)}
     * response becomes a {@link org.eclipse.angus.mail.imap.MessageVanishedEvent}
     * carrying the expunged UIDs, a flag change becomes a
     * {@link jakarta.mail.event.MessageChangedEvent}. {@code null} means the
     * request was not honoured and the folder was opened plainly — no
     * {@code resync} was supplied, the server does not advertise QRESYNC, or the
     * provider handed back something that is not an {@link IMAPFolder}. An
     * <em>empty</em> list is the opposite statement: the resynchronization ran and
     * nothing changed. Callers must keep the two apart, because "not asked" and
     * "asked, nothing vanished" lead to different cleanup decisions.
     */
    public <R> @Nullable R executeReadOnlyResynced(Long accountId, Lane lane, String folderName,
            @Nullable ResyncRequest resync, ImapResyncFolderAction<R> action) {
        return execute(accountId, lane, folderName, Folder.READ_ONLY, resync, action);
    }

    /**
     * What the caller already mirrors of a folder, in the form the QRESYNC SELECT
     * parameter needs: the {@code uidValidity} the local rows were fetched under,
     * the MODSEQ they are current as of, and the UID range they span.
     *
     * <p>
     * The range is the point of the record. QRESYNC's optional known-uids argument
     * limits both the VANISHED and the FETCH half of the response to UIDs the
     * client cares about (RFC 7162 §3.2.5), and this client mirrors a recency
     * window, not the whole folder — without it a mailbox whose old mail was purged
     * server-side would report thousands of expunged UIDs the client never held.
     */
    public record ResyncRequest(long uidValidity, long modSeq, long minUid, long maxUid) {
    }

    /**
     * Internal method wrapping the logic of acquiring the store, opening the folder
     * and handling errors. The lambda (store) -> { ... } now matches the
     * StoreAction interface in ImapConnectionManager.
     */
    private <R> @Nullable R execute(Long accountId, Lane lane, String folderName, int mode,
            ImapFolderAction<R> action) {
        return execute(accountId, lane, folderName, mode, null,
                (folder, uidFolder, resyncEvents) -> action.apply(folder, uidFolder));
    }

    private <R> @Nullable R execute(Long accountId, Lane lane, String folderName, int mode,
            @Nullable ResyncRequest resync, ImapResyncFolderAction<R> action) {
        return connectionManager.executeWithLock(accountId, lane, store -> {
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

                List<MailEvent> resyncEvents = openFolder(store, folder, mode, resync, folderName);

                if (!(folder instanceof UIDFolder uidFolder)) {
                    throw new MailOperationException(ErrorCode.INTERNAL_ERROR,
                            "Folder " + folderName + " does not support UID operations.");
                }

                return action.apply(folder, uidFolder, resyncEvents);

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

    /**
     * Opens the folder, with QRESYNC resynchronization data when the caller asked
     * for it and the server can serve it. Returns the events the SELECT produced,
     * or {@code null} when the folder was opened plainly — see
     * {@link #executeReadOnlyResynced} for what the two answers mean.
     *
     * <p>
     * The capability is probed here rather than by the caller because the
     * {@link Store} only exists inside the lock this class holds; asking for
     * QRESYNC against a server that does not advertise it is a protocol error, not
     * a graceful degradation. Angus sends the required {@code ENABLE QRESYNC}
     * itself — it has to happen before the mailbox is selected (RFC 5161), which is
     * exactly the window {@link IMAPFolder#open(int, ResyncData)} owns and no
     * caller of ours does.
     */
    private static @Nullable List<MailEvent> openFolder(Store store, Folder folder, int mode,
            @Nullable ResyncRequest resync, String folderName) throws MessagingException {
        String plainReason = plainOpenReason(store, folder, resync);

        if (plainReason == null && resync != null && folder instanceof IMAPFolder imapFolder) {
            try {
                List<MailEvent> events = imapFolder.open(mode,
                        new ResyncData(resync.uidValidity(), resync.modSeq(), resync.minUid(), resync.maxUid()));
                log.debug("{} Opened folder {} with QRESYNC (uidvalidity {}, modseq {}, UIDs {}-{}): {} event(s).",
                        LogCategory.IMAP, folderName, resync.uidValidity(), resync.modSeq(), resync.minUid(),
                        resync.maxUid(), events == null ? 0 : events.size());
                return events == null ? List.of() : events;
            } catch (MessagingException e) {
                /*
                 * Degrade instead of failing the cycle. Advertising QRESYNC and honouring it
                 * are two different things — an intermediary, or a server whose CAPABILITY
                 * outruns its SELECT, can reject the ENABLE or the parameter — and a folder
                 * that can only be opened one way must still be syncable. Without this the
                 * cycle would end in last_error, retry, and fail identically forever: an
                 * account no user could fix.
                 *
                 * Re-opening the same Folder is safe because the failure happened before it
                 * became open: Angus sets `opened` only after SELECT/EXAMINE returns, and both
                 * failure paths release the protocol and throw ahead of that.
                 */
                log.warn("{} QRESYNC open of folder {} failed ({}); opening it plainly instead.", LogCategory.IMAP,
                        folderName, e.getMessage());
            }
        } else {
            log.debug("{} Opening folder {} plainly: {}.", LogCategory.IMAP, folderName, plainReason);
        }

        folder.open(mode);
        return null;
    }

    /**
     * Why this open cannot be a resynchronized one, or {@code null} when it can.
     *
     * <p>
     * It exists for the log line, and the log line exists because the skip was
     * invisible: a server that advertises neither CONDSTORE nor QRESYNC never
     * stores a MODSEQ baseline, so {@code resync} arrives null and the SELECT is
     * opened plainly without a word — which reads exactly like a QRESYNC path that
     * is broken. Telling the two apart on the seznam.cz account took reading
     * `folder_sync_state` out of the database and walking three classes; it should
     * take one line of the log.
     */
    private static @Nullable String plainOpenReason(Store store, Folder folder, @Nullable ResyncRequest resync)
            throws MessagingException {
        if (resync == null) {
            return "the folder has no MODSEQ baseline or no local UID range yet";
        }
        if (!(folder instanceof IMAPFolder)) {
            return "the provider returned a folder that is not an IMAPFolder";
        }
        if (!ImapCapabilities.probe(store).hasQresync()) {
            return "the server does not advertise QRESYNC";
        }
        return null;
    }
}
