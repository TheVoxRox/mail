package org.voxrox.mailbackend.util;

import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
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

    /*
     * Body budget (IMAP/SMTP audit finding B1-1). The selected text part is read
     * through the same bounded stream as inline images, so a hostile server
     * declaring a multi-hundred-MB body cannot exhaust the heap: a part over the
     * cap yields ExtractedBody.OVERSIZE and the caller serves a placeholder instead
     * of the content.
     */
    private static final long MAX_BODY_BYTES = 8L * 1024 * 1024;

    /**
     * Raster image subtypes safe to inline. SVG is excluded — it can carry script.
     */
    private static final Set<String> INLINE_IMAGE_SUBTYPES = Set.of("gif", "png", "jpeg", "webp", "bmp");

    private MimePartExtractor() {
    }

    /**
     * The selected body plus whether it came from a {@code text/html} part. The
     * flag lets the caller escape a genuine {@code text/plain} body instead of
     * parsing it as HTML (content-rendering audit finding F3). {@code oversize}
     * marks a body part whose transfer-decoded size exceeded
     * {@link #MAX_BODY_BYTES} (audit B1-1) — {@code text} is then intentionally
     * empty and the caller substitutes a placeholder.
     */
    public record ExtractedBody(String text, boolean isHtml, boolean oversize) {
        private static final ExtractedBody EMPTY = new ExtractedBody("", false, false);
        private static final ExtractedBody OVERSIZE = new ExtractedBody("", false, true);
    }

    /**
     * Backwards-compatible thin wrapper: returns only the body text. Callers that
     * flatten to plain text (drafts, reply/forward) use this; the content path uses
     * {@link #extractBody} to learn the content type. An oversized body (B1-1)
     * flattens to an empty string here — use {@link #extractBody} when the caller
     * must distinguish oversize from genuinely empty.
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
            return readBodyText(part, false);
        }
        if (part.isMimeType("text/html") && part.getFileName() == null) {
            return readBodyText(part, true);
        }

        if (part.isMimeType("message/rfc822")) {
            Object content = part.getContent();
            return content instanceof Part nestedPart
                    ? extractBodyInternal(nestedPart, depth + 1)
                    : ExtractedBody.EMPTY;
        }

        if (part.isMimeType("multipart/*")) {
            if (!(part.getContent() instanceof Multipart multipart)) {
                // Malformed message: the Content-Type claims multipart but the body
                // is not (JavaMail hands back the raw String). Degrade to no text
                // rather than letting a ClassCastException escape the parser.
                log.warn("{} Part claims multipart but content is not; no text extracted.", LogCategory.SYNC);
                return ExtractedBody.EMPTY;
            }
            if (part.isMimeType("multipart/alternative")) {
                return selectAlternative(multipart, depth);
            }
            for (int i = 0; i < multipart.getCount(); i++) {
                ExtractedBody extracted = extractBodyInternal(multipart.getBodyPart(i), depth + 1);
                if (!extracted.text().isEmpty() || extracted.oversize()) {
                    return extracted;
                }
            }
            return ExtractedBody.EMPTY;
        }
        return ExtractedBody.EMPTY;
    }

    /**
     * Picks the body from a {@code multipart/alternative}: the richest renderable
     * part wins. A rich alternative is {@code text/html} or a nested multipart
     * (typically {@code multipart/related} = HTML plus inline images, the Apple
     * Mail layout); {@code text/plain} is kept as the fallback. Deliberately
     * order-agnostic: an oversized (B1-1) rich part falls back to a plain-text
     * sibling that fits whether or not the sender emitted plain-first (RFC 2046
     * recommends it, hostile or sloppy senders do not comply), and the oversize
     * marker survives only when no sibling is displayable.
     */
    private static ExtractedBody selectAlternative(Multipart multipart, int depth)
            throws MessagingException, IOException {
        ExtractedBody textFallback = null;
        ExtractedBody oversizeRich = null;
        for (int i = 0; i < multipart.getCount(); i++) {
            Part bodyPart = multipart.getBodyPart(i);
            if (bodyPart.isMimeType("text/plain") && bodyPart.getFileName() == null) {
                textFallback = extractBodyInternal(bodyPart, depth + 1);
            } else if ((bodyPart.isMimeType("text/html") && bodyPart.getFileName() == null)
                    || bodyPart.isMimeType("multipart/*")) {
                ExtractedBody rich = extractBodyInternal(bodyPart, depth + 1);
                if (rich.oversize()) {
                    oversizeRich = rich;
                } else if (!rich.text().isEmpty()) {
                    return rich;
                }
            }
        }
        if (textFallback != null && !textFallback.oversize() && !textFallback.text().isEmpty()) {
            return textFallback;
        }
        if (oversizeRich != null) {
            return oversizeRich;
        }
        return textFallback != null ? textFallback : ExtractedBody.EMPTY;
    }

    /**
     * Reads a text body part with the transfer-decoded bytes capped at
     * {@link #MAX_BODY_BYTES} (audit B1-1) — {@code getContent()} would buffer the
     * entire, attacker-sized part on the heap. Charset decoding happens here for
     * the same reason; unlike {@code getContent()}, an unknown charset degrades to
     * a UTF-8 decode with replacement characters instead of failing the message.
     */
    private static ExtractedBody readBodyText(Part part, boolean isHtml) throws MessagingException, IOException {
        byte[] bytes = readBounded(part, MAX_BODY_BYTES);
        if (bytes.length > MAX_BODY_BYTES) {
            log.warn("{} Text body part exceeds the {}-byte cap; returning the oversize marker.", LogCategory.SYNC,
                    MAX_BODY_BYTES);
            return ExtractedBody.OVERSIZE;
        }
        return new ExtractedBody(new String(bytes, charsetOf(part)), isHtml, false);
    }

    /**
     * Charset declared by the part's Content-Type. Falls back to UTF-8 — an ASCII
     * superset covering the RFC 2046 {@code us-ascii} default — when the parameter
     * is missing, unmappable or malformed; {@code new String(bytes, charset)} then
     * substitutes replacement characters rather than dropping the body.
     */
    private static Charset charsetOf(Part part) {
        try {
            String contentType = part.getContentType();
            if (contentType == null) {
                return StandardCharsets.UTF_8;
            }
            String name = new ContentType(contentType).getParameter("charset");
            return name == null ? StandardCharsets.UTF_8 : Charset.forName(MimeUtility.javaCharset(name));
        } catch (MessagingException | IllegalArgumentException e) {
            return StandardCharsets.UTF_8;
        }
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
     * a part that exceeds the cap without buffering an unbounded part into memory.
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
