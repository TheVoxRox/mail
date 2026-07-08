package org.voxrox.mailbackend.util;

import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import jakarta.mail.*;
import jakarta.mail.internet.ContentType;
import jakarta.mail.internet.MimeUtility;
import jakarta.mail.internet.ParseException;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.voxrox.mailbackend.feature.mail.dto.AttachmentResponse;

public final class MimePartExtractor {
    private static final Logger log = LoggerFactory.getLogger(MimePartExtractor.class);
    private static final int MAX_DEPTH = 20;

    /*
     * Inline (cid:) image budget. Embedded images are inlined as data: URIs into
     * the stored body HTML, so these caps bound how much that adds to the SQLite
     * content column: an image over the per-image cap — or one that would push a
     * message past the per-message total — is simply not inlined, and its <img> is
     * then dropped by the sanitizer. Constants for now; promote to config if a
     * tunable is ever needed.
     */
    private static final long MAX_INLINE_IMAGE_BYTES = 2L * 1024 * 1024;
    private static final long MAX_TOTAL_INLINE_BYTES = 8L * 1024 * 1024;

    /**
     * Raster image subtypes safe to inline. SVG is excluded — it can carry script.
     */
    private static final Set<String> INLINE_IMAGE_SUBTYPES = Set.of("gif", "png", "jpeg", "webp", "bmp");

    private MimePartExtractor() {
    }

    /**
     * The selected body plus whether it came from a {@code text/html} part. The
     * flag lets the caller escape a genuine {@code text/plain} body instead of
     * parsing it as HTML (content-rendering audit finding F3).
     */
    public record ExtractedBody(String text, boolean isHtml) {
        private static final ExtractedBody EMPTY = new ExtractedBody("", false);
    }

    /**
     * Backwards-compatible thin wrapper: returns only the body text. Callers that
     * flatten to plain text (drafts, reply/forward) use this; the content path uses
     * {@link #extractBody} to learn the content type.
     */
    public static String extractText(Part part) throws MessagingException, IOException {
        return extractBody(part).text();
    }

    public static ExtractedBody extractBody(Part part) throws MessagingException, IOException {
        return extractBodyInternal(part, 0);
    }

    private static ExtractedBody extractBodyInternal(Part part, int depth) throws MessagingException, IOException {
        if (part == null || depth > MAX_DEPTH) {
            if (depth > MAX_DEPTH) {
                log.warn("{} Maximum MIME recursion depth exceeded.", LogCategory.SYNC);
            }
            return ExtractedBody.EMPTY;
        }

        if (part.isMimeType("text/plain") && part.getFileName() == null) {
            Object content = part.getContent();
            return new ExtractedBody(content != null ? content.toString() : "", false);
        }
        if (part.isMimeType("text/html") && part.getFileName() == null) {
            Object content = part.getContent();
            return new ExtractedBody(content != null ? content.toString() : "", true);
        }

        if (part.isMimeType("message/rfc822")) {
            Object content = part.getContent();
            return content instanceof Part nestedPart ? extractBodyInternal(nestedPart, depth + 1) : ExtractedBody.EMPTY;
        }

        if (part.isMimeType("multipart/*")) {
            if (!(part.getContent() instanceof Multipart multipart)) {
                // Malformed message: the Content-Type claims multipart but the body
                // is not (JavaMail hands back the raw String). Degrade to no text
                // rather than letting a ClassCastException escape the parser.
                log.warn("{} Part claims multipart but content is not; no text extracted.", LogCategory.SYNC);
                return ExtractedBody.EMPTY;
            }
            ExtractedBody textFallback = null;

            for (int i = 0; i < multipart.getCount(); i++) {
                Part bodyPart = multipart.getBodyPart(i);
                if (part.isMimeType("multipart/alternative")) {
                    if (bodyPart.isMimeType("text/plain") && bodyPart.getFileName() == null) {
                        textFallback = extractBodyInternal(bodyPart, depth + 1);
                    } else if (bodyPart.isMimeType("text/html") && bodyPart.getFileName() == null) {
                        return extractBodyInternal(bodyPart, depth + 1);
                    }
                } else {
                    ExtractedBody extracted = extractBodyInternal(bodyPart, depth + 1);
                    if (!extracted.text().isEmpty()) {
                        return extracted;
                    }
                }
            }
            return textFallback != null ? textFallback : ExtractedBody.EMPTY;
        }
        return ExtractedBody.EMPTY;
    }

    /**
     * Collects the embedded ({@code cid:}) images that {@code referencedCids}
     * actually names (see {@link HtmlSanitizer#referencedCids}) as safe,
     * size-bounded {@code data:} URIs keyed by their normalized Content-ID. The
     * sanitizer rewrites the matching {@code cid:} references against this map, so
     * inline logos and newsletter graphics render without any network fetch —
     * mirroring how mature clients always show embedded images while keeping remote
     * images blocked. Only referenced, raster ({@link #INLINE_IMAGE_SUBTYPES})
     * parts are ever read: an unreferenced inline image never consumes an IMAP
     * fetch or the byte budget. Per-image and per-message byte caps bound how much
     * this adds to the stored body.
     */
    public static Map<String, String> collectInlineImages(Part part, Set<String> referencedCids)
            throws MessagingException, IOException {
        if (referencedCids.isEmpty()) {
            return Map.of();
        }
        Map<String, String> images = new HashMap<>();
        collectInlineImagesInternal(part, 0, images, referencedCids, new long[]{MAX_TOTAL_INLINE_BYTES});
        return images;
    }

    private static void collectInlineImagesInternal(Part part, int depth, Map<String, String> images,
            Set<String> referencedCids, long[] remaining) throws MessagingException, IOException {
        if (part == null || depth > MAX_DEPTH) {
            return;
        }
        if (part.isMimeType("multipart/*")) {
            if (part.getContent() instanceof Multipart multipart) {
                for (int i = 0; i < multipart.getCount(); i++) {
                    collectInlineImagesInternal(multipart.getBodyPart(i), depth + 1, images, referencedCids, remaining);
                }
            }
            return;
        }
        if (part.isMimeType("message/rfc822")) {
            if (part.getContent() instanceof Part nested) {
                collectInlineImagesInternal(nested, depth + 1, images, referencedCids, remaining);
            }
            return;
        }
        if (part.isMimeType("image/*")) {
            inlineImageIfSafe(part, images, referencedCids, remaining);
        }
    }

    private static void inlineImageIfSafe(Part part, Map<String, String> images, Set<String> referencedCids,
            long[] remaining) throws MessagingException, IOException {
        String cid = contentIdKey(part);
        if (cid == null || !referencedCids.contains(cid) || images.containsKey(cid) || remaining[0] <= 0) {
            return;
        }
        String subtype = inlineImageSubtype(part);
        if (subtype == null) {
            return;
        }
        long perImageCap = Math.min(MAX_INLINE_IMAGE_BYTES, remaining[0]);
        byte[] bytes = readBounded(part, perImageCap);
        if (bytes.length > perImageCap) {
            log.debug("{} Skipped oversized inline image cid={}", LogCategory.SYNC, cid);
            return;
        }
        remaining[0] -= bytes.length;
        images.put(cid, "data:image/" + subtype + ";base64," + Base64.getEncoder().encodeToString(bytes));
    }

    private static @Nullable String contentIdKey(Part part) throws MessagingException {
        String[] header = part.getHeader("Content-ID");
        if (header == null || header.length == 0 || header[0] == null) {
            return null;
        }
        String id = header[0].trim();
        if (id.length() >= 2 && id.startsWith("<") && id.endsWith(">")) {
            id = id.substring(1, id.length() - 1);
        }
        return id.isBlank() ? null : id.toLowerCase(Locale.ROOT);
    }

    private static @Nullable String inlineImageSubtype(Part part) throws MessagingException {
        String contentType = part.getContentType();
        if (contentType == null) {
            return null;
        }
        String subtype;
        try {
            subtype = new ContentType(contentType).getSubType().toLowerCase(Locale.ROOT);
        } catch (ParseException e) {
            return null;
        }
        if (subtype.equals("jpg")) {
            subtype = "jpeg";
        }
        return INLINE_IMAGE_SUBTYPES.contains(subtype) ? subtype : null;
    }

    /**
     * Reads up to {@code cap + 1} decoded bytes so the caller can detect (and skip)
     * a part that exceeds the cap without buffering an unbounded image into memory.
     */
    private static byte[] readBounded(Part part, long cap) throws MessagingException, IOException {
        try (InputStream in = part.getInputStream()) {
            int limit = (int) Math.min(cap, Integer.MAX_VALUE - 1);
            return in.readNBytes(limit + 1);
        }
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
            if (!(part.getContent() instanceof Multipart multipart)) {
                log.warn("{} Part claims multipart but content is not; no attachments extracted.", LogCategory.SYNC);
                return attachments;
            }
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
            if (!(part.getContent() instanceof Multipart multipart)) {
                log.warn("{} Part claims multipart but content is not; treating as no attachments.", LogCategory.SYNC);
                return false;
            }
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
