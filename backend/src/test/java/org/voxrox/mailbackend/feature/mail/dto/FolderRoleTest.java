package org.voxrox.mailbackend.feature.mail.dto;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class FolderRoleTest {

    @Nested
    @DisplayName("fromAttribute (RFC 6154 SPECIAL-USE)")
    class FromAttribute {

        @ParameterizedTest
        @CsvSource({"\\Inbox,INBOX", "\\Sent,SENT", "\\Trash,TRASH", "\\Drafts,DRAFTS", "\\Junk,JUNK",
                "\\Archive,ARCHIVE", "\\unknown,USER"})
        void mapsSpecialUseAttributes(String attribute, FolderRole expected) {
            assertThat(FolderRole.fromAttribute(attribute)).isEqualTo(expected);
        }

        @Test
        @DisplayName("Returns USER for null or empty attribute")
        void returnsUserForUnknown() {
            assertThat(FolderRole.fromAttribute(null)).isEqualTo(FolderRole.USER);
            assertThat(FolderRole.fromAttribute("")).isEqualTo(FolderRole.USER);
        }
    }

    @Nested
    @DisplayName("fromNameFallback")
    class FromNameFallback {

        @ParameterizedTest(name = "{0} -> {1}")
        @CsvSource({
                // The technical names Seznam.cz and Outlook.com return.
                "sent,SENT", "trash,TRASH", "drafts,DRAFTS", "spam,JUNK", "newsletters,NEWSLETTERS", "Sent,SENT",
                "Deleted,TRASH", "Drafts,DRAFTS", "Junk,JUNK",
                // Common English names (Thunderbird, Outlook desktop, Apple Mail, Yahoo).
                "Sent Items,SENT", "Sent Messages,SENT", "Deleted Items,TRASH", "Deleted Messages,TRASH",
                "Junk E-mail,JUNK", "Bulk,JUNK", "Archive,ARCHIVE", "Archives,ARCHIVE",
                // Czech, with the diacritics a server returns and without.
                "Odeslané,SENT", "Odeslaná pošta,SENT", "Koš,TRASH", "Kos,TRASH", "Smazané,TRASH",
                "Odstraněná pošta,TRASH", "Koncepty,DRAFTS", "Rozepsané,DRAFTS", "Nevyžádaná pošta,JUNK",
                "Archiv,ARCHIVE", "Hromadné,NEWSLETTERS"})
        void detectsRoleFromWholeName(String name, FolderRole expected) {
            assertThat(FolderRole.fromNameFallback(name)).isEqualTo(expected);
        }

        @ParameterizedTest(name = "{0} -> {1}")
        @CsvSource({"INBOX.Sent,SENT", "INBOX/Trash,TRASH", "inbox.drafts,DRAFTS", "[Gmail]/Koš,TRASH",
                "[Google Mail]/Sent Mail,SENT", "'  SENT   ITEMS ',SENT"})
        void ignoresNamespacePrefixCaseAndWhitespace(String name, FolderRole expected) {
            assertThat(FolderRole.fromNameFallback(name)).isEqualTo(expected);
        }

        /*
         * The substring match these replace gave every one of them a role: "bin" made
         * Robinson and Sabina the trash, "sent" made Presentations the Sent folder, and
         * a subfolder called Sent anywhere in the tree became the one mail was filed
         * to.
         */
        @ParameterizedTest(name = "{0}")
        @CsvSource({"Robinson", "Sabina", "Cabinet", "Presentations", "Sentimental", "Archiv 2024",
                "Newsletter from Dr Novak", "Draft contracts", "Spam reports", "Košík", "Odeslané faktury",
                "Projects/Sent", "INBOX.Projects.Trash", "[Gmail]Koš", "Projects", "INBOX", "''"})
        void leavesEveryOtherNameToTheUser(String name) {
            assertThat(FolderRole.fromNameFallback(name)).isEqualTo(FolderRole.USER);
        }

        @Test
        @DisplayName("A null name is a user folder")
        void nullNameIsUser() {
            assertThat(FolderRole.fromNameFallback(null)).isEqualTo(FolderRole.USER);
        }
    }
}
