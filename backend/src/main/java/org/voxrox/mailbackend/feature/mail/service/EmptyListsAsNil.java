package org.voxrox.mailbackend.feature.mail.service;

import java.util.Arrays;

import org.eclipse.angus.mail.imap.protocol.IMAPResponse;
import org.jspecify.annotations.Nullable;

/**
 * A FETCH response read again with each empty list in its structure written as
 * {@code NIL}, for a server that writes an absent parameter list as {@code ()}.
 * <p>
 * RFC 3501 gives a parameter list ({@code body-fld-param}) one pair or more, or
 * {@code NIL}. Seznam's server writes a disposition that has no parameters as
 * {@code ("inline" ())}, and Angus 2.0.5 rejects the whole response for it
 * ("null name in parameter list"), which {@code Protocol.command} then skips.
 * The message is left without its structure, so every read of its body or its
 * attachments fails with "Unable to load BODYSTRUCTURE": the sync stores it as
 * an envelope-only stub, and opening it reports an unexpected error. Seen
 * 2026-10-31 on a message whose structure was otherwise sound: a text/html part
 * and a 444 kB attachment.
 * <p>
 * Only a response Angus has already rejected comes here
 * ({@link BoundedImapProtocol#parseFetch}), and what comes back is parsed by
 * the same bounded parse as any other response, so no bound rests on this class
 * reading a response the way Angus does. Its reading decides only which
 * {@code ()} it rewrites: one inside the value of a {@code BODYSTRUCTURE} or
 * {@code BODY} item, below that value's own outer list, that follows a string
 * or an atom — where a parameter list stands, after a subtype or after a
 * disposition's type. An empty list that follows a bracket is left as it is: at
 * the start of a part it stands where a body must, which NIL cannot, and after
 * a disposition Angus reads it as an empty language list. A string, a literal
 * and a section in brackets are read past, so a {@code ()} inside a file name
 * stays in the name; {@code FLAGS ()}, an empty list IMAP does allow, is
 * outside every structure and stays too.
 */
final class EmptyListsAsNil extends IMAPResponse {

    /**
     * The longest response read again. An honest structure is a few kilobytes, one
     * of a hundred parts some tens; past this the response is skipped as Angus
     * skips it, and the copy this class makes stays small beside the
     * {@link BoundedImapProtocol#MAX_RESPONSE_BYTES} the response itself may take.
     */
    static final int MAX_REREAD_BYTES = 1024 * 1024;

    private static final byte[] NIL = {'N', 'I', 'L'};

    private enum Token {
        NONE, OPEN, CLOSE, STRING, ATOM
    }

    private EmptyListsAsNil(IMAPResponse original) {
        super(original);
    }

    /**
     * {@code response} with its empty lists written as NIL, ready for Angus to
     * parse from where it parsed the original; null when there is none to write, or
     * the response is longer than {@link #MAX_REREAD_BYTES}.
     */
    static @Nullable IMAPResponse of(IMAPResponse response) {
        // A copy of the response shares its bytes, and lets this class read them.
        EmptyListsAsNil copy = new EmptyListsAsNil(response);
        byte @Nullable [] rewritten = copy.rewritten();
        if (rewritten == null) {
            return null;
        }
        copy.buffer = rewritten;
        copy.size = rewritten.length;
        return copy;
    }

    /**
     * The bytes with each empty list written as NIL, or null when there is none.
     * Only bytes past {@code index} change, which is where Angus starts the FETCH
     * parse, so the tag and the number it read stay where they were.
     */
    private byte @Nullable [] rewritten() {
        if (size > MAX_REREAD_BYTES) {
            return null;
        }
        byte[] in = buffer;
        // "()" becomes "NIL", one byte longer, so this is room enough for any count.
        byte[] out = new byte[size + size / 2 + 1];
        int written = 0;
        int copiedTo = 0;
        int depth = 0;
        int structureDepth = -1;
        String item = "";
        Token previous = Token.NONE;
        int i = index;
        while (i < size) {
            byte b = in[i];
            if (b == ' ' || b == '\r' || b == '\n') {
                i++;
            } else if (b == '"') {
                i = afterQuoted(in, i, size);
                previous = Token.STRING;
            } else if (b == '{' || (b == '~' && i + 1 < size && in[i + 1] == '{')) {
                int after = afterLiteral(in, b == '~' ? i + 1 : i, size);
                if (after < 0) {
                    i = afterAtom(in, i, size);
                    previous = Token.ATOM;
                } else {
                    i = after;
                    previous = Token.STRING;
                }
            } else if (b == '(') {
                int close = nextNonSpace(in, i + 1, size);
                boolean empty = close < size && in[close] == ')';
                if (empty && structureDepth > 0 && depth >= structureDepth
                        && (previous == Token.STRING || previous == Token.ATOM)) {
                    System.arraycopy(in, copiedTo, out, written, i - copiedTo);
                    written += i - copiedTo;
                    System.arraycopy(NIL, 0, out, written, NIL.length);
                    written += NIL.length;
                    copiedTo = close + 1;
                    i = close + 1;
                    previous = Token.ATOM;
                } else {
                    depth++;
                    if (depth == 2 && (item.equals("BODYSTRUCTURE") || item.equals("BODY"))) {
                        structureDepth = 2;
                    }
                    previous = Token.OPEN;
                    i++;
                }
            } else if (b == ')') {
                if (depth == structureDepth) {
                    structureDepth = -1;
                }
                depth--;
                previous = Token.CLOSE;
                i++;
            } else {
                int after = afterAtom(in, i, size);
                if (depth == 1) {
                    item = asciiUpperCase(in, i, after);
                }
                i = after;
                previous = Token.ATOM;
            }
        }
        if (copiedTo == 0) {
            return null;
        }
        System.arraycopy(in, copiedTo, out, written, size - copiedTo);
        written += size - copiedTo;
        return Arrays.copyOf(out, written);
    }

    /** Past a quoted string, a backslash escaping the byte after it. */
    private static int afterQuoted(byte[] in, int start, int end) {
        int i = start + 1;
        while (i < end) {
            if (in[i] == '\\') {
                i += 2;
            } else if (in[i] == '"') {
                return i + 1;
            } else {
                i++;
            }
        }
        return end;
    }

    /**
     * Past a literal, {@code {n}} with its CRLF and n bytes, or -1 when what starts
     * at {@code open} is not one.
     */
    private static int afterLiteral(byte[] in, int open, int end) {
        int i = open + 1;
        long length = 0;
        while (i < end && in[i] >= '0' && in[i] <= '9' && length <= end) {
            length = length * 10 + (in[i] - '0');
            i++;
        }
        if (i == open + 1 || i >= end || in[i] != '}') {
            return -1;
        }
        i++;
        if (i + 1 < end && in[i] == '\r' && in[i + 1] == '\n') {
            i += 2;
        }
        return (int) Math.min(end, i + length);
    }

    /**
     * Past an atom, a section in brackets included: {@code BODY[HEADER.FIELDS
     * (SUBJECT)]<0>} is one item name, parentheses and all.
     */
    private static int afterAtom(byte[] in, int start, int end) {
        int i = start;
        while (i < end) {
            byte b = in[i];
            if (b == '[') {
                while (i < end && in[i] != ']') {
                    i++;
                }
                if (i < end) {
                    i++;
                }
            } else if (b == ' ' || b == '(' || b == ')' || b == '"' || b == '\r' || b == '\n'
                    || (b == '{' && i > start)) {
                break;
            } else {
                i++;
            }
        }
        return Math.max(i, start + 1);
    }

    private static int nextNonSpace(byte[] in, int start, int end) {
        int i = start;
        while (i < end && in[i] == ' ') {
            i++;
        }
        return i;
    }

    private static String asciiUpperCase(byte[] in, int start, int end) {
        char[] chars = new char[end - start];
        for (int i = start; i < end; i++) {
            byte b = in[i];
            chars[i - start] = (char) (b >= 'a' && b <= 'z' ? b - ('a' - 'A') : b & 0xff);
        }
        return new String(chars);
    }
}
