package org.voxrox.mailbackend.feature.mail.event;

import java.time.Instant;

/**
 * One folder's sync cycle finished.
 *
 * @param newMessagesCount
 *            every message the cycle downloaded into the folder, whatever it
 *            was: the first sync's window, a mirror of Sent, holes refilled
 *            inside the window. What changed in the local copy, so what the
 *            client has to reload.
 * @param arrivedMessagesCount
 *            the part of {@code newMessagesCount} that is mail which has just
 *            arrived for the user: downloaded above the folder's existing
 *            mirror, into a folder whose role delivers new mail
 *            ({@code FolderRole.deliversNewMail}). Zero for a first sync, and
 *            what the client may announce outside the app.
 */
public record MailSyncCompletedEvent(Long accountId, String folderName, int newMessagesCount, int arrivedMessagesCount,
        Instant timestamp) {
}
