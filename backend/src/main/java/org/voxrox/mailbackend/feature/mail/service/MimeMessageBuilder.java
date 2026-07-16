package org.voxrox.mailbackend.feature.mail.service;

import java.io.UnsupportedEncodingException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.List;

import jakarta.activation.DataHandler;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.Session;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.internet.MimeUtility;
import jakarta.mail.util.ByteArrayDataSource;

import org.jspecify.annotations.Nullable;
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
 * without recipients), parsed per the caller's {@link AddressPolicy}.</li>
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

    /**
     * How the {@code to/cc/bcc} text is turned into header addresses. The two
     * callers of this builder have opposite needs, so the choice is explicit at
     * every call site rather than defaulted.
     */
    public enum AddressPolicy {
        /**
         * Send: every address must be a valid {@code addr-spec} or the build fails.
         * Never degrade this to {@link #DRAFT} — silently dropping a token here would
         * deliver the message to a subset of the recipients the user typed.
         */
        STRICT,
        /**
         * Draft: the recipient field is work in progress, so a token that is not (yet)
         * a valid address is dropped from the header instead of failing the save.
         *
         * <p>
         * Dropping rather than writing the raw token is deliberate:
         * {@code luke.lacina@} is not a legal RFC 5322 {@code addr-spec}, and a message
         * carrying one is malformed on the wire. Servers are entitled to reject it —
         * GreenMail fails to build the IMAP ENVELOPE for such a message, and every
         * subsequent read of it throws, which would strand an *unreadable* draft in the
         * user's mailbox ({@code MessageFetcher} skips a message whose envelope will
         * not load). Losing an incomplete address beats losing the whole draft. The
         * typed text is not lost meanwhile: the local row stores the raw
         * {@code to/cc/bcc} strings ({@code SmtpMessageService.upsertLocalDraftRow}),
         * so the composer still shows it; only a draft re-downloaded from the server
         * (fresh device, reset DB) comes back without the incomplete token.
         */
        DRAFT
    }

    public MimeMessage build(Session session, AccountEntity account, MailRequest request, AddressPolicy addressPolicy)
            throws MessagingException, UnsupportedEncodingException {
        MimeMessage message = new MimeMessage(session);

        message.setFrom(new InternetAddress(account.getEmail(), account.getDisplayName(), "UTF-8"));

        setRecipients(message, Message.RecipientType.TO, request.to(), addressPolicy);
        setRecipients(message, Message.RecipientType.CC, request.cc(), addressPolicy);
        setRecipients(message, Message.RecipientType.BCC, request.bcc(), addressPolicy);

        message.setSubject(request.subject() == null ? "" : requireSingleLine(request.subject(), "subject"), "UTF-8");

        String inReplyTo = request.inReplyTo();
        if (inReplyTo != null && !inReplyTo.isBlank()) {
            message.setHeader("In-Reply-To", requireSingleLine(inReplyTo, "In-Reply-To"));
        }
        String references = request.references();
        if (references != null && !references.isBlank()) {
            message.setHeader("References", requireSingleLine(references, "References"));
        }

        Multipart multipart = new MimeMultipart();

        MimeBodyPart textPart = new MimeBodyPart();
        textPart.setText(request.body() == null ? "" : request.body(), "UTF-8");
        multipart.addBodyPart(textPart);

        for (MailRequest.AttachmentRequest att : request.attachments()) {
            MimeBodyPart attachPart = new MimeBodyPart();
            byte[] data = Base64.getDecoder().decode(att.base64Data());
            ByteArrayDataSource ds = new ByteArrayDataSource(data,
                    requireSingleLine(att.contentType(), "content type"));
            attachPart.setDataHandler(new DataHandler(ds));
            attachPart.setFileName(MimeUtility.encodeText(att.fileName()));
            multipart.addBodyPart(attachPart);
        }

        message.setContent(multipart);
        message.setSentDate(Date.from(Instant.now()));
        return message;
    }

    /**
     * Sets one recipient header, or leaves it absent when the field is empty — or,
     * under {@link AddressPolicy#DRAFT}, when nothing in it parses to a valid
     * address yet.
     */
    private static void setRecipients(MimeMessage message, Message.RecipientType type, @Nullable String raw,
            AddressPolicy policy) throws MessagingException {
        if (raw == null || raw.isBlank()) {
            return;
        }
        InternetAddress[] addresses = switch (policy) {
            case STRICT -> InternetAddress.parse(raw);
            case DRAFT -> parseValidTokens(raw);
        };
        if (addresses.length > 0) {
            message.setRecipients(type, addresses);
        }
    }

    /**
     * Splits the field into address tokens and keeps only those that are complete
     * addresses. {@code InternetAddress.parse} cannot be used here — it rejects the
     * whole field over one incomplete token, and its lenient overload
     * ({@code parse(s, false)}) rejects it just the same; only
     * {@link InternetAddress#parseHeader} tokenizes without validating, which is
     * why the per-token {@link InternetAddress#validate()} does the deciding.
     *
     * <p>
     * A field that will not even tokenize yields no addresses rather than an
     * exception: a draft save must never fail on what the user has typed so far.
     */
    private static InternetAddress[] parseValidTokens(String raw) {
        InternetAddress[] tokens;
        try {
            tokens = InternetAddress.parseHeader(raw, false);
        } catch (AddressException e) {
            return new InternetAddress[0];
        }
        List<InternetAddress> complete = new ArrayList<>(tokens.length);
        for (InternetAddress token : tokens) {
            try {
                token.validate();
                complete.add(token);
            } catch (AddressException e) {
                // Half-typed: expected on almost every keystroke-triggered autosave.
            }
        }
        return complete.toArray(new InternetAddress[0]);
    }

    /**
     * Guards a user-supplied value that is written verbatim into a MIME header
     * (subject, In-Reply-To, References, a part's Content-Type). Jakarta Mail does
     * not strip CR/LF, so a raw line break would inject an arbitrary header into
     * the outgoing message. A legitimate client never sends a line break in these
     * fields (and inbound Message-IDs are CR/LF-free after IMAP header unfolding),
     * so we fail closed rather than silently rewrite what the user is sending.
     */
    private static String requireSingleLine(String value, String field) throws MessagingException {
        if (value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
            throw new MessagingException("Illegal line break in " + field);
        }
        return value;
    }
}
