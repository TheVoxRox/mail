package org.voxrox.mailbackend.util;

import org.jsoup.Jsoup;
import org.jspecify.annotations.Nullable;

/**
 * The visible text of a cached message body: what the full-text index searches
 * ({@code messages.body_text} in {@code V1__init.sql}).
 * <p>
 * The body cache holds sanitized HTML, and FTS5 tokenizes whatever it is given.
 * Fed the HTML, it indexed element and attribute names, the URLs of every link
 * and the base64 of inline images next to the words the reader sees, so a
 * search for {@code table} or {@code width} matched every opened HTML message.
 * jsoup's {@code text()} keeps text nodes only: no tags, no attribute values,
 * no style or script data. Adjacent blocks and table cells are separated by a
 * space, so their words stay apart.
 */
public final class BodyText {

    private BodyText() {
    }

    /**
     * Returns the visible text of {@code html} with whitespace collapsed, or
     * {@code null} when {@code html} is {@code null}, which keeps {@code body_text}
     * null exactly when the body is not cached.
     */
    public static @Nullable String of(@Nullable String html) {
        if (html == null) {
            return null;
        }
        return Jsoup.parseBodyFragment(html).text();
    }
}
