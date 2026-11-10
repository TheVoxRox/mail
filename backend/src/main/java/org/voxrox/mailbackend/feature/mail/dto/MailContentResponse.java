package org.voxrox.mailbackend.feature.mail.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The message body plus the metadata the client needs to offer the remote-image
 * opt-in (content-rendering audit finding F2).
 *
 * @param content
 *            sanitized display HTML (remote https images carried inertly in
 *            {@code data-voxrox-remote-src}, never a live {@code src}).
 * @param senderEmail
 *            the bare sender address, used as the key when the user chooses to
 *            always trust this sender's remote images.
 * @param remoteImagesAllowedForSender
 *            {@code true} when the sender is already on the account's
 *            allow-list, so the client can auto-load remote images for this
 *            message. Always {@code false} when {@code remoteImageTrustOffered}
 *            is.
 * @param remoteImageTrustOffered
 *            {@code false} when the message sits in a folder recorded as Junk.
 *            The allow-list is keyed on the spoofable {@code From}, and junk is
 *            where a message forged in a trusted sender's name ends up: there
 *            the list is not applied, and the client must not offer to extend
 *            it from such a message. Loading the images of that one message
 *            stays the user's call.
 */
public record MailContentResponse(String content,

        @Schema(description = "Bare sender email, used as the allow-list key for the remote-image opt-in.", example = "newsletter@example.com") String senderEmail,

        @Schema(description = "Whether the sender is already trusted to load remote images. Always false for a message in a Junk folder.", example = "false") boolean remoteImagesAllowedForSender,

        @Schema(description = "Whether the client may offer to trust the sender for remote images. False for a message in a Junk folder, where the allow-list is not applied.", example = "true") boolean remoteImageTrustOffered) {
}
