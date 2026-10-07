package org.voxrox.mailbackend.feature.mail.service;

import jakarta.mail.MessagingException;

import org.eclipse.angus.mail.iap.ByteArray;
import org.eclipse.angus.mail.imap.IMAPFolder;
import org.eclipse.angus.mail.imap.IMAPMessage;
import org.eclipse.angus.mail.imap.protocol.BODY;
import org.eclipse.angus.mail.imap.protocol.Item;
import org.eclipse.angus.mail.imap.protocol.RFC822DATA;
import org.jspecify.annotations.Nullable;

/**
 * Angus's IMAP message, except that what a fetch merges into headers it already
 * holds is bounded (IMAP/SMTP audit B1-19).
 * <p>
 * When a fetch asks for some headers rather than all of them, as the sync's
 * does, and the message holds headers already, {@code IMAPMessage} merges a
 * header item into them with one {@code InternetHeaders.addHeader} for each
 * header whose name it has not loaded, and each such call scans the whole list
 * before it inserts: a merge costs the product of the headers it adds and the
 * headers the message holds. {@code IMAPFolder.fetch} hands a message every
 * item of every FETCH response of the command, whichever message the client
 * asked about, and the message keeps its headers for as long as the folder is
 * open, so a server can merge into one message again and again, in one command
 * or across many, and under fresh numbers after an EXPUNGE. Measured by the
 * pass over 1.56: 160,000 headers in a second item took 163 s, on the sync's
 * thread with the account's lane held. A bound on each item does not end it,
 * since the items can be many; the merges into one message are what add up, so
 * this counts them on the message.
 * <p>
 * A message merges at most {@link #MAX_MERGED_HEADER_LINES} header lines over
 * its life, counted as the item's header block
 * ({@link BoundedImapProtocol#headerBlock}) whether or not Angus then adds each
 * one. An item past that is not merged: the message keeps the headers it holds,
 * and a header it was asked for and did not get is loaded whole when it is read
 * ({@code IMAPMessage.loadHeaders}), as for a message the fetch said nothing
 * about. A merge then costs at most this bound times the headers held, and
 * those were each charged to the open-folder budget when they arrived (B1-16),
 * so what one open folder can spend merging is bounded by the two together.
 */
final class BoundedImapMessage extends IMAPMessage {

    /**
     * Header lines one message may merge over its life. The sync asks for three
     * headers ({@code MessageFetcher}), and an honest server answers with those
     * three, once a fetch; a merge happens only when the message already holds
     * headers, so an honest message merges a handful in a folder's open.
     */
    static final int MAX_MERGED_HEADER_LINES = 256;

    /** Header lines merged so far; under the folder's message cache lock. */
    private long mergedLines;

    BoundedImapMessage(IMAPFolder folder, int msgnum) {
        super(folder, msgnum);
    }

    @Override
    protected boolean handleFetchItem(Item item, String[] hdrs, boolean allHeaders) throws MessagingException {
        ByteArray merged = allHeaders || headers == null ? null : headerItem(item);
        if (merged != null) {
            long lines = BoundedImapProtocol
                    .headerBlock(merged.getBytes(), merged.getStart(), merged.getStart() + merged.getCount()).headers();
            if (mergedLines + lines > MAX_MERGED_HEADER_LINES) {
                if (folder instanceof BoundedImapFolder bounded) {
                    bounded.mergeRefused(getMessageNumber(), lines);
                }
                return true;
            }
            mergedLines += lines;
        }
        return super.handleFetchItem(item, hdrs, allHeaders);
    }

    /**
     * The bytes of a header item, which {@code IMAPMessage} merges into the headers
     * a message holds; null for any other item, and for a header item that is NIL,
     * which merges nothing.
     */
    private static @Nullable ByteArray headerItem(Item item) {
        return switch (item) {
            case BODY body when body.isHeader() -> body.getByteArray();
            case RFC822DATA rfc822 when rfc822.isHeader() -> rfc822.getByteArray();
            default -> null;
        };
    }
}
