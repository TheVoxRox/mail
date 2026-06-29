package org.voxrox.mailbackend.feature.mail.service;

import java.io.UnsupportedEncodingException;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;

import jakarta.activation.DataHandler;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.internet.MimeUtility;
import jakarta.mail.util.ByteArrayDataSource;

import org.springframework.stereotype.Component;
import org.voxrox.mailbackend.feature.account.entity.AccountEntity;
import org.voxrox.mailbackend.feature.mail.dto.MailRequest;

/**
 * Builds a {@link MimeMessage} from a {@link MailRequest} in the context of the
 * given account. Stateless transformer — no dependencies on repo/session beyond
 * its arguments, can be freely shared between threads.
 *
 * <p>
 * Rules for optional fields (aligned with
 * {@link org.voxrox.mailbackend.feature.mail.dto.DraftRequest#toMailRequest()}):
 * <ul>
 * <li>{@code to/cc/bcc} — set only when not null/blank (a draft is legal even
 * without recipients).</li>
 * <li>{@code subject/body} — null safely maps to "".</li>
 * <li>{@code inReplyTo/references} — header is set only when not
 * null/blank.</li>
 * <li>{@code attachments} — the record's compact constructor guarantees a
 * non-null list, no defensive null check needed.</li>
 * </ul>
 *
 * <p>
 * Body is plaintext from the compose textarea ({@code text/plain;
 * charset=UTF-8}), the subject is encoded as UTF-8, attachment filenames are
 * encoded via {@link MimeUtility#encodeText} (RFC 2047 for non-ASCII).
 */
@Component
public class MimeMessageBuilder {

    public MimeMessage build(Session session, AccountEntity account, MailRequest request)
            throws MessagingException, UnsupportedEncodingException {
        MimeMessage message = new MimeMessage(session);

        message.setFrom(new InternetAddress(account.getEmail(), account.getDisplayName(), "UTF-8"));

        String to = request.to();
        if (to != null && !to.isBlank()) {
            message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(to));
        }
        String cc = request.cc();
        if (cc != null && !cc.isBlank()) {
            message.setRecipients(Message.RecipientType.CC, InternetAddress.parse(cc));
        }
        String bcc = request.bcc();
        if (bcc != null && !bcc.isBlank()) {
            message.setRecipients(Message.RecipientType.BCC, InternetAddress.parse(bcc));
        }

        message.setSubject(request.subject() == null ? "" : request.subject(), "UTF-8");

        String inReplyTo = request.inReplyTo();
        if (inReplyTo != null && !inReplyTo.isBlank()) {
            message.setHeader("In-Reply-To", inReplyTo);
        }
        String references = request.references();
        if (references != null && !references.isBlank()) {
            message.setHeader("References", references);
        }

        Multipart multipart = new MimeMultipart();

        MimeBodyPart textPart = new MimeBodyPart();
        textPart.setText(request.body() == null ? "" : request.body(), "UTF-8");
        multipart.addBodyPart(textPart);

        for (MailRequest.AttachmentRequest att : request.attachments()) {
            MimeBodyPart attachPart = new MimeBodyPart();
            byte[] data = Base64.getDecoder().decode(att.base64Data());
            ByteArrayDataSource ds = new ByteArrayDataSource(data, att.contentType());
            attachPart.setDataHandler(new DataHandler(ds));
            attachPart.setFileName(MimeUtility.encodeText(att.fileName()));
            multipart.addBodyPart(attachPart);
        }

        message.setContent(multipart);
        message.setSentDate(Date.from(Instant.now()));
        return message;
    }
}
