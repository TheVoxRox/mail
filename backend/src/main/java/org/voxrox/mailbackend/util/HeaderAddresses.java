package org.voxrox.mailbackend.util;

import java.util.ArrayList;
import java.util.List;

import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;

/**
 * Splits a raw address header field into the addresses it actually contains.
 *
 * <p>
 * Shared by the two paths that read such fields — building an outgoing message
 * and harvesting correspondents from synced mail — because both need the same
 * rule and disagreeing about which addresses are valid would be a bug in either
 * direction.
 */
public final class HeaderAddresses {

    private HeaderAddresses() {
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
     * why the per-token {@link InternetAddress#validate()} does the deciding.
     *
     * <p>
     * A field that will not even tokenize yields no addresses rather than an
     * exception. Both callers need that: a draft save must not fail on what the
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
            try {
                token.validate();
                complete.add(token);
            } catch (AddressException e) {
                // Half-typed on the compose path (expected on almost every
                // keystroke-triggered autosave), malformed on the sync path.
            }
        }
        return complete.toArray(new InternetAddress[0]);
    }
}
