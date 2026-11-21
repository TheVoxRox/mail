package org.voxrox.mailbackend.util;

import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;

import org.jspecify.annotations.Nullable;

/**
 * Splits a raw address header field into the addresses it actually contains.
 *
 * <p>
 * Shared by the two paths that read such fields — building an outgoing message
 * and harvesting correspondents from synced mail — because both need the same
 * rule and disagreeing about which addresses are valid would be a bug in either
 * direction. Which tokens are addresses is {@link EmailAddresses}, the rule the
 * compose form validates by.
 *
 * <p>
 * A stored sender label is not such a field, and has readers of its own
 * ({@link #labelAddress}, {@link #parseLabel}) for the same reason: the reply,
 * the content response and the correspondent harvest must agree on who sent a
 * message.
 */
public final class HeaderAddresses {

    private HeaderAddresses() {
    }

    /**
     * The address in a sender label, the {@code personal <address>} form
     * {@code MessageFetcher.formatAddress} stores without quoting the personal
     * part. The address is what the label's last {@code <…>} holds, so brackets in
     * the personal part come before it and cannot decide it: the first {@code <}
     * and first {@code >} used to, which let {@code "Alice <a@evil>" <a@real>} name
     * {@code a@evil} and threw on a {@code >} before the first {@code <} (B1-11). A
     * label without that shape is an address on its own. Not validated; the caller
     * decides what an invalid address costs.
     */
    public static String labelAddress(String label) {
        String trimmed = label.trim();
        int open = trimmed.lastIndexOf('<');
        return open >= 0 && trimmed.endsWith(">") ? trimmed.substring(open + 1, trimmed.length() - 1).trim() : trimmed;
    }

    /**
     * The sender a label names — {@link #labelAddress}, with the text before it as
     * the personal name — or {@code null} when that address is not one by
     * {@link EmailAddresses}. {@link #parseValidTokens} is the wrong reader for a
     * label: with the name unquoted, a name holding a comma or an address of its
     * own comes out as tokens of its own.
     */
    public static @Nullable InternetAddress parseLabel(String label) {
        String trimmed = label.trim();
        int open = trimmed.lastIndexOf('<');
        String personal = open > 0 && trimmed.endsWith(">") ? trimmed.substring(0, open).trim() : "";
        String address = labelAddress(trimmed);
        if (!EmailAddresses.isValid(address)) {
            return null;
        }
        try {
            InternetAddress sender = new InternetAddress();
            sender.setAddress(address);
            if (!personal.isEmpty()) {
                sender.setPersonal(personal, StandardCharsets.UTF_8.name());
            }
            return sender;
        } catch (UnsupportedEncodingException e) {
            return null;
        }
    }

    /**
     * Tokenizes the field and keeps only the tokens that are complete addresses.
     *
     * <p>
     * A header field is raw text, not a comma-separated list: a display name may
     * itself contain a comma ({@code "Novak, Jan" <j@x.cz>}), which is exactly what
     * splitting on {@code ,} gets wrong. {@link InternetAddress#parse} cannot be
     * used either — it rejects the whole field over one incomplete token, and its
     * lenient overload ({@code parse(s, false)}) rejects it just the same. Only
     * {@link InternetAddress#parseHeader} tokenizes without validating, which is
     * why a per-token check does the deciding: {@link EmailAddresses}, the rule the
     * compose form validates by, so a draft keeps exactly the recipients the form
     * shows as valid and the harvest suggests only addresses the form accepts.
     *
     * <p>
     * A field that will not even tokenize yields no addresses rather than an
     * exception. Every caller needs that: a draft save must not fail on what the
     * user has typed so far, and a sync must not fail on a malformed header.
     */
    public static InternetAddress[] parseValidTokens(String raw) {
        InternetAddress[] tokens;
        try {
            tokens = InternetAddress.parseHeader(raw, false);
        } catch (AddressException e) {
            return new InternetAddress[0];
        }
        List<InternetAddress> complete = new ArrayList<>(tokens.length);
        for (InternetAddress token : tokens) {
            // A token left out is half-typed on the compose path (expected on almost
            // every keystroke-triggered autosave), malformed on the sync path.
            if (EmailAddresses.isValid(token.getAddress())) {
                complete.add(token);
            }
        }
        return complete.toArray(new InternetAddress[0]);
    }

    /**
     * Every address of a recipient field, tokenized as {@link #parseValidTokens}
     * does, or an exception when the field holds no token or a token that is not an
     * address by {@link EmailAddresses}. The send path, where a recipient must
     * never be dropped in silence. The exception names no address, as it is logged.
     *
     * @throws AddressException
     *             when the field does not tokenize, is empty, or holds a token that
     *             is not an address
     */
    public static InternetAddress[] parseAllValid(String raw) throws AddressException {
        InternetAddress[] tokens = InternetAddress.parseHeader(raw, false);
        if (tokens.length == 0) {
            throw new AddressException("The recipient field holds no address");
        }
        for (InternetAddress token : tokens) {
            if (!EmailAddresses.isValid(token.getAddress())) {
                throw new AddressException("A recipient is not an email address");
            }
        }
        return tokens;
    }
}
