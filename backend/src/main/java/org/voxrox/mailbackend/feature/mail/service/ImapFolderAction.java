package org.voxrox.mailbackend.feature.mail.service;

import jakarta.mail.Folder;
import jakarta.mail.MessagingException;
import jakarta.mail.UIDFolder;

import org.jspecify.annotations.Nullable;

@FunctionalInterface
public interface ImapFolderAction<R> {
    /**
     * Runs the action against an already opened IMAP folder. The result is nullable
     * by contract — several call sites use {@code return null} for "side-effect
     * only" actions, so consumers of a non-void result must null-check (or require
     * non-null when the lambda provably returns one).
     *
     * @param folder
     *            Opened folder (READ_ONLY or READ_WRITE)
     * @param uidFolder
     *            The same folder cast to UIDFolder for UID operations
     */
    @Nullable
    R apply(Folder folder, UIDFolder uidFolder) throws MessagingException;
}
