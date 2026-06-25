package org.voxrox.mailbackend.feature.mail.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("MessageStableId")
class MessageStableIdTest {

    @Test
    @DisplayName("is deterministic and a 32-char lowercase hex string")
    void deterministicHex() {
        String a = MessageStableId.compute(1L, "INBOX", "<m1@example.com>", 10L, 100L);
        String b = MessageStableId.compute(1L, "INBOX", "<m1@example.com>", 10L, 100L);

        assertThat(a).hasSize(32).matches("[0-9a-f]{32}").isEqualTo(b);
    }

    @Test
    @DisplayName("ignores uid/uidValidity when a Message-ID is present — survives a re-download")
    void stableAcrossUidValidityChange() {
        String before = MessageStableId.compute(1L, "INBOX", "<m1@example.com>", 10L, 100L);
        // A UIDVALIDITY change re-downloads the folder with a new uid + uidValidity;
        // the identity-derived id must stay the same so the client's link survives.
        String after = MessageStableId.compute(1L, "INBOX", "<m1@example.com>", 999L, 555L);

        assertThat(after).isEqualTo(before);
    }

    @Test
    @DisplayName("differs by account, folder and Message-ID")
    void distinctByScope() {
        String base = MessageStableId.compute(1L, "INBOX", "<m1@example.com>", 10L, 100L);

        assertThat(MessageStableId.compute(2L, "INBOX", "<m1@example.com>", 10L, 100L)).isNotEqualTo(base);
        assertThat(MessageStableId.compute(1L, "[Gmail]/Spam", "<m1@example.com>", 10L, 100L)).isNotEqualTo(base);
        assertThat(MessageStableId.compute(1L, "INBOX", "<m2@example.com>", 10L, 100L)).isNotEqualTo(base);
    }

    @Test
    @DisplayName("trims the Message-ID so incidental whitespace does not change the id")
    void trimsMessageId() {
        String tight = MessageStableId.compute(1L, "INBOX", "<m1@example.com>", 10L, 100L);
        String padded = MessageStableId.compute(1L, "INBOX", "  <m1@example.com>\n", 10L, 100L);

        assertThat(padded).isEqualTo(tight);
    }

    @Test
    @DisplayName("falls back to uid + uidValidity when the Message-ID is missing or blank")
    void fallbackWithoutMessageId() {
        String fromNull = MessageStableId.compute(1L, "INBOX", null, 10L, 100L);
        String fromBlank = MessageStableId.compute(1L, "INBOX", "   ", 10L, 100L);

        // null and blank both take the uid fallback → same id for the same uid.
        assertThat(fromNull).hasSize(32).matches("[0-9a-f]{32}").isEqualTo(fromBlank);
        // A different uid in the fallback path → a different id.
        assertThat(MessageStableId.compute(1L, "INBOX", null, 11L, 100L)).isNotEqualTo(fromNull);
    }

    @Test
    @DisplayName("the Message-ID derivation and the uid fallback never collide")
    void midAndUidDomainsDoNotCollide() {
        String withMid = MessageStableId.compute(1L, "INBOX", "<m1@example.com>", 10L, 100L);
        String withoutMid = MessageStableId.compute(1L, "INBOX", null, 10L, 100L);

        assertThat(withMid).isNotEqualTo(withoutMid);
    }
}
