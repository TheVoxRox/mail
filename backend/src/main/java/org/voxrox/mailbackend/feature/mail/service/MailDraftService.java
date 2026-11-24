package org.voxrox.mailbackend.feature.mail.service;

import jakarta.mail.internet.InternetAddress;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Document.OutputSettings;
import org.jspecify.annotations.Nullable;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;
import org.voxrox.mailbackend.feature.mail.dto.MailRequest;
import org.voxrox.mailbackend.feature.mail.entity.MessageEntity;
import org.voxrox.mailbackend.util.HeaderAddresses;
import org.voxrox.mailbackend.util.SubjectNormalizer;

import module java.base;

@Service
public class MailDraftService {

    /**
     * Medium date, short time: "15. 1. 2026 10:30" in Czech, "Jan 15, 2026, 10:30
     * AM" in English. The stored time is already local, as {@code MessageFetcher}
     * converts it to the system zone, so it is formatted as it stands.
     */
    private static final DateTimeFormatter QUOTE_DATE = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM,
            FormatStyle.SHORT);

    /**
     * Any horizontal space the formatter writes. CLDR puts a narrow no-break space
     * before "AM" since JDK 20 and no-break spaces in other locales; the header is
     * plain text for the recipient's client, and a font without the narrow one
     * shows a box there.
     */
    private static final Pattern FORMATTER_SPACE = Pattern.compile("\\h");

    private final MessageSource messageSource;

    public MailDraftService(MessageSource messageSource) {
        this.messageSource = messageSource;
    }

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

        String quotedBody = buildQuotedBody(original, content, "mail.quote.originalMessage");

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

        return new MailRequest("", "", null, subject, buildQuotedBody(original, content, "mail.quote.forwardedMessage"),
                List.of(), null, original.getReferences());
    }

    /**
     * The original quoted under the user's text. Its labels and date follow the
     * interface language of the request (Accept-Language), as Outlook and
     * Thunderbird write theirs in their own interface language: the recipient's
     * language is not known. They used to be Czech in every language. The dash
     * frame stays in code, because the composer's attachment reminder
     * (frontend/src/lib/compose/attachmentReminder.ts) finds the end of the user's
     * own text by it.
     *
     * <p>
     * The date is formatted in the language of the bundle that answered
     * ({@code app.locale}), not in the request's: a request with no
     * Accept-Language, or with a language no bundle has, gets the default bundle's
     * labels, and a date in the system's language under them would mix two.
     */
    private String buildQuotedBody(MessageEntity entity, String content, String headingKey) {
        Locale locale = LocaleContextHolder.getLocale();
        Locale textLocale = Locale.forLanguageTag(message("app.locale", locale));
        String sender = orFallback(entity.getSender(), "mail.message.unknownSender", locale);
        String subject = orFallback(entity.getSubject(), "mail.message.noSubject", locale);
        String date = FORMATTER_SPACE.matcher(QUOTE_DATE.withLocale(textLocale).format(entity.getReceivedAt()))
                .replaceAll(" ");
        String plainContent = htmlToPlainText(content);
        return "\n\n---------- " + message(headingKey, locale) + " ----------\n"
                + message("mail.quote.from", locale, sender) + "\n" + message("mail.quote.date", locale, date) + "\n"
                + message("mail.quote.subject", locale, subject) + "\n\n" + plainContent;
    }

    /**
     * A missing sender or subject reads as the reading pane names it
     * ({@code MessageMapper}), not as "null".
     */
    private String orFallback(@Nullable String value, String fallbackKey, Locale locale) {
        return value == null || value.isBlank() ? message(fallbackKey, locale) : value;
    }

    private String message(String key, Locale locale, Object... args) {
        return messageSource.getMessage(key, args, locale);
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
