package org.voxrox.mailbackend.util;

import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

/**
 * What counts as an email address the user can type: one rule, which the
 * frontend's {@code isValidEmailAddress} in
 * {@code frontend/src/lib/compose/addresses.ts} follows too, held together by
 * the vectors in
 * {@code backend/src/test/resources/text/email-address-vectors.json} that both
 * test suites run.
 *
 * <p>
 * The rule is a bare address in its plainest form, {@code local@domain}:
 * <ul>
 * <li>the local part is dot-separated runs of letters of any script (combining
 * marks included), decimal digits and the RFC 5322 {@code atext} symbols, no
 * longer than 64 characters;</li>
 * <li>the domain is at least two dot-separated labels of letters and decimal
 * digits, a hyphen allowed inside a label, each label no longer than 63
 * characters.</li>
 * </ul>
 *
 * <p>
 * Every address the rule accepts is one the send path accepts too, which
 * {@code EmailAddressesTest} checks over the vectors: the domain characters are
 * exactly those Jakarta Mail takes ({@link Character#isLetterOrDigit}), so a
 * combining mark, an underscore or a no-break space in the domain is refused
 * here rather than after the SMTP connection is open. The rule is stricter than
 * Jakarta Mail and stricter than Hibernate's {@code @Email}, each of which
 * accepts addresses the other refuses: it refuses quoted local parts, comments,
 * IP literals and a domain without a dot, none of which the compose form could
 * ever take, and the double dot, the leading or trailing dot and the hyphen at
 * the edge of a label that are almost always a typing mistake.
 *
 * <p>
 * Judges the string exactly as given. The request records strip the value
 * before Bean Validation reads it ({@link StoredText#strip}), and a header
 * token comes out of the tokenizer already trimmed.
 */
public final class EmailAddresses {

    private static final String LOCAL_CHAR = "[\\p{L}\\p{M}\\p{Nd}!#$%&'*+/=?^_`{|}~-]";
    private static final String DOMAIN_CHAR = "[\\p{L}\\p{Nd}]";
    private static final String LABEL = DOMAIN_CHAR + "(?:[\\p{L}\\p{Nd}-]{0,61}" + DOMAIN_CHAR + ")?";

    /**
     * Matched against the whole input. The lookahead bounds the local part before
     * anything else runs, which also keeps a long input without an {@code @} from
     * costing more than 65 characters of scanning.
     */
    private static final Pattern ADDRESS = Pattern
            .compile("(?=[^@]{1,64}@)" + LOCAL_CHAR + "+(?:\\." + LOCAL_CHAR + "+)*@(?:" + LABEL + "\\.)+" + LABEL);

    private EmailAddresses() {
    }

    /** Whether {@code address} is an email address by the rule above. */
    public static boolean isValid(@Nullable String address) {
        return address != null && ADDRESS.matcher(address).matches();
    }
}
