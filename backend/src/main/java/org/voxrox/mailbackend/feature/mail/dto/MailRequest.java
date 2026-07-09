package org.voxrox.mailbackend.feature.mail.dto;

import java.util.Collections;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.jspecify.annotations.Nullable;

public record MailRequest(@NotBlank(message = "{validation.mail.recipientRequired}") String to,

        @Nullable String cc,

        @Nullable String bcc,

        @NotBlank(message = "{validation.mail.subjectRequired}") @Size(max = 500, message = "{validation.mail.subjectTooLong}") String subject,

        @NotBlank(message = "{validation.mail.bodyRequired}") @Size(max = 10 * 1024
                * 1024, message = "{validation.mail.bodyTooLong}") String body,

        @Size(max = 50, message = "{validation.mail.tooManyAttachments}") List<@Valid AttachmentRequest> attachments,

        @Nullable String inReplyTo, @Nullable String references) {
    public MailRequest {
        if (attachments == null) {
            attachments = Collections.emptyList();
        }
    }

    public record AttachmentRequest(
            @NotBlank(message = "{validation.attachment.fileNameRequired}") @Size(max = 255, message = "{validation.attachment.fileNameTooLong}") String fileName,

            @NotBlank(message = "{validation.attachment.contentTypeRequired}") @Size(max = 255, message = "{validation.attachment.contentTypeTooLong}") String contentType,

            /*
             * Per-attachment cap: 70 MB base64 ~= 52 MB binary after decoding. NOTE: the
             * send/draft endpoints consume a JSON request body (not multipart), so the
             * spring.servlet.multipart.* limits do NOT apply here — these bean-validation
             * size caps (plus the attachment-count and body-length caps on the enclosing
             * MailRequest) are the payload bound. They run after Jackson deserialization;
             * the pre-deserialization aggregate bound is a documented residual (Boundary 3
             * is loopback + X-API-KEY, and the client enforces a 25 MB total). See
             * docs/API_SURFACE_AUDIT.md.
             */
            @NotBlank(message = "{validation.attachment.dataRequired}") @Size(max = 70 * 1024
                    * 1024, message = "{validation.attachment.dataTooLarge}") String base64Data) {
    }
}
