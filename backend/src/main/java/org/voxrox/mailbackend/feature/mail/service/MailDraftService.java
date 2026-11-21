package org.voxrox.mailbackend.feature.mail.service;

import jakarta.mail.internet.InternetAddress;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Document.OutputSettings;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.voxrox.mailbackend.feature.mail.dto.MailRequest;
import org.voxrox.mailbackend.feature.mail.entity.MessageEntity;
import org.voxrox.mailbackend.util.HeaderAddresses;
import org.voxrox.mailbackend.util.SubjectNormalizer;

import module java.base;

@Service
public class MailDraftService {

    public MailRequest createReplyDraft(MessageEntity original, String content, boolean replyAll) {
        String myEmail = original.getAccount().getEmail();

        // A subject-less original still gets the "Re: " prefill — the send-side
        // validation requires a non-blank subject anyway.
        String originalSubject = original.getSubject();
        String subject = originalSubject != null ? originalSubject : "";
        // Marker detection is shared with threading (SubjectNormalizer) so the two
        // cannot drift: a local startsWith("re:") missed "Odp:", "AW:", "Re[2]:"
        // and a leading space, and turned a reply to "Odp: Faktura" into
        // "Re: Odp: Faktura".
        if (!SubjectNormalizer.startsWithReplyMarker(subject)) {
            subject = "Re: " + subject;
        }

        String quotedBody = buildQuotedBody(original, content, "Původní zpráva");

        String currentRefs = original.getReferences() != null ? original.getReferences() : "";
        String newReferences = (original.getMessageId() == null)
                ? currentRefs
                : (currentRefs.isEmpty() ? original.getMessageId() : currentRefs + " " + original.getMessageId());

        Set<String> toAddresses = new LinkedHashSet<>();
        Set<String> ccAddresses = new LinkedHashSet<>();

        // getFromEmailOnly() returns "" instead of null, so isBlank() is the right
        // check.
        String senderEmail = original.getFromEmailOnly();
        if (!senderEmail.isBlank() && !senderEmail.equalsIgnoreCase(myEmail)) {
            toAddresses.add(senderEmail);
        }

        if (replyAll) {
            toAddresses.addAll(extractEmails(original.getRecipientsTo(), myEmail));
            ccAddresses.addAll(extractEmails(original.getRecipientsCc(), myEmail));
            if (!senderEmail.isBlank())
                ccAddresses.remove(senderEmail);
        }

        if (toAddresses.isEmpty())
            toAddresses.add(myEmail);

        return new MailRequest(String.join(", ", toAddresses), String.join(", ", ccAddresses), null, subject,
                quotedBody, List.of(), original.getMessageId(), newReferences);
    }

    public MailRequest createForwardDraft(MessageEntity original, String content) {
        String originalSubject = original.getSubject();
        String subject = originalSubject != null ? originalSubject : "";
        if (!SubjectNormalizer.startsWithForwardMarker(subject)) {
            subject = "Fwd: " + subject;
        }

        return new MailRequest("", "", null, subject, buildQuotedBody(original, content, "Přeposlaná zpráva"),
                List.of(), null, original.getReferences());
    }

    private String buildQuotedBody(MessageEntity entity, String content, String label) {
        String plainContent = htmlToPlainText(content);
        return "\n\n" + "---------- " + label + " ----------\n" + "Od: " + entity.getSender() + "\n" + "Datum: "
                + entity.getReceivedAt() + "\n" + "Předmět: " + entity.getSubject() + "\n\n" + plainContent;
    }

    // The compose textarea is a plain-text editor — sanitized HTML from the
    // original message must be flattened to plain text, otherwise the user
    // would see raw tags. Block-level elements become line breaks (the \\n
    // sentinel is rewritten to a real \n after the clean step).
    private static String htmlToPlainText(String content) {
        if (content == null || content.isBlank()) {
            return "";
        }
        Document doc = Jsoup.parse(content);
        doc.outputSettings(new OutputSettings().prettyPrint(false));
        doc.select("br").append("\\n");
        doc.select("p").prepend("\\n\\n");
        String withMarkers = doc.html();
        String text = Jsoup.clean(withMarkers, "", org.jsoup.safety.Safelist.none(),
                new OutputSettings().prettyPrint(false));
        return org.jsoup.parser.Parser.unescapeEntities(text, false).replace("\\n", "\n").trim();
    }

    /**
     * The addresses of a header field the reply-all fills in: the ones the compose
     * form accepts, read as a draft reads its own recipients
     * ({@link HeaderAddresses#parseValidTokens}). One malformed address used to
     * cost the whole field, since the strict parse refuses a field over one token.
     */
    private Set<String> extractEmails(@Nullable String rawAddresses, String myEmail) {
        Set<String> emails = new LinkedHashSet<>();
        if (rawAddresses == null || rawAddresses.isBlank())
            return emails;
        for (InternetAddress address : HeaderAddresses.parseValidTokens(rawAddresses)) {
            String email = address.getAddress();
            if (!email.equalsIgnoreCase(myEmail))
                emails.add(email);
        }
        return emails;
    }
}
