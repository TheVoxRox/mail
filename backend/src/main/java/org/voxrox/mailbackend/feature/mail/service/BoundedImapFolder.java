package org.voxrox.mailbackend.feature.mail.service;

import org.eclipse.angus.mail.imap.IMAPFolder;
import org.eclipse.angus.mail.imap.IMAPMessage;
import org.eclipse.angus.mail.imap.IMAPStore;
import org.eclipse.angus.mail.imap.protocol.ListInfo;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.voxrox.mailbackend.util.LogCategory;

/**
 * Angus's IMAP folder, except that the messages it makes are
 * {@link BoundedImapMessage}s, which bound what a fetch merges into the headers
 * they hold (IMAP/SMTP audit B1-19). {@link BoundedImapStore} makes every
 * folder this way.
 */
final class BoundedImapFolder extends IMAPFolder {

    private static final Logger log = LoggerFactory.getLogger(BoundedImapFolder.class);

    /** Whether this folder has logged a refused merge; one line a folder object. */
    private boolean mergeRefusalLogged;

    BoundedImapFolder(String fullName, char separator, IMAPStore store, @Nullable Boolean isNamespace) {
        super(fullName, separator, store, isNamespace);
    }

    BoundedImapFolder(ListInfo listInfo, IMAPStore store) {
        super(listInfo, store);
    }

    @Override
    protected IMAPMessage newIMAPMessage(int msgnum) {
        return new BoundedImapMessage(this, msgnum);
    }

    /**
     * Notes a header item a message did not merge. Once a folder object: a server
     * that sends one sends them by the thousand, each charged to the open-folder
     * budget and none worth a line of its own.
     */
    void mergeRefused(int msgnum, long lines) {
        if (!mergeRefusalLogged) {
            mergeRefusalLogged = true;
            log.warn(
                    "{} Did not merge a header item of {} lines into message {} of folder {}: past the {} header "
                            + "lines a message may merge. The message keeps the headers it holds.",
                    LogCategory.IMAP, lines, msgnum, getFullName(), BoundedImapMessage.MAX_MERGED_HEADER_LINES);
        }
    }
}
