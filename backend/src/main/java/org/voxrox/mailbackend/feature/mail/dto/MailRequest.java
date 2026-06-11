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

        @NotBlank(message = "{validation.mail.bodyRequired}") String body,

        List<@Valid AttachmentRequest> attachments,

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
             * 70 MB cap = ~52 MB binary after base64 decoding (aligned with
             * spring.servlet.multipart.max-file-size=50MB). Without the cap the client
             * could send hundreds of MB in a single JSON body and exhaust the heap.
             */
            @NotBlank(message = "{validation.attachment.dataRequired}") @Size(max = 70 * 1024
                    * 1024, message = "{validation.attachment.dataTooLarge}") String base64Data) {
    }
}
