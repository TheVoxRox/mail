package org.voxrox.mailbackend.feature.mail.service;

import java.util.Locale;

import jakarta.mail.*;

import org.eclipse.angus.mail.imap.IMAPFolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.voxrox.mailbackend.core.metrics.MailMetrics;
import org.voxrox.mailbackend.feature.mail.dto.MessageFlag;
import org.voxrox.mailbackend.util.AuditLog;
import org.voxrox.mailbackend.util.LogCategory;

@Service
public class ImapActionService {
    private static final Logger log = LoggerFactory.getLogger(ImapActionService.class);

    private final ImapFolderExecutor folderExecutor;
    private final ImapConnectionManager connectionManager;
    private final MailMetrics metrics;

    public ImapActionService(ImapFolderExecutor folderExecutor, ImapConnectionManager connectionManager,
            MailMetrics metrics) {
        this.folderExecutor = folderExecutor;
        this.connectionManager = connectionManager;
        this.metrics = metrics;
    }

    /**
     * Asynchronously moves a message between folders on the server.
     * <p>
     * Three strategies in order of preference:
     * <ol>
     * <li><b>RFC 6851 IMAP MOVE</b> — atomic server-side move via
     * {@link IMAPFolder#moveMessages(Message[], Folder)}. Requires the server
     * CAPABILITY {@code MOVE}; failure surfaces as {@link MessagingException} with
     * {@code "MOVE not supported"} → fallback below.</li>
     * <li><b>UID EXPUNGE fallback</b> — when MOVE is not available, use classic
     * COPY + STORE \Deleted + {@link IMAPFolder#expunge(Message[])}. The latter is
     * RFC 4315 UID EXPUNGE: the server expunges only the specified message, leaving
     * foreign {@code \Deleted} flags in other sessions untouched.</li>
     * <li><b>Generic fallback</b> — JavaMail provider other than Angus: COPY +
     * STORE \Deleted + {@code folder.expunge()} (removes all DELETED messages in
     * the folder — acceptable for our single-tenant desktop scenario).</li>
     * </ol>
     */
    @Async("userMailExecutor")
    public void moveOnServerAsync(Long accountId, String sourceFolder, String targetFolder, long uid) {
        try {
            folderExecutor.executeReadWrite(accountId, sourceFolder, (src, uidFolderSrc) -> {
                Folder dest = null;
                try {
                    log.info("{} Moving UID {} from {} to {}", LogCategory.IMAP, uid, sourceFolder, targetFolder);

                    dest = connectionManager.openFolder(accountId, targetFolder, Folder.READ_WRITE);

                    Message msg = uidFolderSrc.getMessageByUID(uid);
                    if (msg == null) {
                        log.warn("{} moveOnServerAsync: UID {} not found in folder {}, move skipped.", LogCategory.IMAP,
                                uid, sourceFolder);
                        metrics.recordMove(MailMetrics.OUTCOME_FAILURE);
                        return null;
                    }

                    performMove(src, dest, msg, sourceFolder, targetFolder, uid);
                    metrics.recordMove(MailMetrics.OUTCOME_SUCCESS);
                } catch (MessagingException e) {
                    log.error("{} Error moving UID {}: {}", LogCategory.IMAP, uid, e.getMessage());
                    AuditLog.failure("imap_move", "account=" + accountId,
                            "uid=" + uid + " " + e.getClass().getSimpleName());
                    metrics.recordMove(MailMetrics.OUTCOME_FAILURE);
                } finally {
                    if (dest != null && dest.isOpen()) {
                        try {
                            dest.close(false);
                        } catch (MessagingException e) {
                            log.debug("{} Closing the move destination folder failed: {}", LogCategory.IMAP,
                                    e.getMessage());
                        }
                    }
                }
                return null;
            });
        } catch (Exception e) {
            log.error("{} Async move of UID {} from {} to {} failed: {}", LogCategory.IMAP, uid, sourceFolder,
                    targetFolder, e.getMessage(), e);
            AuditLog.failure("imap_move", "account=" + accountId, "uid=" + uid + " " + e.getClass().getSimpleName());
            metrics.recordMove(MailMetrics.OUTCOME_FAILURE);
        }
    }

    private void performMove(Folder src, Folder dest, Message msg, String sourceFolder, String targetFolder, long uid)
            throws MessagingException {
        Message[] msgs = new Message[]{msg};

        // 1) Attempt atomic RFC 6851 MOVE
        if (src instanceof IMAPFolder imapSrc) {
            try {
                imapSrc.moveMessages(msgs, dest);
                log.info("{} Move of UID {} from {} to {} completed via IMAP MOVE.", LogCategory.IMAP, uid,
                        sourceFolder, targetFolder);
                return;
            } catch (MessagingException e) {
                // Substring heuristic: JavaMail exposes no structured IMAP code, so we detect
                // "MOVE not supported" from the (nullable, provider-dependent) message. Any
                // other error is a real failure and must propagate.
                String m = e.getMessage();
                if (m == null || !m.toUpperCase(Locale.ROOT).contains("MOVE")) {
                    throw e; // different IMAP error — propagate
                }
                log.debug("{} Server does not support MOVE ({}), falling back to COPY+EXPUNGE.", LogCategory.IMAP, m);
            }
        }

        // 2) Fallback: COPY + STORE \Deleted + (UID) EXPUNGE
        src.copyMessages(msgs, dest);
        msg.setFlag(Flags.Flag.DELETED, true);
        if (src instanceof IMAPFolder imapSrc) {
            // RFC 4315 UID EXPUNGE — expunges only this message, leaves foreign \Deleted
            // untouched.
            imapSrc.expunge(msgs);
            log.info("{} Move of UID {} from {} to {} completed (COPY + UID EXPUNGE).", LogCategory.IMAP, uid,
                    sourceFolder, targetFolder);
        } else {
            src.expunge();
            log.info("{} Move of UID {} from {} to {} completed (COPY + EXPUNGE).", LogCategory.IMAP, uid, sourceFolder,
                    targetFolder);
        }
    }

    /**
     * Synchronously hard-deletes a message from the server — sets {@code \Deleted}
     * and immediately performs {@code EXPUNGE}. Used for the previous draft
     * revision during {@code POST /drafts?replaces=...} and for the sent draft in
     * {@link SmtpMessageService#sendDraftAsync}, where we do not want the trash
     * folder cluttered with working versions.
     * <p>
     * The operation is <b>idempotent</b>: its postcondition is "the message is
     * absent from {@code folderName}". When the UID is already gone the goal is
     * already met, so this is a no-op success — logged at DEBUG, not WARN. A draft
     * can legitimately be removed by a concurrent cleanup path before this runs:
     * the send handler issues a best-effort move-to-trash of the draft
     * ({@code MailFacade.deleteMessages}) that races the autosave {@code replaces}
     * hard-delete of the same revision, and on an eventually-consistent async
     * pipeline either may win. Idempotency keeps that race correct instead of
     * trying to serialise the two paths.
     */
    public void hardDelete(Long accountId, String folderName, long uid) {
        folderExecutor.executeReadWrite(accountId, folderName, (folder, uidFolder) -> {
            try {
                Message msg = uidFolder.getMessageByUID(uid);
                if (msg == null) {
                    // Already gone — a concurrent cleanup path expunged or moved it first.
                    // The desired end state already holds, so this is a no-op, not an anomaly.
                    log.debug("{} hardDelete: UID {} already absent from folder {}, nothing to do.", LogCategory.IMAP,
                            uid, folderName);
                    return null;
                }
                msg.setFlag(Flags.Flag.DELETED, true);
                folder.expunge();
                log.info("{} Hard delete of UID {} from folder {} completed.", LogCategory.IMAP, uid, folderName);
            } catch (MessagingException e) {
                log.error("{} Error during hard delete of UID {} from folder {}: {}", LogCategory.IMAP, uid, folderName,
                        e.getMessage());
            }
            return null;
        });
    }

    /**
     * Asynchronously updates flags on the server.
     * <p>
     * The domain {@link MessageFlag} is mapped to {@link Flags.Flag} here, in the
     * IMAP adapter layer — the caller (Facade, controller) does not know the
     * JavaMail type.
     */
    @Async("userMailExecutor")
    public void updateFlagsOnServerAsync(Long accountId, String folderName, long uid, MessageFlag flag, boolean value) {
        Flags.Flag imapFlag = toJavaMailFlag(flag);
        try {
            folderExecutor.executeReadWrite(accountId, folderName, (folder, uidFolder) -> {
                Message msg = uidFolder.getMessageByUID(uid);
                if (msg != null) {
                    msg.setFlag(imapFlag, value);
                    log.debug("{} Flag {}={} written for UID {}", LogCategory.IMAP, flag, value, uid);
                }
                return null;
            });
        } catch (Exception e) {
            log.error("{} Async flag write {}={} for UID {} in folder {} failed: {}", LogCategory.IMAP, flag, value,
                    uid, folderName, e.getMessage(), e);
            AuditLog.failure("imap_flag_update", "account=" + accountId,
                    "folder=" + folderName + " uid=" + uid + " " + e.getClass().getSimpleName());
        }
    }

    private static Flags.Flag toJavaMailFlag(MessageFlag flag) {
        return switch (flag) {
            case SEEN -> Flags.Flag.SEEN;
            case FLAGGED -> Flags.Flag.FLAGGED;
            case ANSWERED -> Flags.Flag.ANSWERED;
        };
    }
}
