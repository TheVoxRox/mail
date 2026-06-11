package org.voxrox.mailbackend.feature.mail.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Properties;

import jakarta.mail.Address;
import jakarta.mail.BodyPart;
import jakarta.mail.Message;
import jakarta.mail.Multipart;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.voxrox.mailbackend.feature.account.entity.AccountEntity;
import org.voxrox.mailbackend.feature.mail.dto.MailRequest;

/**
 * Unit tests for {@link MimeMessageBuilder}. The builder is a pure
 * transformation (no Spring beans, no static calls into mail infrastructure),
 * so an empty {@link Session} is enough and we verify properties of the
 * resulting MIME message directly via the Jakarta Mail API.
 */
class MimeMessageBuilderTest {

    private MimeMessageBuilder builder;
    private Session session;
    private AccountEntity account;

    @BeforeEach
    void setUp() {
        builder = new MimeMessageBuilder();
        session = Session.getInstance(new Properties());

        account = new AccountEntity();
        account.setId(1L);
        account.setEmail("alice@example.com");
        account.setDisplayName("Alice Nováková");
    }

    private MailRequest req(String to, String cc, String bcc, String subject, String body,
            List<MailRequest.AttachmentRequest> attachments, String inReplyTo, String references) {
        return new MailRequest(to, cc, bcc, subject, body, attachments, inReplyTo, references);
    }

    @Nested
    @DisplayName("From header")
    class FromHeader {

        @Test
        @DisplayName("Email and display name with diacritics — display name encoded as UTF-8 (RFC 2047)")
        void includesEmailAndUtf8DisplayName() throws Exception {
            MimeMessage msg = builder.build(session, account,
                    req("to@example.com", null, null, "s", "b", List.of(), null, null));

            Address[] from = msg.getFrom();
            assertThat(from).hasSize(1);
            // toString() returns the RFC 822 form: "Display Name" <email>; diacritics are
            // encoded as =?UTF-8?...?= (Q or B encoding per Jakarta Mail).
            String fromString = from[0].toString();
            assertThat(fromString).contains("alice@example.com");
            assertThat(fromString).contains("=?UTF-8?");
            assertThat(fromString).contains("?="); // closing token RFC 2047
        }

        @Test
        @DisplayName("displayName=null — From has only the email without the personal part")
        void worksWithNullDisplayName() throws Exception {
            account.setDisplayName(null);

            MimeMessage msg = builder.build(session, account,
                    req("to@example.com", null, null, "s", "b", List.of(), null, null));

            Address[] from = msg.getFrom();
            assertThat(from).hasSize(1);
            assertThat(from[0].toString()).isEqualTo("alice@example.com");
        }
    }

    @Nested
    @DisplayName("Recipients")
    class Recipients {

        @Test
        @DisplayName("Single TO recipient — sets one recipient, CC and BCC not set when null")
        void singleToOnly() throws Exception {
            MimeMessage msg = builder.build(session, account,
                    req("bob@example.com", null, null, "s", "b", List.of(), null, null));

            assertThat(msg.getRecipients(Message.RecipientType.TO)).extracting(Object::toString)
                    .containsExactly("bob@example.com");
            assertThat(msg.getRecipients(Message.RecipientType.CC)).isNull();
            assertThat(msg.getRecipients(Message.RecipientType.BCC)).isNull();
        }

        @Test
        @DisplayName("Multiple TO recipients separated by comma — all added")
        void multipleToCommaSeparated() throws Exception {
            MimeMessage msg = builder.build(session, account,
                    req("bob@example.com, carol@example.com", null, null, "s", "b", List.of(), null, null));

            assertThat(msg.getRecipients(Message.RecipientType.TO)).extracting(Object::toString)
                    .containsExactly("bob@example.com", "carol@example.com");
        }

        @Test
        @DisplayName("CC and BCC populated — both set; blank TO not set (draft scenario)")
        void ccAndBccSetWhenPresent() throws Exception {
            MimeMessage msg = builder.build(session, account,
                    req("", "cc@example.com", "bcc@example.com", "s", "b", List.of(), null, null));

            assertThat(msg.getRecipients(Message.RecipientType.TO)).isNull();
            assertThat(msg.getRecipients(Message.RecipientType.CC)).extracting(Object::toString)
                    .containsExactly("cc@example.com");
            assertThat(msg.getRecipients(Message.RecipientType.BCC)).extracting(Object::toString)
                    .containsExactly("bcc@example.com");
        }
    }

    @Nested
    @DisplayName("Subject")
    class Subject {

        @Test
        @DisplayName("Null subject -> empty string (a draft without subject is legal)")
        void nullSubjectFallsBackToEmpty() throws Exception {
            MimeMessage msg = builder.build(session, account,
                    req("to@example.com", null, null, null, "b", List.of(), null, null));

            assertThat(msg.getSubject()).isEqualTo("");
        }

        @Test
        @DisplayName("UTF-8 subject with diacritics comes back decoded after the round-trip")
        void utf8SubjectIsPreserved() throws Exception {
            String subject = "Žluťoučký kůň úpěl ďábelské ódy";

            MimeMessage msg = builder.build(session, account,
                    req("to@example.com", null, null, subject, "b", List.of(), null, null));

            // getSubject() internally decodes RFC 2047 — we should get the original
            // diacritics back.
            assertThat(msg.getSubject()).isEqualTo(subject);
        }
    }

    @Nested
    @DisplayName("Body & attachments")
    class BodyAndAttachments {

        @Test
        @DisplayName("Null body -> empty content (draft without body is legal)")
        void nullBodyFallsBackToEmptyContent() throws Exception {
            MimeMessage msg = builder.build(session, account,
                    req("to@example.com", null, null, "s", null, List.of(), null, null));

            Multipart mp = (Multipart) msg.getContent();
            BodyPart text = mp.getBodyPart(0);
            assertThat(text.getContent()).isEqualTo("");
            // Note: content-type of an empty part cannot be reliably verified on an
            // unsaved message (Jakarta Mail defaults to text/plain until saveChanges),
            // so we test it separately on a non-empty body — see
            // {@link #plainBodyHasUtf8ContentType}.
        }

        @Test
        @DisplayName("HTML-like body from compose — content-type is text/plain with charset=UTF-8")
        void plainBodyHasUtf8ContentType() throws Exception {
            String body = "<b>x</b>\n<script>alert('x')</script>";
            MimeMessage msg = builder.build(session, account,
                    req("to@example.com", null, null, "s", body, List.of(), null, null));

            // Jakarta Mail finalizes content-type only on writeTo()/saveChanges() —
            // we test it via the serialized MIME representation that an SMTP server would
            // see.
            ByteArrayOutputStream raw = new ByteArrayOutputStream();
            msg.writeTo(raw);
            String mime = raw.toString(StandardCharsets.US_ASCII);

            assertThat(mime).contains("Content-Type: text/plain");
            assertThat(mime).contains("charset=UTF-8");
            assertThat(mime).doesNotContain("Content-Type: text/html");
            // Raw body part content — visible in the MIME output (before encoding
            // wrapping).
            Multipart mp = (Multipart) msg.getContent();
            assertThat(mp.getBodyPart(0).getContent()).isEqualTo(body);
        }

        @Test
        @DisplayName("Attachment — base64 decoded back to original bytes; content-type and name preserved")
        void attachmentDecodedAndNamed() throws Exception {
            byte[] payload = "Hello, attachment!".getBytes(StandardCharsets.UTF_8);
            String b64 = Base64.getEncoder().encodeToString(payload);

            MailRequest.AttachmentRequest att = new MailRequest.AttachmentRequest("report.txt", "text/plain", b64);

            MimeMessage msg = builder.build(session, account,
                    req("to@example.com", null, null, "s", "b", List.of(att), null, null));

            Multipart mp = (Multipart) msg.getContent();
            assertThat(mp.getCount()).isEqualTo(2); // body + 1 attachment
            BodyPart attPart = mp.getBodyPart(1);

            // Filename: encodeText may return the original string (ASCII) or the
            // RFC 2047 encoded form for non-ASCII — for "report.txt" it is ASCII =
            // passthrough.
            assertThat(attPart.getFileName()).isEqualTo("report.txt");
            assertThat(attPart.getContentType()).startsWith("text/plain");

            // Content — DataHandler gives us an InputStream; we read the bytes.
            try (var in = attPart.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                in.transferTo(out);
                assertThat(out.toByteArray()).isEqualTo(payload);
            }
        }

        @Test
        @DisplayName("Multiple attachments — all added in order")
        void multipleAttachments() throws Exception {
            MailRequest.AttachmentRequest att1 = new MailRequest.AttachmentRequest("a.txt", "text/plain",
                    Base64.getEncoder().encodeToString("A".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            MailRequest.AttachmentRequest att2 = new MailRequest.AttachmentRequest("b.bin", "application/octet-stream",
                    Base64.getEncoder().encodeToString("B".getBytes(java.nio.charset.StandardCharsets.UTF_8)));

            MimeMessage msg = builder.build(session, account,
                    req("to@example.com", null, null, "s", "b", List.of(att1, att2), null, null));

            Multipart mp = (Multipart) msg.getContent();
            assertThat(mp.getCount()).isEqualTo(3); // body + 2 attachments
            assertThat(mp.getBodyPart(1).getFileName()).isEqualTo("a.txt");
            assertThat(mp.getBodyPart(2).getFileName()).isEqualTo("b.bin");
        }
    }

    @Nested
    @DisplayName("Threading headers")
    class ThreadingHeaders {

        @Test
        @DisplayName("inReplyTo + references populated — headers set exactly")
        void headersSetWhenPresent() throws Exception {
            MimeMessage msg = builder.build(session, account, req("to@example.com", null, null, "s", "b", List.of(),
                    "<original@example.com>", "<root@example.com> <original@example.com>"));

            assertThat(msg.getHeader("In-Reply-To")).containsExactly("<original@example.com>");
            assertThat(msg.getHeader("References")).containsExactly("<root@example.com> <original@example.com>");
        }

        @Test
        @DisplayName("inReplyTo and references null/blank — headers are not added at all")
        void headersSkippedWhenBlank() throws Exception {
            MimeMessage msgNull = builder.build(session, account,
                    req("to@example.com", null, null, "s", "b", List.of(), null, null));
            MimeMessage msgBlank = builder.build(session, account,
                    req("to@example.com", null, null, "s", "b", List.of(), "  ", "\t"));

            assertThat(msgNull.getHeader("In-Reply-To")).isNull();
            assertThat(msgNull.getHeader("References")).isNull();
            assertThat(msgBlank.getHeader("In-Reply-To")).isNull();
            assertThat(msgBlank.getHeader("References")).isNull();
        }
    }

    @Test
    @DisplayName("SentDate is set close to Instant.now() (5s tolerance)")
    void sentDateIsRecent() throws Exception {
        Instant before = Instant.now().minusSeconds(5);

        MimeMessage msg = builder.build(session, account,
                req("to@example.com", null, null, "s", "b", List.of(), null, null));

        Instant after = Instant.now().plusSeconds(5);
        assertThat(msg.getSentDate().toInstant()).isAfterOrEqualTo(before).isBeforeOrEqualTo(after);
    }
}
