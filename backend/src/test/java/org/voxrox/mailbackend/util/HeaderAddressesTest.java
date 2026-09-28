package org.voxrox.mailbackend.util;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.mail.internet.InternetAddress;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The sender-label readers. The label is what
 * {@code MessageFetcher.formatAddress} stores: {@code personal <address>}, the
 * personal part unquoted.
 */
class HeaderAddressesTest {

    @Nested
    @DisplayName("labelAddress")
    class LabelAddress {

        @Test
        @DisplayName("The address is the label's last bracket pair, whatever the name holds (B1-11)")
        void lastBracketPairIsTheAddress() {
            assertThat(HeaderAddresses.labelAddress("Shop > News <news@shop.example>")).isEqualTo("news@shop.example");
            assertThat(HeaderAddresses.labelAddress("Alice <alice@evil.example> <alice@example.com>"))
                    .isEqualTo("alice@example.com");
        }

        @Test
        @DisplayName("A '>' inside a quoted local part stays part of the address")
        void closingBracketInsideTheAddress() {
            assertThat(HeaderAddresses.labelAddress("Bob <\"b>b\"@example.com>")).isEqualTo("\"b>b\"@example.com");
        }

        @Test
        @DisplayName("A label that is not 'name <address>' is an address on its own, trimmed")
        void labelWithoutBrackets() {
            assertThat(HeaderAddresses.labelAddress("  alice@example.com ")).isEqualTo("alice@example.com");
            assertThat(HeaderAddresses.labelAddress("Shop > News")).isEqualTo("Shop > News");
            assertThat(HeaderAddresses.labelAddress("<alice@example.com")).isEqualTo("<alice@example.com");
        }
    }

    @Nested
    @DisplayName("parseLabel")
    class ParseLabel {

        @Test
        @DisplayName("The text before the address is the name, commas and brackets included")
        void nameIsEverythingBeforeTheAddress() {
            InternetAddress sender = HeaderAddresses.parseLabel("Novak, Jan <jan@example.com>");

            assertThat(sender).isNotNull();
            assertThat(sender.getAddress()).isEqualTo("jan@example.com");
            assertThat(sender.getPersonal()).isEqualTo("Novak, Jan");

            InternetAddress spoofed = HeaderAddresses.parseLabel("Alice <alice@evil.example> <alice@example.com>");

            assertThat(spoofed).isNotNull();
            assertThat(spoofed.getAddress()).isEqualTo("alice@example.com");
            assertThat(spoofed.getPersonal()).isEqualTo("Alice <alice@evil.example>");
        }

        @Test
        @DisplayName("A bare address has no name")
        void bareAddress() {
            InternetAddress sender = HeaderAddresses.parseLabel("alice@example.com");

            assertThat(sender).isNotNull();
            assertThat(sender.getAddress()).isEqualTo("alice@example.com");
            assertThat(sender.getPersonal()).isNull();
        }

        @Test
        @DisplayName("An address that does not validate gives no sender")
        void invalidAddress() {
            assertThat(HeaderAddresses.parseLabel("@@@")).isNull();
            assertThat(HeaderAddresses.parseLabel("Shop > News")).isNull();
            assertThat(HeaderAddresses.parseLabel("Nobody <>")).isNull();
        }
    }
}
