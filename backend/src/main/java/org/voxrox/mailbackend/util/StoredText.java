package org.voxrox.mailbackend.util;

import org.jspecify.annotations.Nullable;

/**
 * The form free text typed by the user is stored in: without the whitespace
 * around it, and {@code null} when nothing else is left.
 *
 * <p>
 * Surrounding whitespace carries no meaning in a name or a note, and stored it
 * leaks everywhere the value is shown: a doubled space between name and surname
 * in the contact list, a vCard export that carries it, and a name of spaces
 * alone that reads as present, so the list showed no placeholder and a screen
 * reader announced the row with no name. Null rather than an empty string, so a
 * blank value means the same as a missing one to every query and every check.
 *
 * <p>
 * Applied where the value enters the entity ({@code ContactEntity}'s setters),
 * not by each caller, so no write path can skip it; and by
 * {@code V3__Strip_contact_names}, which brought the rows stored before it to
 * the same form. Changing the rule means a migration of its own, for the same
 * reason.
 *
 * <p>
 * {@link String#strip()} rather than {@link String#trim()}: it removes Unicode
 * whitespace (an em space pasted from a document), not only the ASCII control
 * range.
 */
public final class StoredText {

    private StoredText() {
    }

    public static @Nullable String stripToNull(@Nullable String value) {
        if (value == null) {
            return null;
        }
        String stripped = value.strip();
        return stripped.isEmpty() ? null : stripped;
    }
}
