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

        @ParameterizedTest
        @CsvSource({"Sent,SENT", "Odeslané,SENT", "Trash,TRASH", "Koš,TRASH", "Smazané,TRASH", "Drafts,DRAFTS",
                "Rozepsané,DRAFTS", "Spam,JUNK", "Junk,JUNK", "Archive,ARCHIVE", "Archiv,ARCHIVE",
                // Seznam.cz "Bulk" / "Hromadné" — non-RFC, name-based only.
                "Bulk,NEWSLETTERS", "Hromadné,NEWSLETTERS", "Newsletters,NEWSLETTERS"})
        void detectsRoleFromName(String name, FolderRole expected) {
            assertThat(FolderRole.fromNameFallback(name)).isEqualTo(expected);
        }

        @Test
        @DisplayName("Unknown name maps to USER")
        void unknownNameIsUser() {
            assertThat(FolderRole.fromNameFallback("Projects")).isEqualTo(FolderRole.USER);
            assertThat(FolderRole.fromNameFallback(null)).isEqualTo(FolderRole.USER);
        }
    }
}
