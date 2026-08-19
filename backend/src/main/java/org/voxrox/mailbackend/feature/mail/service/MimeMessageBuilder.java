package org.voxrox.mailbackend.feature.mail.service;

import java.io.UnsupportedEncodingException;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.Optional;

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

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.voxrox.mailbackend.feature.account.entity.AccountEntity;
import org.voxrox.mailbackend.feature.mail.dto.MailRequest;
import org.voxrox.mailbackend.util.HeaderAddresses;

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
 * Body is the plaintext from the compose textarea ({@code text/plain;
 * charset=UTF-8}); under {@link BodyFormat#MARKDOWN} a message that actually
 * uses Markdown additionally carries the rendered {@code text/html} as a
 * {@code multipart/alternative} sibling. The subject is encoded as UTF-8,
 * attachment filenames are encoded via {@link MimeUtility#encodeText} (RFC 2047
 * for non-ASCII).
 */
@Component
public class MimeMessageBuilder {

    private final MarkdownBodyRenderer markdownRenderer;

    public MimeMessageBuilder(MarkdownBodyRenderer markdownRenderer) {
        this.markdownRenderer = markdownRenderer;
    }

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

    /**
     * Whether the body text is interpreted as Markdown on the way out.
     */
    public enum BodyFormat {
        /**
         * Send: a body that uses Markdown goes out as {@code multipart/alternative} —
         * the typed source as {@code text/plain}, the rendering as {@code text/html}. A
         * body without Markdown stays a single {@code text/plain} part (see
         * {@link MarkdownBodyRenderer}).
         */
        MARKDOWN,
        /**
         * Draft: always a single {@code text/plain} part carrying the typed source.
         *
         * <p>
         * Never widen this to {@link #MARKDOWN}. A draft is round-tripped through the
         * IMAP Drafts folder and back into the composer, which is a plain-text editor:
         * reopening flattens whatever body part it finds to text
         * ({@code frontend/src/lib/compose/prefill.ts}). Storing the rendering would
         * therefore return {@code bold} where the user typed {@code **bold**} — the
         * Markdown source would be destroyed by saving and reopening the draft. The
         * rendering is derived data; the typed text is what has to survive.
         */
        PLAIN
    }

    public MimeMessage build(Session session, AccountEntity account, MailRequest request, AddressPolicy addressPolicy,
            BodyFormat bodyFormat) throws MessagingException, UnsupportedEncodingException {
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
        multipart.addBodyPart(buildBodyPart(request.body() == null ? "" : request.body(), bodyFormat));

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
     * The message's body part: a bare {@code text/plain} part, or — when the body
     * uses Markdown and the caller asked for {@link BodyFormat#MARKDOWN} — a
     * {@code multipart/alternative} holding the typed source and its rendering.
     *
     * <p>
     * Alternative parts are ordered least-to-most preferred per RFC 2046 §5.1.4, so
     * {@code text/plain} comes first: a client that understands both shows the
     * HTML, one that understands only text still finds the source it can render.
     */
    private MimeBodyPart buildBodyPart(String body, BodyFormat bodyFormat) throws MessagingException {
        MimeBodyPart textPart = new MimeBodyPart();
        textPart.setText(body, "UTF-8");

        if (bodyFormat == BodyFormat.PLAIN) {
            return textPart;
        }
        Optional<String> html = markdownRenderer.renderAlternative(body);
        if (html.isEmpty()) {
            return textPart;
        }

        MimeBodyPart htmlPart = new MimeBodyPart();
        htmlPart.setContent(html.get(), "text/html; charset=UTF-8");

        MimeMultipart alternative = new MimeMultipart("alternative");
        alternative.addBodyPart(textPart);
        alternative.addBodyPart(htmlPart);

        MimeBodyPart wrapper = new MimeBodyPart();
        wrapper.setContent(alternative);
        return wrapper;
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
     * addresses — see {@link HeaderAddresses#parseValidTokens} for why neither a
     * comma split nor {@code InternetAddress.parse} can do this. Shared with the
     * correspondent harvest, which reads the same header fields back off synced
     * mail and must agree with this one about what counts as an address.
     */
    private static InternetAddress[] parseValidTokens(String raw) {
        return HeaderAddresses.parseValidTokens(raw);
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
