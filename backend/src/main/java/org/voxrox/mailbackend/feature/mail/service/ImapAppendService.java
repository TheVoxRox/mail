package org.voxrox.mailbackend.feature.mail.service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Optional;

import jakarta.mail.Flags;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;

import org.eclipse.angus.mail.imap.AppendUID;
import org.eclipse.angus.mail.imap.IMAPFolder;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.voxrox.mailbackend.feature.mail.dto.FolderRole;
import org.voxrox.mailbackend.util.LogCategory;

/**
 * IMAP write/read operations against a specific folder role (SENT, DRAFTS).
 * Used to archive a sent message and a draft post-SMTP, and to fetch raw MIME
 * for the send-draft pipeline.
 *
 * <p>
 * Best-effort contract: {@link #appendByRole} never throws — it returns
 * {@code false} on any failure instead. For the Sent archive a failed append is
 * tolerated because a false 500 on an already-delivered message would be worse;
 * note this leaves NO server-side copy of the sent mail (sync cannot reconcile
 * a copy that was never appended), so callers should at least log it. On
 * providers that auto-file SMTP-submitted mail into Sent (e.g. Gmail) the copy
 * still exists regardless. For a draft <em>replace</em> the caller MUST gate
 * the delete of the previous revision on a {@code true} return — otherwise a
 * failed append would destroy the only remaining copy of the user's content.
 *
 * <p>
 * The Sent append is deliberately NOT gated per provider. Auto-filing providers
 * would suggest a duplicate, but Gmail collapses our append into its own copy
 * by Message-ID, so gating would guard a problem that does not exist. Measured
 * live on 2026-07-14 against a real Gmail account: both the direct send and the
 * draft-send path left exactly one copy in Sent, with no append failure in the
 * log (a failure would WARN below). Re-check before claiming the same for a
 * provider that auto-files but does not dedupe.
 */
@Component
public class ImapAppendService {

    private static final Logger log = LoggerFactory.getLogger(ImapAppendService.class);

    private final ImapConnectionManager imapConnectionManager;
    private final ImapFolderService imapFolderService;

    public ImapAppendService(ImapConnectionManager imapConnectionManager, ImapFolderService imapFolderService) {
        this.imapConnectionManager = imapConnectionManager;
        this.imapFolderService = imapFolderService;
    }

    /**
     * Appends a message to the folder matching the given role (SENT for sent mail,
     * DRAFTS for drafts). Best-effort: any failure (missing role, non-existent
     * folder, IMAP error) is logged as a warning and reported via the return value
     * — the method never throws.
     *
     * @param markSeen
     *            for SENT {@code true} (the sender has already "read" it), for
     *            DRAFTS {@code false}.
     * @return {@code true} if the message was actually appended, {@code false} on
     *         any failure. Callers that delete prior state on success (e.g. a draft
     *         replace) MUST gate that delete on a {@code true} return.
     */
    public boolean appendByRole(Long accountId, FolderRole role, MimeMessage message, boolean markSeen) {
        try {
            final String folderName = imapFolderService.findFolderNameByRoleOrThrow(accountId, role);

            Boolean appended = imapConnectionManager.executeWithLock(accountId, store -> {
                Folder folder = store.getFolder(folderName);
                if (!folder.exists()) {
                    log.warn("{} Folder for role {} ({}) does not exist.", LogCategory.SMTP, role, folderName);
                    return false;
                }
                folder.open(Folder.READ_WRITE);
                try {
                    if (markSeen) {
                        message.setFlag(Flags.Flag.SEEN, true);
                    }
                    folder.appendMessages(new Message[]{message});
                    log.debug("{} Message stored in the folder for role {}: {}", LogCategory.SMTP, role, folderName);
                    return true;
                } finally {
                    if (folder.isOpen()) {
                        folder.close(false);
                    }
                }
            });
            return Boolean.TRUE.equals(appended);
        } catch (Exception e) {
            log.warn("{} Failed to store the message in the folder for role {} on account {}", LogCategory.SMTP, role,
                    accountId, e);
            return false;
        }
    }

    /**
     * Result of a draft append. {@code uid}/{@code uidValidity} come from the
     * UIDPLUS APPENDUID response and are {@code null} when the server does not
     * advertise them — the caller then skips the local upsert and the row appears
     * with the next sync, exactly as before.
     */
    public record DraftAppendOutcome(boolean appended, @Nullable Long uid, @Nullable Long uidValidity) {

        static DraftAppendOutcome failed() {
            return new DraftAppendOutcome(false, null, null);
        }
    }

    /**
     * Appends a draft to the given (pre-resolved) folder, requesting the UIDPLUS
     * APPENDUID so the caller can persist the local row immediately. Same
     * best-effort contract as {@link #appendByRole}: never throws, and a caller
     * that deletes prior state on success MUST gate it on {@code appended()}.
     */
    public DraftAppendOutcome appendDraft(Long accountId, String folderName, MimeMessage message) {
        try {
            DraftAppendOutcome outcome = imapConnectionManager.executeWithLock(accountId, store -> {
                Folder folder = store.getFolder(folderName);
                if (!folder.exists()) {
                    log.warn("{} Drafts folder {} does not exist.", LogCategory.SMTP, folderName);
                    return DraftAppendOutcome.failed();
                }
                folder.open(Folder.READ_WRITE);
                try {
                    if (folder instanceof IMAPFolder imapFolder) {
                        AppendUID[] uids = imapFolder.appendUIDMessages(new Message[]{message});
                        AppendUID appendUid = (uids != null && uids.length > 0) ? uids[0] : null;
                        log.debug("{} Draft stored in {} (APPENDUID: {}).", LogCategory.SMTP, folderName,
                                appendUid != null ? appendUid.uid : "unsupported");
                        if (appendUid != null && appendUid.uid >= 0) {
                            return new DraftAppendOutcome(true, appendUid.uid, appendUid.uidvalidity);
                        }
                        return new DraftAppendOutcome(true, null, null);
                    }
                    folder.appendMessages(new Message[]{message});
                    log.debug("{} Draft stored in {} (no UIDPLUS).", LogCategory.SMTP, folderName);
                    return new DraftAppendOutcome(true, null, null);
                } finally {
                    if (folder.isOpen()) {
                        folder.close(false);
                    }
                }
            });
            return outcome != null ? outcome : DraftAppendOutcome.failed();
        } catch (Exception e) {
            log.warn("{} Failed to store the draft in folder {} on account {}", LogCategory.SMTP, folderName, accountId,
                    e);
            return DraftAppendOutcome.failed();
        }
    }

    /**
     * Fetches a MIME message from IMAP and returns it as a detached
     * {@link MimeMessage} that no longer depends on the original Store/Folder (it
     * can be safely sent over SMTP after the IMAP connection is closed). The
     * implementation uses {@code writeTo(bytes) + new
     * MimeMessage(parseStream)} — the canonical Jakarta Mail pattern for detach
     * (the copy constructor has known issues with multipart parts).
     *
     * @return {@link Optional#empty()} if the message with the given UID does not
     *         exist in the folder (typically a race with deletion on the other
     *         side).
     */
    public Optional<MimeMessage> fetchAndDetachMime(Long accountId, String folderName, long uid, Session session) {
        MimeMessage detached = imapFolderService.executeInFolder(accountId, folderName, Folder.READ_ONLY,
                (folder, uidFolder) -> {
                    try {
                        Message msg = uidFolder.getMessageByUID(uid);
                        if (msg == null) {
                            return null;
                        }
                        ByteArrayOutputStream bos = new ByteArrayOutputStream();
                        msg.writeTo(bos);
                        return new MimeMessage(session, new ByteArrayInputStream(bos.toByteArray()));
                    } catch (java.io.IOException e) {
                        throw new MessagingException("Error reading MIME bytes from IMAP", e);
                    }
                });
        return Optional.ofNullable(detached);
    }
}
