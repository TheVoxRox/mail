package org.voxrox.mailbackend.feature.mail.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import jakarta.mail.Address;
import jakarta.mail.FetchProfile;
import jakarta.mail.Flags;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.UIDFolder;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.voxrox.mailbackend.feature.mail.dto.MailDetailResponse;

/**
 * Unit tests for {@link MessageFetcher}.
 *
 * Strategy: - Real {@link MimeMessage} instances constructed from RFC 822 raw
 * bytes (cleaner than mocking every jakarta.mail method and also exercises the
 * real parser). - {@link Folder}/{@link UIDFolder} as a Mockito mock with an
 * extra interface ({@code extraInterfaces}) — fetch() is a no-op (raw messages
 * already have headers), getUID(msg) returns the UID from a map keyed by
 * identity. - For the "non-MimeMessage" test branch a pure {@link Message} mock
 * is used because a MimeMessage would always satisfy instanceof.
 *
 * Covers: - Empty/null input -> empty list - uidFolder that is not a Folder ->
 * empty list (defensive instanceof check) - Happy path: subject, sender, to,
 * cc, flags, threading headers - Subject null/blank -> null for later
 * localization in the mapper - From null/empty -> null for later localization -
 * To/Cc null -> null in DTO; multiple addresses -> joined with ", " -
 * receivedDate preferred over sentDate; both null -> fallback to now() -
 * SEEN/FLAGGED/ANSWERED flags - Multipart with attachment ->
 * hasAttachments=true - Text-only -> hasAttachments=false - Threading:
 * Message-ID, In-Reply-To, References - Non-MimeMessage -> messageId=null -
 * Per-message MessagingException -> skip + continue with the rest -
 * folder.fetch() throws -> empty list - body, contentError, stableId are always
 * null (sync does not populate content).
 */
class MessageFetcherTest {

    private MessageFetcher fetcher;
    private Folder folder; // also implements UIDFolder
    private UIDFolder uidFolder;
    private Map<Message, Long> uidMap;

    @BeforeEach
    void setUp() throws Exception {
        fetcher = new MessageFetcher();
        // The mock implements both interfaces — matches reality (IMAPFolder).
        folder = mock(Folder.class, withSettings().extraInterfaces(UIDFolder.class));
        uidFolder = (UIDFolder) folder;
        uidMap = new HashMap<>();

        // fetch() is a no-op in the test — raw MimeMessages already carry headers.
        doNothing().when(folder).fetch(any(Message[].class), any(FetchProfile.class));

        when(uidFolder.getUID(any(Message.class))).thenAnswer(inv -> uidMap.getOrDefault(inv.getArgument(0), 0L));
    }

    private static MimeMessage parse(String raw) throws MessagingException {
        Session session = Session.getInstance(new Properties());
        return new MimeMessage(session, new ByteArrayInputStream(raw.getBytes(StandardCharsets.UTF_8)));
    }

    private MimeMessage register(MimeMessage msg, long uid) {
        uidMap.put(msg, uid);
        return msg;
    }

    private static String simpleEmail(String subject, String from, String to) {
        return "" + "From: " + from + "\r\n" + "To: " + to + "\r\n" + "Subject: " + subject + "\r\n"
                + "Date: Thu, 15 Jan 2026 10:30:00 +0000\r\n" + "Message-ID: <abc@example.com>\r\n"
                + "Content-Type: text/plain; charset=UTF-8\r\n" + "\r\n" + "Hello body\r\n";
    }

    @Nested
    @DisplayName("Edge inputs")
    class EdgeInputs {

        @Test
        void nullMessagesReturnsEmpty() {
            assertThat(fetcher.fetchBatch(null, uidFolder, "INBOX")).isEmpty();
        }

        @Test
        void emptyMessagesReturnsEmpty() {
            assertThat(fetcher.fetchBatch(new Message[0], uidFolder, "INBOX")).isEmpty();
        }

        @Test
        void uidFolderNotFolderReturnsEmpty() throws Exception {
            // A UIDFolder that is NOT a Folder — defensive instanceof check.
            UIDFolder pureUid = mock(UIDFolder.class);
            MimeMessage msg = register(parse(simpleEmail("Hi", "a@x", "b@y")), 1L);

            assertThat(fetcher.fetchBatch(new Message[]{msg}, pureUid, "INBOX")).isEmpty();
        }

        @Test
        void folderFetchThrowingReturnsEmpty() throws Exception {
            doThrow(new MessagingException("boom")).when(folder).fetch(any(Message[].class), any(FetchProfile.class));
            MimeMessage msg = register(parse(simpleEmail("Hi", "a@x", "b@y")), 1L);

            assertThat(fetcher.fetchBatch(new Message[]{msg}, uidFolder, "INBOX")).isEmpty();
        }
    }

    @Nested
    @DisplayName("Field mapping")
    class Mapping {

        @Test
        void happyPathPopulatesAllBasicFields() throws Exception {
            String raw = "" + "From: \"Alice\" <alice@example.com>\r\n" + "To: bob@example.com\r\n"
                    + "Cc: carol@example.com\r\n" + "Subject: Hello world\r\n"
                    + "Date: Thu, 15 Jan 2026 10:30:00 +0000\r\n" + "Message-ID: <msg-1@example.com>\r\n"
                    + "In-Reply-To: <prev@example.com>\r\n" + "References: <root@example.com> <prev@example.com>\r\n"
                    + "Content-Type: text/plain; charset=UTF-8\r\n" + "\r\n" + "Body text\r\n";
            MimeMessage msg = register(parse(raw), 42L);

            var batch = fetcher.fetchBatch(new Message[]{msg}, uidFolder, "INBOX");

            assertThat(batch).hasSize(1);
            MailDetailResponse dto = batch.get(0);
            assertThat(dto.uid()).isEqualTo(42L);
            assertThat(dto.subject()).isEqualTo("Hello world");
            assertThat(dto.sender()).contains("alice@example.com");
            assertThat(dto.recipientsTo()).contains("bob@example.com");
            assertThat(dto.recipientsCc()).contains("carol@example.com");
            assertThat(dto.messageId()).isEqualTo("<msg-1@example.com>");
            assertThat(dto.inReplyTo()).isEqualTo("<prev@example.com>");
            assertThat(dto.references()).isEqualTo("<root@example.com> <prev@example.com>");
            assertThat(dto.hasAttachments()).isFalse();
            assertThat(dto.attachments()).isEmpty();
        }

        @Test
        void syncOnlyFieldsAreNull() throws Exception {
            // body / contentError / stableId are never populated by MessageFetcher —
            // they belong to the persistence layer.
            MimeMessage msg = register(parse(simpleEmail("Hi", "a@x", "b@y")), 1L);

            var dto = fetcher.fetchBatch(new Message[]{msg}, uidFolder, "INBOX").get(0);

            assertThat(dto.stableId()).isNull();
            assertThat(dto.body()).isNull();
            assertThat(dto.contentError()).isNull();
        }

        @Test
        void blankSubjectFallsBack() throws Exception {
            String raw = "" + "From: a@x\r\n" + "To: b@y\r\n" + "Subject:    \r\n" + "\r\n" + "x\r\n";
            MimeMessage msg = register(parse(raw), 1L);

            var dto = fetcher.fetchBatch(new Message[]{msg}, uidFolder, "INBOX").get(0);
            assertThat(dto.subject()).isNull();
        }

        @Test
        void missingSubjectHeaderFallsBack() throws Exception {
            String raw = "" + "From: a@x\r\n" + "To: b@y\r\n" + "\r\n" + "x\r\n";
            MimeMessage msg = register(parse(raw), 1L);

            var dto = fetcher.fetchBatch(new Message[]{msg}, uidFolder, "INBOX").get(0);
            assertThat(dto.subject()).isNull();
        }

        @Test
        void missingFromFallsBack() throws Exception {
            // MimeMessage without From — getFrom() returns null and the mapper supplies the
            // output label.
            String raw = "" + "To: b@y\r\n" + "Subject: x\r\n" + "\r\n" + "x\r\n";
            MimeMessage msg = register(parse(raw), 1L);

            var dto = fetcher.fetchBatch(new Message[]{msg}, uidFolder, "INBOX").get(0);
            assertThat(dto.sender()).isNull();
        }

        @Test
        void missingRecipientsAreNull() throws Exception {
            String raw = "" + "From: a@x\r\n" + "Subject: x\r\n" + "\r\n" + "x\r\n";
            MimeMessage msg = register(parse(raw), 1L);

            var dto = fetcher.fetchBatch(new Message[]{msg}, uidFolder, "INBOX").get(0);
            assertThat(dto.recipientsTo()).isNull();
            assertThat(dto.recipientsCc()).isNull();
        }

        @Test
        void multipleRecipientsAreJoinedWithComma() throws Exception {
            String raw = "" + "From: a@x\r\n" + "To: one@x, two@x, three@x\r\n" + "Cc: cc1@x, cc2@x\r\n"
                    + "Subject: x\r\n" + "\r\n" + "x\r\n";
            MimeMessage msg = register(parse(raw), 1L);

            var dto = fetcher.fetchBatch(new Message[]{msg}, uidFolder, "INBOX").get(0);
            assertThat(dto.recipientsTo()).contains("one@x", "two@x", "three@x");
            assertThat(dto.recipientsTo().split(", ")).hasSize(3);
            assertThat(dto.recipientsCc().split(", ")).hasSize(2);
        }

        @Test
        void missingThreadingHeadersAreNull() throws Exception {
            String raw = "" + "From: a@x\r\n" + "To: b@y\r\n" + "Subject: x\r\n" + "\r\n" + "x\r\n";
            MimeMessage msg = register(parse(raw), 1L);

            var dto = fetcher.fetchBatch(new Message[]{msg}, uidFolder, "INBOX").get(0);
            assertThat(dto.messageId()).isNull();
            assertThat(dto.inReplyTo()).isNull();
            assertThat(dto.references()).isNull();
        }
    }

    @Nested
    @DisplayName("Flags")
    class FlagTests {

        @Test
        void noFlagsByDefault() throws Exception {
            MimeMessage msg = register(parse(simpleEmail("x", "a@x", "b@y")), 1L);

            var dto = fetcher.fetchBatch(new Message[]{msg}, uidFolder, "INBOX").get(0);
            assertThat(dto.seen()).isFalse();
            assertThat(dto.flagged()).isFalse();
            assertThat(dto.answered()).isFalse();
        }

        @Test
        void allFlagsSet() throws Exception {
            MimeMessage msg = register(parse(simpleEmail("x", "a@x", "b@y")), 1L);
            msg.setFlag(Flags.Flag.SEEN, true);
            msg.setFlag(Flags.Flag.FLAGGED, true);
            msg.setFlag(Flags.Flag.ANSWERED, true);

            var dto = fetcher.fetchBatch(new Message[]{msg}, uidFolder, "INBOX").get(0);
            assertThat(dto.seen()).isTrue();
            assertThat(dto.flagged()).isTrue();
            assertThat(dto.answered()).isTrue();
        }

        @Test
        void onlySeenSet() throws Exception {
            MimeMessage msg = register(parse(simpleEmail("x", "a@x", "b@y")), 1L);
            msg.setFlag(Flags.Flag.SEEN, true);

            var dto = fetcher.fetchBatch(new Message[]{msg}, uidFolder, "INBOX").get(0);
            assertThat(dto.seen()).isTrue();
            assertThat(dto.flagged()).isFalse();
            assertThat(dto.answered()).isFalse();
        }
    }

    @Nested
    @DisplayName("Date")
    class DateHandling {

        @Test
        void receivedDatePreferredOverSentDate() throws Exception {
            // MimeMessage from bytes has no receivedDate (= null) — override via subclass.
            Date received = Date.from(LocalDateTime.of(2026, 3, 1, 12, 0).atZone(ZoneId.systemDefault()).toInstant());
            Session session = Session.getInstance(new Properties());
            MimeMessage msg = new MimeMessage(session,
                    new ByteArrayInputStream(simpleEmail("x", "a@x", "b@y").getBytes(StandardCharsets.UTF_8))) {
                @Override
                public Date getReceivedDate() {
                    return received;
                }
            };
            register(msg, 1L);

            var dto = fetcher.fetchBatch(new Message[]{msg}, uidFolder, "INBOX").get(0);
            // receivedDate was March 1, 2026
            assertThat(dto.receivedAt().getYear()).isEqualTo(2026);
            assertThat(dto.receivedAt().getMonthValue()).isEqualTo(3);
            assertThat(dto.receivedAt().getDayOfMonth()).isEqualTo(1);
        }

        @Test
        void fallsBackToSentDateWhenReceivedNull() throws Exception {
            // Without the subclass override -> getReceivedDate() = null, the sent date
            // from the Date header is used.
            MimeMessage msg = register(parse(simpleEmail("x", "a@x", "b@y")), 1L);

            var dto = fetcher.fetchBatch(new Message[]{msg}, uidFolder, "INBOX").get(0);
            // Date header: Thu, 15 Jan 2026 10:30:00 +0000
            assertThat(dto.receivedAt().getYear()).isEqualTo(2026);
            assertThat(dto.receivedAt().getMonthValue()).isEqualTo(1);
            assertThat(dto.receivedAt().getDayOfMonth()).isEqualTo(15);
        }

        @Test
        void fallsBackToNowWhenBothDatesNull() throws Exception {
            String raw = "" + "From: a@x\r\n" + "To: b@y\r\n" + "Subject: x\r\n" + "\r\n" + "x\r\n";
            MimeMessage msg = register(parse(raw), 1L);

            LocalDateTime before = LocalDateTime.now().minusSeconds(2);
            var dto = fetcher.fetchBatch(new Message[]{msg}, uidFolder, "INBOX").get(0);
            LocalDateTime after = LocalDateTime.now().plusSeconds(2);

            assertThat(dto.receivedAt()).isBetween(before, after);
        }
    }

    @Nested
    @DisplayName("Attachments")
    class Attachments {

        @Test
        void textOnlyHasNoAttachments() throws Exception {
            MimeMessage msg = register(parse(simpleEmail("x", "a@x", "b@y")), 1L);

            var dto = fetcher.fetchBatch(new Message[]{msg}, uidFolder, "INBOX").get(0);
            assertThat(dto.hasAttachments()).isFalse();
            assertThat(dto.attachments()).isEmpty();
        }

        @Test
        void multipartWithAttachmentDetected() throws Exception {
            String boundary = "----=_BOUNDARY_42";
            String raw = "" + "From: a@x\r\n" + "To: b@y\r\n" + "Subject: with attachment\r\n" + "MIME-Version: 1.0\r\n"
                    + "Content-Type: multipart/mixed; boundary=\"" + boundary + "\"\r\n" + "\r\n" + "--" + boundary
                    + "\r\n" + "Content-Type: text/plain; charset=UTF-8\r\n" + "\r\n" + "Body text\r\n" + "--"
                    + boundary + "\r\n" + "Content-Type: application/pdf; name=\"doc.pdf\"\r\n"
                    + "Content-Disposition: attachment; filename=\"doc.pdf\"\r\n"
                    + "Content-Transfer-Encoding: base64\r\n" + "\r\n" + "JVBERi0xLjQK\r\n" + "--" + boundary
                    + "--\r\n";
            MimeMessage msg = register(parse(raw), 1L);

            var dto = fetcher.fetchBatch(new Message[]{msg}, uidFolder, "INBOX").get(0);
            assertThat(dto.hasAttachments()).isTrue();
            assertThat(dto.attachments()).hasSize(1);
            assertThat(dto.attachments().get(0).fileName()).isEqualTo("doc.pdf");
        }
    }

    @Nested
    @DisplayName("Resilience")
    class Resilience {

        @Test
        void perMessageExceptionSkipsAndContinues() throws Exception {
            MimeMessage good = register(parse(simpleEmail("Good", "a@x", "b@y")), 1L);

            // A mock that throws on getSubject — fails in mapToResponse, not in fetch.
            Message bad = mock(Message.class);
            when(bad.getSubject()).thenThrow(new MessagingException("subject read failed"));
            uidMap.put(bad, 2L);

            var batch = fetcher.fetchBatch(new Message[]{good, bad}, uidFolder, "INBOX");

            assertThat(batch).hasSize(1);
            assertThat(batch.get(0).subject()).isEqualTo("Good");
            assertThat(batch.get(0).uid()).isEqualTo(1L);
        }

        @Test
        @DisplayName("RFC 2047 encoded-word sender is decoded (real seznam.cz newsletter example)")
        void encodedWordSenderIsDecoded() throws Exception {
            // Trenýrkárna.cz newsletter — the original report from the user.
            String raw = "" + "From: =?utf-8?q?Tren=C3=BDrk=C3=A1rna=2Ecz?= <novinky@newsletter.trenyrkarna.cz>\r\n"
                    + "To: b@y\r\n" + "Subject: x\r\n" + "Date: Thu, 15 Jan 2026 10:30:00 +0000\r\n" + "\r\n" + "x\r\n";
            MimeMessage msg = register(parse(raw), 1L);

            var dto = fetcher.fetchBatch(new Message[]{msg}, uidFolder, "INBOX").get(0);
            assertThat(dto.sender()).isEqualTo("Trenýrkárna.cz <novinky@newsletter.trenyrkarna.cz>");
        }

        @Test
        @DisplayName("RFC 2047 encoded-word recipients (To, Cc) are decoded and joined")
        void encodedWordRecipientsAreDecoded() throws Exception {
            String raw = "" + "From: a@x\r\n" + "To: =?utf-8?q?Bohum=C3=ADr?= <bohumir@example.com>\r\n"
                    + "Cc: =?utf-8?B?xb1vZmll?= <zofie@example.com>\r\n" + "Subject: x\r\n" + "\r\n" + "x\r\n";
            MimeMessage msg = register(parse(raw), 1L);

            var dto = fetcher.fetchBatch(new Message[]{msg}, uidFolder, "INBOX").get(0);
            assertThat(dto.recipientsTo()).isEqualTo("Bohumír <bohumir@example.com>");
            assertThat(dto.recipientsCc()).isEqualTo("Žofie <zofie@example.com>");
        }

        @Test
        @DisplayName("formatAddress: plain email without personal name returns the email itself")
        void formatAddressPlainEmail() throws Exception {
            assertThat(MessageFetcher.formatAddress(new InternetAddress("alice@example.com")))
                    .isEqualTo("alice@example.com");
        }

        @Test
        @DisplayName("formatAddress: null input returns null")
        void formatAddressNullSafe() {
            assertThat(MessageFetcher.formatAddress(null)).isNull();
        }

        @Test
        @DisplayName("BODYSTRUCTURE failure during MIME walk produces envelope-only stub with contentError")
        void bodystructureFailureProducesEnvelopeOnlyStubWithContentError() throws Exception {
            // Simulates Seznam returning malformed BODYSTRUCTURE for a single UID:
            // envelope fields are readable (from ENVELOPE/INTERNALDATE/FLAGS), but
            // isMimeType triggers a lazy BODYSTRUCTURE fetch that fails.
            Message msg = mock(Message.class);
            when(msg.getSubject()).thenReturn("Newsletter");
            when(msg.getFrom()).thenReturn(new Address[]{new InternetAddress("a@x")});
            when(msg.getRecipients(Message.RecipientType.TO)).thenReturn(new Address[]{new InternetAddress("b@y")});
            when(msg.getRecipients(Message.RecipientType.CC)).thenReturn(null);
            when(msg.getReceivedDate()).thenReturn(null);
            when(msg.getSentDate()).thenReturn(null);
            when(msg.getFlags()).thenReturn(new Flags());
            when(msg.getHeader("In-Reply-To")).thenReturn(null);
            when(msg.getHeader("References")).thenReturn(null);
            when(msg.isMimeType("multipart/*")).thenThrow(new MessagingException("Unable to load BODYSTRUCTURE"));
            uidMap.put(msg, 99L);

            var batch = fetcher.fetchBatch(new Message[]{msg}, uidFolder, "INBOX");

            assertThat(batch).hasSize(1);
            MailDetailResponse dto = batch.get(0);
            assertThat(dto.uid()).isEqualTo(99L);
            assertThat(dto.subject()).isEqualTo("Newsletter");
            assertThat(dto.sender()).contains("a@x");
            assertThat(dto.recipientsTo()).contains("b@y");
            assertThat(dto.attachments()).isEmpty();
            assertThat(dto.hasAttachments()).isFalse();
            assertThat(dto.contentError()).contains("BODYSTRUCTURE");
        }

        @Test
        @DisplayName("Threading header read failure does not drop the message")
        void headerReadFailureKeepsEnvelopeWithoutThreadingFields() throws Exception {
            // Simulates a message that was expunged on the server between batch fetch
            // and per-message processing: envelope is still readable from the cached
            // FetchResponse, but a lazy header re-fetch throws.
            Message msg = mock(Message.class);
            when(msg.getSubject()).thenReturn("Standalone");
            when(msg.getFrom()).thenReturn(new Address[]{new InternetAddress("a@x")});
            when(msg.getRecipients(Message.RecipientType.TO)).thenReturn(new Address[]{new InternetAddress("b@y")});
            when(msg.getRecipients(Message.RecipientType.CC)).thenReturn(null);
            when(msg.getReceivedDate()).thenReturn(null);
            when(msg.getSentDate()).thenReturn(null);
            when(msg.getFlags()).thenReturn(new Flags());
            when(msg.getHeader("In-Reply-To")).thenThrow(new MessagingException("Folder is not open"));
            when(msg.getHeader("References")).thenThrow(new MessagingException("Folder is not open"));
            // Non-MimeMessage path so messageId stays null without triggering
            // MimeMessage.getMessageID.
            when(msg.isMimeType("multipart/*")).thenReturn(false);
            when(msg.getFileName()).thenReturn(null);
            when(msg.getDisposition()).thenReturn(null);
            uidMap.put(msg, 55L);

            var batch = fetcher.fetchBatch(new Message[]{msg}, uidFolder, "INBOX");

            assertThat(batch).hasSize(1);
            MailDetailResponse dto = batch.get(0);
            assertThat(dto.uid()).isEqualTo(55L);
            assertThat(dto.subject()).isEqualTo("Standalone");
            assertThat(dto.sender()).contains("a@x");
            assertThat(dto.inReplyTo()).isNull();
            assertThat(dto.references()).isNull();
            assertThat(dto.messageId()).isNull();
            // Header miss is not a content failure — contentError stays null.
            assertThat(dto.contentError()).isNull();
        }

        @Test
        @DisplayName("Mixed batch: BODYSTRUCTURE-failing message is persisted as stub alongside good ones")
        void mixedBatchKeepsBothGoodAndStubbedMessages() throws Exception {
            MimeMessage good = register(parse(simpleEmail("Good", "a@x", "b@y")), 1L);

            Message bad = mock(Message.class);
            when(bad.getSubject()).thenReturn("Bad");
            when(bad.getFrom()).thenReturn(new Address[]{new InternetAddress("c@x")});
            when(bad.getRecipients(Message.RecipientType.TO)).thenReturn(null);
            when(bad.getRecipients(Message.RecipientType.CC)).thenReturn(null);
            when(bad.getReceivedDate()).thenReturn(null);
            when(bad.getSentDate()).thenReturn(null);
            when(bad.getFlags()).thenReturn(new Flags());
            when(bad.getHeader("In-Reply-To")).thenReturn(null);
            when(bad.getHeader("References")).thenReturn(null);
            when(bad.isMimeType("multipart/*")).thenThrow(new MessagingException("Unable to load BODYSTRUCTURE"));
            uidMap.put(bad, 2L);

            var batch = fetcher.fetchBatch(new Message[]{good, bad}, uidFolder, "INBOX");

            assertThat(batch).hasSize(2);
            MailDetailResponse goodDto = batch.stream().filter(d -> d.uid() == 1L).findFirst().orElseThrow();
            MailDetailResponse badDto = batch.stream().filter(d -> d.uid() == 2L).findFirst().orElseThrow();
            assertThat(goodDto.subject()).isEqualTo("Good");
            assertThat(goodDto.contentError()).isNull();
            assertThat(badDto.subject()).isEqualTo("Bad");
            assertThat(badDto.contentError()).contains("BODYSTRUCTURE");
            assertThat(badDto.hasAttachments()).isFalse();
        }

        @Test
        void nonMimeMessageHasNullMessageId() throws Exception {
            // Pure Message mock — it does not implement MimeMessage, so instanceof
            // fails and messageId stays null. Other fields are populated from mock stubs.
            Message msg = mock(Message.class);
            when(msg.getSubject()).thenReturn("Plain");
            when(msg.getFrom()).thenReturn(new Address[0]);
            when(msg.getRecipients(Message.RecipientType.TO)).thenReturn(null);
            when(msg.getRecipients(Message.RecipientType.CC)).thenReturn(null);
            when(msg.getReceivedDate()).thenReturn(null);
            when(msg.getSentDate()).thenReturn(null);
            when(msg.getFlags()).thenReturn(new Flags());
            when(msg.getHeader("In-Reply-To")).thenReturn(null);
            when(msg.getHeader("References")).thenReturn(null);
            // MimePartExtractor.extractAttachmentMetadata calls isMimeType -> false = no
            // attachments
            when(msg.isMimeType("multipart/*")).thenReturn(false);
            when(msg.getFileName()).thenReturn(null);
            when(msg.getDisposition()).thenReturn(null);
            uidMap.put(msg, 7L);

            var batch = fetcher.fetchBatch(new Message[]{msg}, uidFolder, "INBOX");

            assertThat(batch).hasSize(1);
            MailDetailResponse dto = batch.get(0);
            assertThat(dto.uid()).isEqualTo(7L);
            assertThat(dto.subject()).isEqualTo("Plain");
            assertThat(dto.sender()).isNull();
            assertThat(dto.messageId()).isNull(); // key: instanceof MimeMessage = false
            assertThat(dto.hasAttachments()).isFalse();
        }
    }
}
