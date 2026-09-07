package org.voxrox.mailbackend.feature.mail.service;

import java.util.List;

import jakarta.mail.Folder;
import jakarta.mail.MessagingException;
import jakarta.mail.UIDFolder;
import jakarta.mail.event.MailEvent;

import org.jspecify.annotations.Nullable;

/**
 * {@link ImapFolderAction} for the sync path, which needs one thing the plain
 * action cannot express: what the SELECT itself reported.
 */
@FunctionalInterface
public interface ImapResyncFolderAction<R> {
    /**
     * Runs the action against an already opened IMAP folder. The result is nullable
     * by the same contract as {@link ImapFolderAction#apply}.
     *
     * @param folder
     *            Opened folder (READ_ONLY or READ_WRITE)
     * @param uidFolder
     *            The same folder cast to UIDFolder for UID operations
     * @param resyncEvents
     *            What the QRESYNC SELECT reported — expunged UIDs and flag changes
     *            since the caller's MODSEQ. {@code null} when the folder was opened
     *            without resynchronization, which is not the same as an empty list;
     *            see {@link ImapFolderExecutor#executeReadOnlyResynced}.
     */
    @Nullable
    R apply(Folder folder, UIDFolder uidFolder, @Nullable List<MailEvent> resyncEvents) throws MessagingException;
}
