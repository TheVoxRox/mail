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
 *            message.
 */
public record MailContentResponse(String content,

        @Schema(description = "Bare sender email, used as the allow-list key for the remote-image opt-in.", example = "newsletter@example.com") String senderEmail,

        @Schema(description = "Whether the sender is already trusted to load remote images.", example = "false") boolean remoteImagesAllowedForSender) {
}
