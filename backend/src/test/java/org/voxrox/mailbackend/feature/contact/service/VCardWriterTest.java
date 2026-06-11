package org.voxrox.mailbackend.feature.contact.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.voxrox.mailbackend.feature.contact.EmailLabel;
import org.voxrox.mailbackend.feature.contact.entity.ContactEmailEntity;
import org.voxrox.mailbackend.feature.contact.entity.ContactEntity;

/**
 * Unit tests for {@link VCardWriter}. They cover RFC 6350 escaping, structure
 * of N/FN/EMAIL/NOTE fields, primary email -> PREF=1, label mapping, FN
 * fallback when name/surname are missing, and CRLF line endings. Czech fixture
 * data ("Novák", "Setkání") is deliberately UTF-8 to validate multi-byte
 * character handling in vCard output.
 */
class VCardWriterTest {

    private static ContactEntity contact(String name, String surname, String note, ContactEmailEntity... emails) {
        ContactEntity c = new ContactEntity();
        c.setName(name);
        c.setSurname(surname);
        c.setNote(note);
        for (ContactEmailEntity e : emails) {
            c.getEmails().add(e);
        }
        return c;
    }

    private static ContactEmailEntity email(String addr, EmailLabel label, boolean primary) {
        ContactEmailEntity e = new ContactEmailEntity();
        e.setEmail(addr);
        e.setLabel(label);
        e.setPrimary(primary);
        return e;
    }

    @Nested
    @DisplayName("Output structure")
    class Structure {

        @Test
        @DisplayName("BEGIN/VERSION/END markers and CRLF on every line")
        void containsRequiredMarkers() {
            String out = VCardWriter.write(List.of(contact("Jan", "Novák", null, email("jan@x.cz", null, true))));

            assertThat(out).startsWith("BEGIN:VCARD\r\n").contains("VERSION:4.0\r\n").endsWith("END:VCARD\r\n");
            // every line ends with CRLF, no lone LF
            assertThat(out.replace("\r\n", "")).doesNotContain("\n");
        }

        @Test
        @DisplayName("Multiple contacts = multiple BEGIN/END blocks in sequence")
        void multipleContacts() {
            String out = VCardWriter.write(List.of(contact("A", "Alfa", null, email("a@x.cz", null, true)),
                    contact("B", "Beta", null, email("b@x.cz", null, true))));

            int beginCount = out.split("BEGIN:VCARD", -1).length - 1;
            int endCount = out.split("END:VCARD", -1).length - 1;
            assertThat(beginCount).isEqualTo(2);
            assertThat(endCount).isEqualTo(2);
        }

        @Test
        @DisplayName("Empty list -> empty string")
        void emptyList() {
            assertThat(VCardWriter.write(List.of())).isEmpty();
        }
    }

    @Nested
    @DisplayName("FN (full name) — composition rules")
    class FullName {

        @Test
        void nameAndSurnameJoined() {
            String out = VCardWriter.write(List.of(contact("Jan", "Novák", null, email("a@x.cz", null, true))));
            assertThat(out).contains("FN:Jan Novák\r\n");
        }

        @Test
        void surnameOnly() {
            String out = VCardWriter.write(List.of(contact(null, "Novák", null, email("a@x.cz", null, true))));
            assertThat(out).contains("FN:Novák\r\n");
        }

        @Test
        void nameOnly() {
            String out = VCardWriter.write(List.of(contact("Jan", null, null, email("a@x.cz", null, true))));
            assertThat(out).contains("FN:Jan\r\n");
        }

        @Test
        @DisplayName("Without name/surname -> fall back to the primary email")
        void fallbackToPrimaryEmail() {
            String out = VCardWriter.write(List.of(
                    contact(null, null, null, email("first@x.cz", null, false), email("primary@x.cz", null, true))));
            assertThat(out).contains("FN:primary@x.cz\r\n");
        }

        @Test
        @DisplayName("Without name/surname and no primary -> the first email in the list")
        void fallbackToFirstEmailWhenNoPrimary() {
            String out = VCardWriter.write(List.of(contact(null, null, null, email("only@x.cz", null, false))));
            assertThat(out).contains("FN:only@x.cz\r\n");
        }
    }

    @Nested
    @DisplayName("N (structured name)")
    class StructuredName {

        @Test
        @DisplayName("surname;name;;; (family;given;;;)")
        void includesSurnameAndGivenName() {
            String out = VCardWriter.write(List.of(contact("Jan", "Novák", null, email("a@x.cz", null, true))));
            assertThat(out).contains("N:Novák;Jan;;;\r\n");
        }

        @Test
        @DisplayName("If neither name nor surname is set, the N field is omitted")
        void omittedWhenBothNull() {
            String out = VCardWriter.write(List.of(contact(null, null, null, email("only@x.cz", null, true))));
            assertThat(out).doesNotContain("\r\nN:");
        }

        @Test
        void nullNameReplacedByEmptyComponent() {
            String out = VCardWriter.write(List.of(contact(null, "Novák", null, email("a@x.cz", null, true))));
            assertThat(out).contains("N:Novák;;;;\r\n");
        }
    }

    @Nested
    @DisplayName("EMAIL — TYPE and PREF parameters")
    class Emails {

        @Test
        @DisplayName("Primary email gets PREF=1")
        void primaryGetsPref() {
            String out = VCardWriter.write(List.of(contact("Jan", "Novák", null, email("primary@x.cz", null, true))));
            assertThat(out).contains("EMAIL;PREF=1:primary@x.cz\r\n");
        }

        @Test
        @DisplayName("Non-primary email without PREF")
        void nonPrimaryNoPref() {
            String out = VCardWriter.write(List.of(contact("Jan", "Novák", null, email("a@x.cz", null, false))));
            assertThat(out).contains("EMAIL:a@x.cz\r\n");
        }

        @Test
        @DisplayName("Label maps to a lowercase TYPE")
        void labelMapsToType() {
            String out = VCardWriter
                    .write(List.of(contact("Jan", "Novák", null, email("work@x.cz", EmailLabel.WORK, true),
                            email("home@x.cz", EmailLabel.HOME, false), email("other@x.cz", EmailLabel.OTHER, false))));

            assertThat(out).contains("EMAIL;TYPE=work;PREF=1:work@x.cz\r\n").contains("EMAIL;TYPE=home:home@x.cz\r\n")
                    .contains("EMAIL;TYPE=other:other@x.cz\r\n");
        }

        @Test
        @DisplayName("Multiple emails on one contact = multiple EMAIL lines")
        void multipleEmailsAllExported() {
            String out = VCardWriter.write(List.of(contact("Jan", "Novák", null, email("a@x.cz", null, true),
                    email("b@x.cz", null, false), email("c@x.cz", null, false))));
            int count = out.split("EMAIL", -1).length - 1;
            assertThat(count).isEqualTo(3);
        }
    }

    @Nested
    @DisplayName("NOTE — text-value escape (RFC 6350 §3.4)")
    class Notes {

        @Test
        @DisplayName("Note with regular text is emitted")
        void plainNote() {
            String out = VCardWriter
                    .write(List.of(contact("Jan", "Novák", "Setkání 2024-03-15", email("a@x.cz", null, true))));
            assertThat(out).contains("NOTE:Setkání 2024-03-15\r\n");
        }

        @Test
        @DisplayName("Null/blank note is not exported")
        void blankNoteOmitted() {
            String out = VCardWriter.write(List.of(contact("Jan", "Novák", "   ", email("a@x.cz", null, true))));
            assertThat(out).doesNotContain("NOTE:");

            String out2 = VCardWriter.write(List.of(contact("Jan", "Novák", null, email("a@x.cz", null, true))));
            assertThat(out2).doesNotContain("NOTE:");
        }

        @Test
        @DisplayName("Backslash, comma, semicolon and newline are escaped")
        void specialCharsEscaped() {
            String out = VCardWriter
                    .write(List.of(contact("Jan", "Novák", "a,b;c\\d\nnext", email("a@x.cz", null, true))));
            assertThat(out).contains("NOTE:a\\,b\\;c\\\\d\\nnext\r\n");
        }

        @Test
        @DisplayName("CR in the input is dropped (replaced by the \\n below)")
        void crStripped() {
            String out = VCardWriter.write(List.of(contact("Jan", "Novák", "a\r\nb", email("a@x.cz", null, true))));
            assertThat(out).contains("NOTE:a\\nb\r\n");
        }
    }

    @Nested
    @DisplayName("Escape in N and FN fields")
    class NameEscape {

        @Test
        @DisplayName("Semicolon in surname is escaped (otherwise structured N would break)")
        void semicolonInNameIsEscaped() {
            String out = VCardWriter.write(List.of(contact("Jan", "Novák;ml.", null, email("a@x.cz", null, true))));
            assertThat(out).contains("FN:Jan Novák\\;ml.\r\n").contains("N:Novák\\;ml.;Jan;;;\r\n");
        }
    }
}
