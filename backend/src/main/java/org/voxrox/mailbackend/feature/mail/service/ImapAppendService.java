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
import org.voxrox.mailbackend.exception.ErrorCode;
import org.voxrox.mailbackend.exception.MailFailureCause;
import org.voxrox.mailbackend.exception.MailOperationException;
import org.voxrox.mailbackend.feature.mail.dto.FolderRole;
import org.voxrox.mailbackend.feature.mail.service.ImapConnectionManager.Lane;
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

            Boolean appended = imapConnectionManager.executeWithLock(accountId, Lane.BACKGROUND, store -> {
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
                        // close(false), not the AutoCloseable close() — that one is
                        // close(true) and would expunge. See ImapFolderExecutor.
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
            DraftAppendOutcome outcome = imapConnectionManager.executeWithLock(accountId, Lane.BACKGROUND, store -> {
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
                        // close(false), not the AutoCloseable close() — that one is
                        // close(true) and would expunge. See ImapFolderExecutor.
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
     * Raw MIME bytes a draft fetched back off the server may take (IMAP/SMTP audit
     * B1-5). The server states no size here — it simply sends — so without a bound
     * a hostile or compromised one answers the fetch with as much as it likes, and
     * the bytes are held twice over: once as the array read off the wire, once as
     * the parsed {@link MimeMessage}'s own copy.
     * <p>
     * 40 MiB is above what any mail provider accepts for sending — base64 inflates
     * attachments by about a third, so a 25 MB attachment set, Gmail's ceiling,
     * reaches the wire at roughly 35 MB — and some 104 MiB of the packaged 384 MB
     * heap at its worst: the bytes are read into a buffer that doubles as it grows,
     * 64 MiB for a draft past 32, beside the content's copy (measured by the pass
     * over 1.59). A draft past it could not have been sent anyway; this way it
     * fails on the fetch rather than at the SMTP server.
     */
    static final int MAX_DRAFT_BYTES = 40 * 1024 * 1024;

    /**
     * What the header block of a draft fetched back for an untouched send may cost
     * once parsed, charged as {@link BoundedImapProtocol#headerBytes} charges a
     * body item: {@link BoundedImapProtocol#HEADER_LINE_BYTES} a header and the
     * block's bytes twice more (IMAP/SMTP audit B1-18). {@code MimeMessage} loads
     * every line of the block into {@code InternetHeaders}, some 26 times its size
     * for short lines, which within {@link #MAX_DRAFT_BYTES} came to 1,046 MiB
     * (measured by the pass over 1.56). An honest draft's block is some kilobytes,
     * a reply deep in a thread some more for its {@code References}; this admits a
     * block of 128 KiB, some three thousand recipients of 40 bytes, and what the
     * address parse then keeps, some 76 bytes an address at its densest
     * ({@code a,a,a}, measured), stays near 5 MB.
     */
    static final int MAX_DRAFT_HEADER_BYTES = 256 * 1024;

    /**
     * Fetches a MIME message from IMAP and returns it as a detached
     * {@link MimeMessage} that no longer depends on the original Store/Folder (it
     * can be safely sent over SMTP after the IMAP connection is closed): the
     * message's bytes are written out and parsed again.
     * <p>
     * The write is bounded at {@link #MAX_DRAFT_BYTES} and stops at the first byte
     * past it, so an oversized answer costs the bound rather than whatever the
     * server chose to send. The header block is then charged against
     * {@link #MAX_DRAFT_HEADER_BYTES} before it is parsed, and only that block is:
     * the message is an {@link UntouchedDraft}, whose content goes out as the
     * server holds it, never parsed into parts (B1-18).
     *
     * @return {@link Optional#empty()} if the message with the given UID does not
     *         exist in the folder (typically a race with deletion on the other
     *         side).
     * @throws MailOperationException
     *             if the server answers with more than {@link #MAX_DRAFT_BYTES}, or
     *             with a header block that would cost more than
     *             {@link #MAX_DRAFT_HEADER_BYTES}.
     */
    public Optional<MimeMessage> fetchAndDetachMime(Long accountId, String folderName, long uid, Session session) {
        // INTERACTIVE: the user pressed Send and is watching for the result. Reading
        // the draft is short and read-only, so it belongs in front of a sync rather
        // than behind one.
        MimeMessage detached = imapFolderService.executeInFolder(accountId, Lane.INTERACTIVE, folderName,
                Folder.READ_ONLY, (folder, uidFolder) -> {
                    try {
                        Message msg = uidFolder.getMessageByUID(uid);
                        if (msg == null) {
                            return null;
                        }
                        BoundedByteArrayOutputStream bytes = new BoundedByteArrayOutputStream(MAX_DRAFT_BYTES);
                        msg.writeTo(bytes);
                        bytes.checkHeaderBlock();
                        // Reads the buffer in place rather than through toByteArray(), which would
                        // copy the whole draft a third time.
                        return new UntouchedDraft(session, bytes.toInputStream());
                    } catch (java.io.IOException e) {
                        throw new MessagingException("Error reading MIME bytes from IMAP", e);
                    }
                });
        return Optional.ofNullable(detached);
    }

    /**
     * A {@link ByteArrayOutputStream} that refuses to hold more than a bound, and
     * that hands its buffer out without copying it.
     * <p>
     * The refusal is a {@link MailOperationException} rather than an
     * {@link java.io.IOException}, which the caller above would wrap as "error
     * reading MIME bytes" and so lose the reason in the log. The send pipeline
     * reports any failure to the user by its {@link MailFailureCause}, so the text
     * stays in the log either way.
     */
    private static final class BoundedByteArrayOutputStream extends ByteArrayOutputStream {

        private final int limit;

        BoundedByteArrayOutputStream(int limit) {
            this.limit = limit;
        }

        ByteArrayInputStream toInputStream() {
            return new ByteArrayInputStream(buf, 0, count);
        }

        /**
         * Refuses a draft whose header block would cost more than
         * {@link #MAX_DRAFT_HEADER_BYTES} once parsed, counted on the bytes as held
         * before anything parses them (B1-18).
         */
        synchronized void checkHeaderBlock() {
            long charge = BoundedImapProtocol.headerBytes(buf, 0, count);
            if (charge > MAX_DRAFT_HEADER_BYTES) {
                throw new MailOperationException(ErrorCode.MAIL_CONNECTION_ERROR,
                        "The headers of the draft on the server would take " + charge + " bytes, more than "
                                + MAX_DRAFT_HEADER_BYTES + ", and it was not sent.");
            }
        }

        @Override
        public synchronized void write(int b) {
            checkRoomFor(1);
            super.write(b);
        }

        @Override
        public synchronized void write(byte[] source, int offset, int length) {
            checkRoomFor(length);
            super.write(source, offset, length);
        }

        private void checkRoomFor(int length) {
            if (length > limit - count) {
                throw new MailOperationException(ErrorCode.MAIL_CONNECTION_ERROR,
                        "The draft on the server is larger than " + limit + " bytes and was not sent.");
            }
        }
    }

    /**
     * A draft fetched back for an untouched send, which goes out as the server
     * holds it: its header block is parsed, and its content is not (IMAP/SMTP audit
     * B1-18). {@code MimeMessage.saveChanges} marks a message modified and updates
     * the headers of every part, which parses a multipart content into its parts,
     * each part's header block into {@code InternetHeaders} and each nested
     * multipart the same way; the bound on the top-level block then left every
     * other block uncharged, and a draft of 5 MB with a million header lines in its
     * second part ran a 64 MB heap out of memory where this sent it (measured).
     * Here saving updates only what the send changes at the top, the
     * {@code MIME-Version}, the {@code Date} and the {@code Message-ID}, and leaves
     * the message unmodified, so {@code writeTo} writes those headers and then
     * copies the content byte for byte. That is what went out before as well: the
     * parts were updated on a copy that saving then dropped, and the content was
     * copied as held (measured: three drafts, the bytes the same either way). What
     * changes is that nothing parses the content, so a multipart whose boundary
     * never appears, which that parse refused, now goes out as the server holds it.
     * Nothing else on the send path reads the content: the recipient check, the
     * SMTP envelope and the Sent append read the top-level headers, and the SMTP
     * transport converts content to 8-bit only under
     * {@code mail.smtp.allow8bitmime}, which this application never sets.
     */
    static final class UntouchedDraft extends MimeMessage {

        UntouchedDraft(Session session, java.io.InputStream in) throws MessagingException {
            super(session, in);
        }

        @Override
        public void saveChanges() throws MessagingException {
            saved = true;
            updateHeaders();
        }

        @Override
        protected synchronized void updateHeaders() throws MessagingException {
            setHeader("MIME-Version", "1.0");
            if (getHeader("Date") == null) {
                setSentDate(java.util.Date.from(java.time.Instant.now()));
            }
            updateMessageID();
        }
    }
}
