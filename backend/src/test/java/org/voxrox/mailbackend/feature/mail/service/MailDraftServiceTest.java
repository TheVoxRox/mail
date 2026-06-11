package org.voxrox.mailbackend.feature.mail.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.voxrox.mailbackend.feature.account.entity.AccountEntity;
import org.voxrox.mailbackend.feature.mail.dto.MailRequest;
import org.voxrox.mailbackend.feature.mail.entity.MessageEntity;

/**
 * Unit tests for {@link MailDraftService}.
 *
 * Pure logic — no DB, no IMAP, no mocks. Just MessageEntity (POJO) and
 * verification of the MailRequest output.
 *
 * Covers: - Re:/Fwd: prefix idempotency (no "Re: Re: Re:") - Reply-all dedup
 * (sender must not appear in both To and Cc, self-exclusion) - Reply-to-self
 * (replying to your own email) - Threading: References header (concat of the
 * original ones + Message-ID) - Forward: empty To/Cc, no In-Reply-To -
 * getFromEmailOnly() parsing (with and without a display name) - extractEmails
 * malformed-input handling.
 */
class MailDraftServiceTest {

    private static final String MY_EMAIL = "me@example.com";

    private MailDraftService service;
    private AccountEntity myAccount;

    @BeforeEach
    void setUp() {
        service = new MailDraftService();
        myAccount = new AccountEntity();
        myAccount.setEmail(MY_EMAIL);
    }

    private MessageEntity newMessage(String sender, String subject) {
        MessageEntity msg = new MessageEntity();
        msg.setAccount(myAccount);
        msg.setSender(sender);
        msg.setSubject(subject);
        msg.setReceivedAt(LocalDateTime.of(2026, 1, 15, 10, 30));
        return msg;
    }

    @Nested
    @DisplayName("createReplyDraft — subject prefix")
    class ReplySubject {

        @Test
        void shouldPrependReWhenMissing() {
            MessageEntity orig = newMessage("Alice <alice@example.com>", "Hello");

            MailRequest reply = service.createReplyDraft(orig, "my answer", false);

            assertThat(reply.subject()).isEqualTo("Re: Hello");
        }

        @Test
        void shouldNotDoubleReWhenAlreadyPresent() {
            MessageEntity orig = newMessage("Alice <alice@example.com>", "Re: Hello");

            MailRequest reply = service.createReplyDraft(orig, "ans", false);

            assertThat(reply.subject()).isEqualTo("Re: Hello");
        }

        @Test
        void shouldDetectReCaseInsensitively() {
            MessageEntity orig = newMessage("Alice <alice@example.com>", "RE: Hello");

            MailRequest reply = service.createReplyDraft(orig, "ans", false);

            // The original capitalization is preserved — what matters is that a
            // second prefix is NOT added.
            assertThat(reply.subject()).isEqualTo("RE: Hello");
        }
    }

    @Nested
    @DisplayName("createReplyDraft — recipients")
    class ReplyRecipients {

        @Test
        void replyToSenderOnlyWhenNotReplyAll() {
            MessageEntity orig = newMessage("Alice <alice@example.com>", "Hi");
            orig.setRecipientsTo("me@example.com, bob@example.com");
            orig.setRecipientsCc("carol@example.com");

            MailRequest reply = service.createReplyDraft(orig, "x", false);

            assertThat(reply.to()).isEqualTo("alice@example.com");
            assertThat(reply.cc()).isEmpty();
        }

        @Test
        void replyAllShouldIncludeOriginalToAndCcExcludingMe() {
            MessageEntity orig = newMessage("Alice <alice@example.com>", "Hi");
            orig.setRecipientsTo("me@example.com, bob@example.com");
            orig.setRecipientsCc("carol@example.com, dave@example.com");

            MailRequest reply = service.createReplyDraft(orig, "x", true);

            // To: sender + the rest of the original To except me
            assertThat(reply.to()).contains("alice@example.com", "bob@example.com");
            assertThat(reply.to()).doesNotContain("me@example.com");
            // Cc: original Cc except me and except the sender (sender is already in To)
            assertThat(reply.cc()).contains("carol@example.com", "dave@example.com");
            assertThat(reply.cc()).doesNotContain("me@example.com", "alice@example.com");
        }

        @Test
        void replyAllMustNotDuplicateSenderInCcWhenSenderIsAlsoInOriginalCc() {
            // Edge case: someone sent the email and added themselves to Cc.
            MessageEntity orig = newMessage("Alice <alice@example.com>", "Hi");
            orig.setRecipientsTo("me@example.com");
            orig.setRecipientsCc("alice@example.com, bob@example.com");

            MailRequest reply = service.createReplyDraft(orig, "x", true);

            assertThat(reply.to()).contains("alice@example.com");
            assertThat(reply.cc()).doesNotContain("alice@example.com");
            assertThat(reply.cc()).contains("bob@example.com");
        }

        @Test
        void replyToOwnEmailShouldFallbackToSelf() {
            // When replying to an email sent to myself (or the sender is blank),
            // To must not be empty — fall back to my own address, otherwise the
            // frontend would not know where to send.
            MessageEntity orig = newMessage("me@example.com", "Note to self");

            MailRequest reply = service.createReplyDraft(orig, "x", false);

            assertThat(reply.to()).isEqualTo(MY_EMAIL);
        }

        @Test
        void replyToBlankSenderShouldFallbackToSelf() {
            MessageEntity orig = newMessage("", "Hi");

            MailRequest reply = service.createReplyDraft(orig, "x", false);

            assertThat(reply.to()).isEqualTo(MY_EMAIL);
        }
    }

    @Nested
    @DisplayName("createReplyDraft — threading headers")
    class ReplyThreading {

        @Test
        void shouldSetInReplyToFromOriginalMessageId() {
            MessageEntity orig = newMessage("Alice <alice@example.com>", "Hi");
            orig.setMessageId("<msg-1@example.com>");

            MailRequest reply = service.createReplyDraft(orig, "x", false);

            assertThat(reply.inReplyTo()).isEqualTo("<msg-1@example.com>");
        }

        @Test
        void referencesShouldConcatExistingReferencesAndOriginalMessageId() {
            MessageEntity orig = newMessage("Alice <alice@example.com>", "Hi");
            orig.setMessageId("<msg-3@example.com>");
            orig.setReferences("<msg-1@example.com> <msg-2@example.com>");

            MailRequest reply = service.createReplyDraft(orig, "x", false);

            assertThat(reply.references()).isEqualTo("<msg-1@example.com> <msg-2@example.com> <msg-3@example.com>");
        }

        @Test
        void referencesFirstReplyInThreadShouldEqualOriginalMessageId() {
            // First reply in the thread — orig has no References, only Message-ID.
            MessageEntity orig = newMessage("Alice <alice@example.com>", "Hi");
            orig.setMessageId("<msg-1@example.com>");

            MailRequest reply = service.createReplyDraft(orig, "x", false);

            assertThat(reply.references()).isEqualTo("<msg-1@example.com>");
        }

        @Test
        void shouldHandleNullMessageIdGracefully() {
            MessageEntity orig = newMessage("Alice <alice@example.com>", "Hi");
            orig.setMessageId(null);
            orig.setReferences("<msg-1@example.com>");

            MailRequest reply = service.createReplyDraft(orig, "x", false);

            assertThat(reply.inReplyTo()).isNull();
            // Without a new Message-ID, references do not change.
            assertThat(reply.references()).isEqualTo("<msg-1@example.com>");
        }
    }

    @Nested
    @DisplayName("createReplyDraft — body")
    class ReplyBody {

        @Test
        void bodyShouldContainUserContentAndQuotedOriginal() {
            MessageEntity orig = newMessage("Alice <alice@example.com>", "Hi");

            MailRequest reply = service.createReplyDraft(orig, "Thanks for the info!", false);

            assertThat(reply.body()).contains("Thanks for the info!").contains("Původní zpráva")
                    .contains("Alice <alice@example.com>").contains("Hi");
        }
    }

    @Nested
    @DisplayName("createForwardDraft")
    class Forward {

        @Test
        void shouldPrependFwdWhenMissing() {
            MessageEntity orig = newMessage("Alice <alice@example.com>", "Quarterly report");

            MailRequest fwd = service.createForwardDraft(orig, "FYI");

            assertThat(fwd.subject()).isEqualTo("Fwd: Quarterly report");
        }

        @Test
        void shouldNotDoubleFwd() {
            MessageEntity orig = newMessage("Alice <alice@example.com>", "Fwd: Quarterly report");

            MailRequest fwd = service.createForwardDraft(orig, "FYI");

            assertThat(fwd.subject()).isEqualTo("Fwd: Quarterly report");
        }

        @Test
        void forwardShouldHaveEmptyToAndCc() {
            // Forward draft = the user picks the recipients themselves.
            MessageEntity orig = newMessage("Alice <alice@example.com>", "Hi");
            orig.setRecipientsTo("bob@example.com");
            orig.setRecipientsCc("carol@example.com");

            MailRequest fwd = service.createForwardDraft(orig, "FYI");

            assertThat(fwd.to()).isEmpty();
            assertThat(fwd.cc()).isEmpty();
        }

        @Test
        void forwardMustNotSetInReplyTo() {
            // Forward is NOT a reply — In-Reply-To would confuse threading.
            MessageEntity orig = newMessage("Alice <alice@example.com>", "Hi");
            orig.setMessageId("<msg-1@example.com>");

            MailRequest fwd = service.createForwardDraft(orig, "FYI");

            assertThat(fwd.inReplyTo()).isNull();
        }

        @Test
        void forwardBodyShouldContainUserContentAndQuotedOriginal() {
            MessageEntity orig = newMessage("Alice <alice@example.com>", "Hi");

            MailRequest fwd = service.createForwardDraft(orig, "FYI all");

            assertThat(fwd.body()).contains("FYI all").contains("Přeposlaná zpráva")
                    .contains("Alice <alice@example.com>");
        }
    }

    @Nested
    @DisplayName("getFromEmailOnly parsing")
    class FromEmailParsing {

        @Test
        void shouldExtractEmailFromAngleBrackets() {
            MessageEntity orig = newMessage("Alice Smith <alice@example.com>", "Hi");

            MailRequest reply = service.createReplyDraft(orig, "x", false);

            assertThat(reply.to()).isEqualTo("alice@example.com");
        }

        @Test
        void shouldUsePlainEmailWhenNoAngleBrackets() {
            MessageEntity orig = newMessage("alice@example.com", "Hi");

            MailRequest reply = service.createReplyDraft(orig, "x", false);

            assertThat(reply.to()).isEqualTo("alice@example.com");
        }
    }

    @Nested
    @DisplayName("extractEmails — malformed input handling")
    class ExtractEmails {

        @Test
        void replyAllWithMalformedToAddressShouldNotCrash() {
            // If the original message contains an invalid address, the parser throws
            // AddressException. That must not break the whole reply flow — the service
            // must log it and continue.
            MessageEntity orig = newMessage("Alice <alice@example.com>", "Hi");
            orig.setRecipientsTo("not-an-email!!! <<<");
            orig.setRecipientsCc("bob@example.com");

            MailRequest reply = service.createReplyDraft(orig, "x", true);

            // Sender is always in To.
            assertThat(reply.to()).contains("alice@example.com");
            // Cc with a valid address passed through — the malformed To field was isolated.
            assertThat(reply.cc()).contains("bob@example.com");
        }

        @Test
        void replyAllWithBlankRecipientsIsHandled() {
            MessageEntity orig = newMessage("Alice <alice@example.com>", "Hi");
            orig.setRecipientsTo("");
            orig.setRecipientsCc(null);

            MailRequest reply = service.createReplyDraft(orig, "x", true);

            assertThat(reply.to()).isEqualTo("alice@example.com");
            assertThat(reply.cc()).isEmpty();
        }
    }
}
