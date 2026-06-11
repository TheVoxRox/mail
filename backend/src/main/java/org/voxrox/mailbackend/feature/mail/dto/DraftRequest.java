package org.voxrox.mailbackend.feature.mail.dto;

import java.util.Collections;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;

import org.voxrox.mailbackend.feature.mail.dto.MailRequest.AttachmentRequest;

/**
 * Input DTO for saving a draft. Unlike {@link MailRequest}, all text fields are
 * optional — the user may save a work-in-progress message without a recipient,
 * subject or body.
 */
public record DraftRequest(String to, String cc, String bcc,
        @Size(max = 500, message = "{validation.mail.subjectTooLong}") String subject, String body,
        List<@Valid AttachmentRequest> attachments, String inReplyTo, String references) {

    public DraftRequest {
        if (attachments == null) {
            attachments = Collections.emptyList();
        }
    }

    /**
     * {@code MailRequest} validation is applied only at the controller layer (via
     * {@code @Valid}), so direct construction with empty fields is legal.
     */
    public MailRequest toMailRequest() {
        return new MailRequest(to == null ? "" : to, cc, bcc, subject == null ? "" : subject, body == null ? "" : body,
                attachments, inReplyTo, references);
    }
}
