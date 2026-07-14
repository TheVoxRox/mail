package org.voxrox.mailbackend.feature.mail.service;

import java.io.UnsupportedEncodingException;

import jakarta.mail.*;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeUtility;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.voxrox.mailbackend.feature.mail.dto.AttachmentResponse;
import org.voxrox.mailbackend.feature.mail.dto.MailDetailResponse;
import org.voxrox.mailbackend.util.LogCategory;
import org.voxrox.mailbackend.util.MimePartExtractor;

import module java.base;

@Service
public class MessageFetcher {
    private static final Logger log = LoggerFactory.getLogger(MessageFetcher.class);

    public List<MailDetailResponse> fetchBatch(Message[] messages, UIDFolder uidFolder, String folderName) {
        if (messages == null || messages.length == 0) {
            return new ArrayList<>();
        }

        /*
         * In JavaMail UIDFolder typically implements IMAPFolder (= Folder), but the
         * interface does not guarantee it — verify the runtime type.
         */
        if (!(uidFolder instanceof Folder folder)) {
            log.error("{} UIDFolder is not an instance of Folder ({}), batch fetch skipped.", LogCategory.IMAP,
                    uidFolder.getClass().getName());
            return new ArrayList<>();
        }

        try {
            FetchProfile fetchProfile = new FetchProfile();
            fetchProfile.add(FetchProfile.Item.ENVELOPE);
            fetchProfile.add(UIDFolder.FetchProfileItem.UID);
            fetchProfile.add(FetchProfile.Item.FLAGS);
            fetchProfile.add(FetchProfile.Item.CONTENT_INFO);
            fetchProfile.add("Message-ID");
            fetchProfile.add("In-Reply-To");
            fetchProfile.add("References");

            folder.fetch(messages, fetchProfile);

            List<MailDetailResponse> responses = new ArrayList<>();
            for (Message message : messages) {
                try {
                    responses.add(mapToResponse(message, uidFolder, folderName));
                } catch (MessagingException | IOException e) {
                    log.error("{} Failed to map a message in {}: {}", LogCategory.IMAP, folderName, e.getMessage());
                }
            }
            return responses;
        } catch (MessagingException e) {
            log.error("{} Critical IMAP communication error in {}: {}", LogCategory.IMAP, folderName, e.getMessage());
            return new ArrayList<>();
        }
    }

    private MailDetailResponse mapToResponse(Message message, UIDFolder uidFolder, String folderName)
            throws MessagingException, IOException {
        long uid = uidFolder.getUID(message);

        String subject = message.getSubject();
        String safeSubject = (subject != null && !subject.isBlank()) ? subject : null;

        String sender = null;
        Address[] from = message.getFrom();
        if (from != null && from.length > 0) {
            sender = formatAddress(from[0]);
        }

        String to = joinAddresses(message.getRecipients(Message.RecipientType.TO));
        String cc = joinAddresses(message.getRecipients(Message.RecipientType.CC));
        // Present only on the user's own draft/sent copies — received mail never
        // carries the header. Needed so a reopened draft round-trips its Bcc.
        String bcc = joinAddresses(message.getRecipients(Message.RecipientType.BCC));

        Date date = (message.getReceivedDate() != null) ? message.getReceivedDate() : message.getSentDate();
        LocalDateTime receivedAt = (date != null)
                ? LocalDateTime.ofInstant(date.toInstant(), ZoneId.systemDefault())
                : LocalDateTime.now();

        Flags flags = message.getFlags();
        boolean seen = flags.contains(Flags.Flag.SEEN);
        boolean flagged = flags.contains(Flags.Flag.FLAGGED);
        boolean answered = flags.contains(Flags.Flag.ANSWERED);

        // MIME structure traversal triggers a lazy BODYSTRUCTURE fetch. Some IMAP
        // servers (e.g. Seznam) occasionally return malformed BODYSTRUCTURE for
        // individual messages — persist an envelope-only stub so the message still
        // appears in the list. The detail endpoint will retry the content fetch and
        // surface contentError dynamically (see MailFacade.getMessageDetail).
        List<AttachmentResponse> attachments;
        String contentError = null;
        try {
            attachments = MimePartExtractor.extractAttachmentMetadata(message, "");
        } catch (MessagingException | IOException | RuntimeException e) {
            // RuntimeException too: a malformed BODYSTRUCTURE can surface as an
            // unchecked JavaMail error (e.g. getContent() not returning a Multipart),
            // and the list must stay fail-soft — one bad message cannot break the page.
            log.warn(
                    "{} Failed to parse MIME structure for UID {} in {} ({}). "
                            + "Persisting envelope-only stub; body and attachments unavailable until next sync.",
                    LogCategory.IMAP, uid, folderName, e.getMessage());
            attachments = List.of();
            contentError = e.getMessage();
        }

        // Threading headers (In-Reply-To, References, Message-ID) are pre-fetched by
        // the batch FetchProfile, so reads normally hit the cached value. A
        // MessagingException here means the message was expunged on the server
        // between batch fetch and per-message processing and JavaMail tried a lazy
        // re-fetch that failed. We do not want to drop the whole envelope for a
        // missing threading header — record null and continue. Threading just
        // degrades (the message becomes a thread root); subject / sender / date are
        // still useful in the list.
        String inReplyTo = tryReadHeader(message, "In-Reply-To", uid, folderName);
        String references = tryReadHeader(message, "References", uid, folderName);

        String messageId = null;
        if (message instanceof MimeMessage mime) {
            try {
                messageId = mime.getMessageID();
            } catch (MessagingException e) {
                log.warn("{} Failed to read Message-ID for UID {} in {}: {}", LogCategory.IMAP, uid, folderName,
                        e.getMessage());
            }
        }

        // stableId is generated later in MessageMapper at persistence time.
        // threadId is assigned by ThreadingService at persistence time (see
        // MessageDownloader.saveMessagesBatchAtomic); the fetch layer never
        // populates it.
        String stableId = null;
        String threadId = null;

        return new MailDetailResponse(stableId, uid, safeSubject, sender, to, cc, bcc, null, receivedAt, seen, flagged,
                answered, messageId, inReplyTo, references, !attachments.isEmpty(), attachments, contentError,
                threadId);
    }

    private @Nullable String joinAddresses(@Nullable Address[] addresses) {
        if (addresses == null || addresses.length == 0)
            return null;
        return String.join(", ", Arrays.stream(addresses).map(MessageFetcher::formatAddress).toArray(String[]::new));
    }

    /**
     * Renders an {@link Address} for display, decoding RFC 2047 encoded-words.
     *
     * Jakarta Mail parses the personal part via
     * {@link InternetAddress#getPersonal()}, which transparently decodes
     * encoded-words (e.g. {@code =?utf-8?q?Tren=C3=BDrk=C3=A1rna?=} →
     * {@code Trenýrkárna}). {@link Address#toString()} returns the raw RFC 5322
     * form, which leaks the encoded-word into the UI. We deliberately build
     * {@code "Personal <email>"} without quoting the personal part so the UI stays
     * clean — round-tripping through RFC 5322 isn't a concern, this is a display
     * label.
     */
    static @Nullable String formatAddress(@Nullable Address addr) {
        if (addr == null) {
            return null;
        }
        if (addr instanceof InternetAddress ia) {
            String personal = ia.getPersonal();
            String email = ia.getAddress();
            if (personal != null && !personal.isBlank()) {
                return email != null && !email.isBlank() ? personal + " <" + email + ">" : personal;
            }
            if (email != null && !email.isBlank()) {
                return email;
            }
        }
        // Non-InternetAddress (rare for IMAP) — best-effort decode of the raw form.
        try {
            return MimeUtility.decodeText(addr.toString());
        } catch (UnsupportedEncodingException e) {
            return addr.toString();
        }
    }

    private @Nullable String tryReadHeader(Message msg, String headerName, long uid, String folderName) {
        try {
            return getHeader(msg, headerName);
        } catch (MessagingException e) {
            log.warn("{} Failed to read header {} for UID {} in {}: {}", LogCategory.IMAP, headerName, uid, folderName,
                    e.getMessage());
            return null;
        }
    }

    private @Nullable String getHeader(Message msg, String headerName) throws MessagingException {
        String[] headers = msg.getHeader(headerName);
        if (headers != null && headers.length > 0) {
            return headers[0];
        }
        return null;
    }
}
