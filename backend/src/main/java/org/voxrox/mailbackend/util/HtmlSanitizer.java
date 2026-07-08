package org.voxrox.mailbackend.util;

import java.net.URI;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.safety.Safelist;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HtmlSanitizer {

    private static final Pattern SAFE_DATA_IMAGE = Pattern
            .compile("^data:image/(?:gif|png|jpe?g|webp|bmp);base64,[a-z0-9+/=\\s]+$", Pattern.CASE_INSENSITIVE);

    /**
     * Matches {@code cid:} references in the raw body, capturing the Content-ID.
     */
    private static final Pattern CID_REFERENCE = Pattern.compile("cid:([^\\s\"'<>()]+)", Pattern.CASE_INSENSITIVE);

    private static final Logger log = LoggerFactory.getLogger(HtmlSanitizer.class);

    /*
     * The backend is the canonical policy: it does not let user inline CSS or
     * remote images through. The frontend may be stricter, but must not be the only
     * safeguard.
     *
     * Static because sanitize runs per content fetch: the policy never changes
     * after init and Jsoup's Cleaner only reads the Safelist, so sharing one
     * instance across threads is safe. OutputSettings stays per call — Jsoup
     * attaches it to the output Document.
     *
     * cid: is kept through clean() so a matching embedded image can be rewritten to
     * its inlined data: URI below (an unmatched cid: is then dropped). data:image
     * is a local inline resource. http/https images are a tracking / privacy risk;
     * any future "show remote content" action belongs in an explicit user gesture,
     * not the default.
     */
    private static final Safelist SAFELIST = Safelist.relaxed().removeAttributes(":all", "style")
            .addProtocols("a", "href", "http", "https", "mailto", "tel").removeProtocols("a", "href", "ftp")
            .addProtocols("img", "src", "cid", "data").removeProtocols("img", "src", "http", "https")
            .addAttributes("table", "align", "width", "bgcolor", "cellpadding", "cellspacing")
            .addAttributes("td", "align", "valign", "width", "height");

    /**
     * Sanitizes with no embedded images — any {@code cid:} reference is dropped.
     */
    public static String sanitize(String rawHtml) {
        return sanitize(rawHtml, Map.of());
    }

    /**
     * @param inlineImages
     *            normalized Content-ID → {@code data:} URI for embedded images, as
     *            collected by {@link MimePartExtractor#collectInlineImages}. A
     *            {@code cid:} reference is rewritten to its data URI when present
     *            here and dropped otherwise, so no unresolved {@code cid:} ever
     *            reaches the client.
     */
    public static String sanitize(String rawHtml, Map<String, String> inlineImages) {
        if (rawHtml == null || rawHtml.isBlank()) {
            return "";
        }

        try {
            Document.OutputSettings settings = new Document.OutputSettings().prettyPrint(false)
                    .syntax(Document.OutputSettings.Syntax.html);

            String cleaned = Jsoup.clean(rawHtml, "", SAFELIST, settings);

            Document doc = Jsoup.parseBodyFragment(cleaned);

            // External links leave the app without a referrer and without window.opener.
            for (Element link : doc.select("a[href]")) {
                if (isSafeLink(link.attr("href"))) {
                    link.attr("target", "_blank");
                    link.attr("rel", "nofollow noopener noreferrer");
                } else {
                    link.removeAttr("href");
                }
            }

            /*
             * Untrusted inline styles are never allowed in the output. The Jsoup safelist
             * already strips them in clean(); this pass is a defensive fallback in case the
             * safelist is later relaxed.
             */
            for (Element styled : doc.select("[style]")) {
                styled.removeAttr("style");
            }

            /*
             * Embedded cid: images are rewritten to their inlined data: URI (or dropped
             * when we have no matching part); inline data:image is kept; remote http/https
             * sources are dropped to defeat tracking pixels and avoid leaking IP/UA.
             */
            for (Element image : doc.select("img")) {
                String src = image.attr("src").trim();
                if (src.regionMatches(true, 0, "cid:", 0, 4)) {
                    String dataUri = inlineImages.get(normalizeCid(src.substring(4)));
                    if (dataUri != null) {
                        image.attr("src", dataUri);
                    } else {
                        image.remove();
                    }
                } else if (!isSafeImageSrc(src)) {
                    image.remove();
                }
            }

            String result = "<div class='mail-content-wrapper' style='all: revert;'>" + doc.body().html() + "</div>";

            log.trace("{} HTML sanitized. Processed {} characters.", LogCategory.SECURITY, rawHtml.length());
            return result;

        } catch (RuntimeException e) {
            /*
             * Defensive security barrier: any parser/sanitizer error must not let raw HTML
             * through. Jsoup does not declare checked exceptions, so RuntimeException
             * covers the realistic failure surface.
             */
            log.error("{} Critical HTML sanitization failure! {}", LogCategory.SECURITY, e.getMessage());
            return "<div style='color: red; padding: 10px; border: 1px solid red; font-family: sans-serif;'>"
                    + "Email content was blocked for security reasons.</div>";
        }
    }

    private static boolean isSafeLink(String rawHref) {
        if (rawHref == null || rawHref.isBlank()) {
            return false;
        }
        try {
            URI uri = URI.create(rawHref.trim());
            if (!uri.isAbsolute() || uri.getScheme() == null) {
                return false;
            }
            String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
            return scheme.equals("http") || scheme.equals("https") || scheme.equals("mailto") || scheme.equals("tel");
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * cid: is resolved separately (see the img loop); this gates only inline
     * data:image.
     */
    private static boolean isSafeImageSrc(String rawSrc) {
        if (rawSrc == null || rawSrc.isBlank()) {
            return false;
        }
        return SAFE_DATA_IMAGE.matcher(rawSrc.trim()).matches();
    }

    /**
     * Normalizes a {@code cid:} reference body to match a collected Content-ID key.
     */
    private static String normalizeCid(String raw) {
        String cid = raw.trim();
        if (cid.length() >= 2 && cid.startsWith("<") && cid.endsWith(">")) {
            cid = cid.substring(1, cid.length() - 1);
        }
        return cid.toLowerCase(Locale.ROOT);
    }

    /**
     * Extracts the normalized Content-IDs that the raw body actually references via
     * {@code cid:}. Callers pass this to
     * {@link MimePartExtractor#collectInlineImages} so only referenced parts are
     * read and inlined — unreferenced inline images never consume the byte budget
     * or an IMAP fetch.
     */
    public static Set<String> referencedCids(String rawHtml) {
        if (rawHtml == null || rawHtml.isBlank()) {
            return Set.of();
        }
        Set<String> cids = new HashSet<>();
        Matcher matcher = CID_REFERENCE.matcher(rawHtml);
        while (matcher.find()) {
            cids.add(normalizeCid(matcher.group(1)));
        }
        return cids;
    }
}
