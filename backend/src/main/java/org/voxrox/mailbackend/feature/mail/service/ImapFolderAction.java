package org.voxrox.mailbackend.feature.mail.service;

import jakarta.mail.Folder;
import jakarta.mail.MessagingException;
import jakarta.mail.UIDFolder;

@FunctionalInterface
public interface ImapFolderAction<R> {
    /**
     * Runs the action against an already opened IMAP folder.
     *
     * @param folder
     *            Opened folder (READ_ONLY or READ_WRITE)
     * @param uidFolder
     *            The same folder cast to UIDFolder for UID operations
     */
    R apply(Folder folder, UIDFolder uidFolder) throws MessagingException;
}
