package org.voxrox.mailbackend.feature.mail.service;

import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Document.OutputSettings;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.voxrox.mailbackend.feature.mail.dto.MailRequest;
import org.voxrox.mailbackend.feature.mail.entity.MessageEntity;
import org.voxrox.mailbackend.util.LogCategory;

import module java.base;

@Service
public class MailDraftService {

    private static final Logger log = LoggerFactory.getLogger(MailDraftService.class);

    public MailRequest createReplyDraft(MessageEntity original, String content, boolean replyAll) {
        String myEmail = original.getAccount().getEmail();

        // A subject-less original still gets the "Re: " prefill — the send-side
        // validation requires a non-blank subject anyway.
        String originalSubject = original.getSubject();
        String subject = originalSubject != null ? originalSubject : "";
        if (!subject.toLowerCase(Locale.ROOT).startsWith("re:")) {
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
                quotedBody, Collections.emptyList(), original.getMessageId(), newReferences);
    }

    public MailRequest createForwardDraft(MessageEntity original, String content) {
        String originalSubject = original.getSubject();
        String subject = originalSubject != null ? originalSubject : "";
        if (!subject.toLowerCase(Locale.ROOT).startsWith("fwd:")) {
            subject = "Fwd: " + subject;
        }

        return new MailRequest("", "", null, subject, buildQuotedBody(original, content, "Přeposlaná zpráva"),
                Collections.emptyList(), null, original.getReferences());
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

    private Set<String> extractEmails(@Nullable String rawAddresses, String myEmail) {
        Set<String> emails = new LinkedHashSet<>();
        if (rawAddresses == null || rawAddresses.isBlank())
            return emails;
        try {
            InternetAddress[] parsed = InternetAddress.parse(rawAddresses);
            for (InternetAddress address : parsed) {
                String email = address.getAddress();
                if (email != null && !email.equalsIgnoreCase(myEmail))
                    emails.add(email);
            }
        } catch (AddressException e) {
            log.warn("{} Failed to parse addresses ({} chars): {}", LogCategory.SYNC, rawAddresses.length(),
                    e.getMessage());
        }
        return emails;
    }
}
