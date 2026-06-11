package org.voxrox.mailbackend.util;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import jakarta.mail.*;
import jakarta.mail.internet.MimeUtility;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.voxrox.mailbackend.feature.mail.dto.AttachmentResponse;

public final class MimePartExtractor {
    private static final Logger log = LoggerFactory.getLogger(MimePartExtractor.class);
    private static final int MAX_DEPTH = 20;

    private MimePartExtractor() {
    }

    public static String extractText(Part part) throws MessagingException, IOException {
        return extractTextInternal(part, 0);
    }

    private static String extractTextInternal(Part part, int depth) throws MessagingException, IOException {
        if (part == null || depth > MAX_DEPTH) {
            if (depth > MAX_DEPTH) {
                log.warn("{} Maximum MIME recursion depth exceeded.", LogCategory.SYNC);
            }
            return "";
        }

        if (part.isMimeType("text/plain") && part.getFileName() == null) {
            Object content = part.getContent();
            return content != null ? content.toString() : "";
        }
        if (part.isMimeType("text/html") && part.getFileName() == null) {
            Object content = part.getContent();
            return content != null ? content.toString() : "";
        }

        if (part.isMimeType("message/rfc822")) {
            Object content = part.getContent();
            return content instanceof Part nestedPart ? extractTextInternal(nestedPart, depth + 1) : "";
        }

        if (part.isMimeType("multipart/*")) {
            Multipart multipart = (Multipart) part.getContent();
            String textFallback = null;

            for (int i = 0; i < multipart.getCount(); i++) {
                Part bodyPart = multipart.getBodyPart(i);
                if (part.isMimeType("multipart/alternative")) {
                    if (bodyPart.isMimeType("text/plain") && bodyPart.getFileName() == null) {
                        textFallback = extractTextInternal(bodyPart, depth + 1);
                    } else if (bodyPart.isMimeType("text/html") && bodyPart.getFileName() == null) {
                        return extractTextInternal(bodyPart, depth + 1);
                    }
                } else {
                    String extractedText = extractTextInternal(bodyPart, depth + 1);
                    if (extractedText != null && !extractedText.isEmpty()) {
                        return extractedText;
                    }
                }
            }
            return textFallback != null ? textFallback : "";
        }
        return "";
    }

    public static List<AttachmentResponse> extractAttachmentMetadata(Part part, String currentPath)
            throws MessagingException, IOException {
        return extractAttachmentMetadataInternal(part, currentPath, 0);
    }

    private static List<AttachmentResponse> extractAttachmentMetadataInternal(Part part, String currentPath, int depth)
            throws MessagingException, IOException {
        List<AttachmentResponse> attachments = new ArrayList<>();
        if (part == null || depth > MAX_DEPTH) {
            return attachments;
        }

        if (part.isMimeType("multipart/*")) {
            Multipart multipart = (Multipart) part.getContent();
            for (int i = 0; i < multipart.getCount(); i++) {
                BodyPart bodyPart = multipart.getBodyPart(i);
                String nextPath = currentPath.isEmpty() ? String.valueOf(i + 1) : currentPath + "." + (i + 1);

                if (isAttachment(bodyPart)) {
                    attachments.add(mapToAttachmentResponse(bodyPart, nextPath));
                }

                if (bodyPart.isMimeType("multipart/*") || bodyPart.isMimeType("message/rfc822")) {
                    attachments.addAll(extractAttachmentMetadataInternal(bodyPart, nextPath, depth + 1));
                }
            }
        } else if (currentPath.isEmpty() && isAttachment(part)) {
            attachments.add(mapToAttachmentResponse(part, "1"));
        }
        return attachments;
    }

    private static boolean isAttachment(Part part) throws MessagingException {
        String fileName = part.getFileName();
        String disposition = part.getDisposition();

        if (disposition != null && disposition.equalsIgnoreCase(Part.ATTACHMENT)) {
            return true;
        }
        if (disposition != null && disposition.equalsIgnoreCase(Part.INLINE)) {
            return fileName != null;
        }
        return fileName != null && !part.isMimeType("text/html") && !part.isMimeType("text/plain");
    }

    private static AttachmentResponse mapToAttachmentResponse(Part part, String path) throws MessagingException {
        String fileName = "unnamed";
        try {
            String rawName = part.getFileName();
            if (rawName != null) {
                fileName = MimeUtility.decodeText(rawName);
            }
        } catch (UnsupportedEncodingException e) {
            log.warn("{} File name decoding error at path {}: {}", LogCategory.ATTACHMENT, path, e.getMessage());
        }

        String contentType = "application/octet-stream";
        if (part.getContentType() != null) {
            // Only the media type before the first ';' matters; limit 2 skips
            // splitting the parameter list.
            contentType = part.getContentType().split(";", 2)[0].toLowerCase(Locale.ROOT).trim();
        }

        long size = part.getSize();
        if (size < 0) {
            size = 0;
        }

        return new AttachmentResponse(path, fileName, contentType, size);
    }

    public static boolean hasAttachments(Part part) throws MessagingException, IOException {
        return hasAttachmentsInternal(part, 0);
    }

    private static boolean hasAttachmentsInternal(Part part, int depth) throws MessagingException, IOException {
        if (part == null || depth > MAX_DEPTH) {
            return false;
        }
        if (part.isMimeType("multipart/*")) {
            Multipart multipart = (Multipart) part.getContent();
            for (int i = 0; i < multipart.getCount(); i++) {
                BodyPart bodyPart = multipart.getBodyPart(i);
                if (isAttachment(bodyPart)) {
                    return true;
                }
                if (bodyPart.isMimeType("multipart/*") && hasAttachmentsInternal(bodyPart, depth + 1)) {
                    return true;
                }
            }
            return false;
        }
        return isAttachment(part);
    }
}
