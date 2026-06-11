package org.voxrox.mailbackend.util;

import java.net.URI;
import java.util.Locale;
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

    private static final Logger log = LoggerFactory.getLogger(HtmlSanitizer.class);

    public static String sanitize(String rawHtml) {
        if (rawHtml == null || rawHtml.isBlank()) {
            return "";
        }

        try {
            /*
             * The backend is the canonical policy: it does not let user inline CSS or
             * remote images through. The frontend may be stricter, but must not be the only
             * safeguard.
             */
            Safelist safelist = Safelist.relaxed().removeAttributes(":all", "style")
                    .addProtocols("a", "href", "http", "https", "mailto", "tel").removeProtocols("a", "href", "ftp")
                    /*
                     * CID and data:image are local mail resources. http/https images are a tracking
                     * / privacy risk; any future "show remote content" action belongs in an
                     * explicit user gesture, not the default.
                     */
                    .addProtocols("img", "src", "cid", "data").removeProtocols("img", "src", "http", "https")
                    .addAttributes("table", "align", "width", "bgcolor", "cellpadding", "cellspacing")
                    .addAttributes("td", "align", "valign", "width", "height");

            Document.OutputSettings settings = new Document.OutputSettings().prettyPrint(false)
                    .syntax(Document.OutputSettings.Syntax.html);

            String cleaned = Jsoup.clean(rawHtml, "", safelist, settings);

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
             * Only local images (cid:) or inline data:image are kept. Remote http/https
             * sources are dropped to defeat tracking pixels and avoid leaking IP/UA.
             */
            for (Element image : doc.select("img")) {
                if (!isSafeImageSrc(image.attr("src"))) {
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

    private static boolean isSafeImageSrc(String rawSrc) {
        if (rawSrc == null || rawSrc.isBlank()) {
            return false;
        }
        String src = rawSrc.trim();
        return src.regionMatches(true, 0, "cid:", 0, 4) || SAFE_DATA_IMAGE.matcher(src).matches();
    }
}
