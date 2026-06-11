package org.voxrox.mailbackend.feature.contact.service;

import java.util.List;

import org.voxrox.mailbackend.feature.contact.EmailLabel;
import org.voxrox.mailbackend.feature.contact.entity.ContactEmailEntity;
import org.voxrox.mailbackend.feature.contact.entity.ContactEntity;

/**
 * Serializes contacts to vCard 4.0 (RFC 6350).
 * <p>
 * Implemented properties: {@code FN}, {@code N}, {@code EMAIL} (with
 * {@code TYPE} and {@code PREF} parameters), {@code NOTE}. Line endings are
 * CRLF per the spec. Escaping per §3.4 — backslash, comma, semicolon and
 * newline in free-text fields. {@code TYPE}/{@code PREF} parameters are
 * enum/integer, so escaping does not apply to them.
 * <p>
 * Line folding (max 75 octets per line) is not implemented yet — most parsers
 * tolerate the omission (Apple Contacts, Google Contacts,
 * evolution-data-server, Thunderbird) and adding it would mean complexity
 * without any real benefit for ordinary field lengths. If a specific client has
 * trouble, we will add it.
 */
public final class VCardWriter {

    private static final String CRLF = "\r\n";

    private VCardWriter() {
    }

    public static String write(List<ContactEntity> contacts) {
        StringBuilder sb = new StringBuilder(contacts.size() * 256);
        for (ContactEntity c : contacts) {
            appendCard(sb, c);
        }
        return sb.toString();
    }

    private static void appendCard(StringBuilder sb, ContactEntity contact) {
        sb.append("BEGIN:VCARD").append(CRLF);
        sb.append("VERSION:4.0").append(CRLF);

        /*
         * FN is required — if we have no name+surname, fall back to the primary email.
         * Without FN the spec would require a validation error, but we have a guarantee
         * that a contact has at least one email (DB constraint).
         */
        sb.append("FN:").append(escapeText(buildFullName(contact))).append(CRLF);

        /*
         * N (structured name): family;given;additional;prefix;suffix. Our model:
         * surname → family, name → given. The rest is empty.
         */
        if (contact.getSurname() != null || contact.getName() != null) {
            sb.append("N:").append(escapeText(nullToEmpty(contact.getSurname()))).append(';')
                    .append(escapeText(nullToEmpty(contact.getName()))).append(";;;").append(CRLF);
        }

        for (ContactEmailEntity email : contact.getEmails()) {
            appendEmail(sb, email);
        }

        if (contact.getNote() != null && !contact.getNote().isBlank()) {
            sb.append("NOTE:").append(escapeText(contact.getNote())).append(CRLF);
        }

        sb.append("END:VCARD").append(CRLF);
    }

    private static String buildFullName(ContactEntity contact) {
        String name = contact.getName();
        String surname = contact.getSurname();
        boolean hasName = name != null && !name.isBlank();
        boolean hasSurname = surname != null && !surname.isBlank();
        if (hasName && hasSurname) {
            return name + " " + surname;
        }
        if (hasSurname) {
            return surname;
        }
        if (hasName) {
            return name;
        }
        // Fallback: primary email (contacts without name/surname legitimately exist).
        return contact.getEmails().stream().filter(ContactEmailEntity::isPrimary).map(ContactEmailEntity::getEmail)
                .findFirst()
                .orElseGet(() -> contact.getEmails().stream().map(ContactEmailEntity::getEmail).findFirst().orElse(""));
    }

    private static void appendEmail(StringBuilder sb, ContactEmailEntity email) {
        sb.append("EMAIL");
        EmailLabel label = email.getLabel();
        if (label != null) {
            sb.append(";TYPE=").append(toVCardType(label));
        }
        if (email.isPrimary()) {
            sb.append(";PREF=1");
        }
        sb.append(':').append(email.getEmail()).append(CRLF);
    }

    private static String toVCardType(EmailLabel label) {
        return switch (label) {
            case WORK -> "work";
            case HOME -> "home";
            case OTHER -> "other";
        };
    }

    /**
     * RFC 6350 §3.4 — text-value escape. Comma, semicolon, backslash and newline
     * are special in plain text fields (FN, NOTE, N components). These rules do not
     * apply in parameter values (TYPE, PREF) — that is why we only call this on
     * text content.
     */
    private static String escapeText(String input) {
        if (input == null || input.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(input.length() + 8);
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            switch (c) {
                case '\\' -> out.append("\\\\");
                case ',' -> out.append("\\,");
                case ';' -> out.append("\\;");
                case '\n' -> out.append("\\n");
                case '\r' -> {
                    /* Strip standalone CR — the \n below stands in for it. */ }
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
