package org.voxrox.mailbackend.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import module java.base;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * {@link EmailAddresses} over the vectors
 * {@code frontend/src/lib/compose/addresses.ts} runs too, so the form and the
 * backend answer the same for every value, and every address the rule accepts
 * through the parsers the send and the draft use.
 */
class EmailAddressesTest {

    static Stream<Arguments> sharedVectors() throws IOException {
        return vectors().stream();
    }

    /** The accepted vectors, as why and address alone. */
    static Stream<Arguments> validVectors() throws IOException {
        return whyAndAddress(true);
    }

    /** The refused vectors, as why and address alone. */
    static Stream<Arguments> invalidVectors() throws IOException {
        return whyAndAddress(false);
    }

    private static Stream<Arguments> whyAndAddress(boolean valid) throws IOException {
        return vectors().stream().filter(vector -> (boolean) vector.get()[2] == valid)
                .map(vector -> Arguments.of(vector.get()[0], vector.get()[1]));
    }

    private static List<Arguments> vectors() throws IOException {
        JsonNode file;
        try (InputStream in = EmailAddressesTest.class.getResourceAsStream("/text/email-address-vectors.json")) {
            file = new ObjectMapper().readTree(Objects.requireNonNull(in, "email-address-vectors.json"));
        }
        List<Arguments> vectors = new ArrayList<>();
        for (JsonNode vector : file.get("vectors")) {
            vectors.add(Arguments.of(vector.get("why").asString(), vector.get("address").asString(),
                    vector.get("valid").asBoolean()));
        }
        return vectors;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("sharedVectors")
    @DisplayName("A shared vector is an address here exactly when the form takes it for one")
    void sharedVectorIsJudgedAsTheFormJudgesIt(String why, String address, boolean valid) {
        assertThat(EmailAddresses.isValid(address)).as(why).isEqualTo(valid);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("validVectors")
    @DisplayName("An address the rule accepts is one Jakarta Mail sends to, whole, and a draft keeps")
    void acceptedAddressSurvivesTheSendAndTheDraft(String why, String address) throws Exception {
        InternetAddress[] parsed = InternetAddress.parse(address);
        assertThat(parsed).as(why).hasSize(1);
        assertThat(parsed[0].getAddress()).as(why).isEqualTo(address);
        new InternetAddress(address, true).validate();

        assertThat(HeaderAddresses.parseAllValid(address)).as(why).extracting(InternetAddress::getAddress)
                .containsExactly(address);
        assertThat(HeaderAddresses.parseValidTokens(address)).as(why).extracting(InternetAddress::getAddress)
                .containsExactly(address);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidVectors")
    @DisplayName("A value the rule refuses is never kept as an address by a draft")
    void refusedValueIsNotKeptByADraft(String why, String address) {
        assertThat(HeaderAddresses.parseValidTokens(address)).as(why).extracting(InternetAddress::getAddress)
                .doesNotContain(address);
    }

    @Test
    @DisplayName("No value is no address")
    void nullIsNoAddress() {
        assertThat(EmailAddresses.isValid(null)).isFalse();
    }

    @Test
    @DisplayName("A long value without an @ is refused, without scanning past the local part's limit")
    void longValueWithoutAtIsRefused() {
        assertThat(EmailAddresses.isValid("a".repeat(100_000))).isFalse();
        assertThat(EmailAddresses.isValid("a@" + "b".repeat(100_000))).isFalse();
    }

    @Test
    @DisplayName("An address of thousands of domain labels is refused, not a stack overflow (A8)")
    void manyDomainLabelsAreRefusedWithoutOverflow() {
        // The domain's repeated group recursed once per label in the regex engine:
        // a few thousand labels overflowed the stack, in a request and in a sync's
        // harvest of a received header alike.
        String address = "jan@" + "a.".repeat(20_000) + "cz";
        assertThat(EmailAddresses.isValid(address)).isFalse();
        assertThat(HeaderAddresses.parseLabel("Jan <" + address + ">")).isNull();
        assertThat(HeaderAddresses.parseValidTokens(address + ", eva@seznam.cz")).extracting(InternetAddress::getAddress)
                .containsExactly("eva@seznam.cz");
    }

    @Test
    @DisplayName("The send path refuses a recipient field over one token that is not an address")
    void sendPathRefusesTheWholeFieldOverOneToken() {
        assertThatThrownBy(() -> HeaderAddresses.parseAllValid("ok@seznam.cz, jan.@seznam.cz"))
                .isInstanceOf(AddressException.class).hasMessageNotContaining("jan");
        assertThatThrownBy(() -> HeaderAddresses.parseAllValid(" , ")).isInstanceOf(AddressException.class);
    }

    @Test
    @DisplayName("The send path keeps a display name next to its address")
    void sendPathKeepsDisplayName() throws Exception {
        InternetAddress[] parsed = HeaderAddresses.parseAllValid("\"Novak, Jan\" <jan@seznam.cz>, eva@seznam.cz");
        assertThat(parsed).extracting(InternetAddress::getAddress).containsExactly("jan@seznam.cz", "eva@seznam.cz");
        assertThat(parsed[0].getPersonal()).isEqualTo("Novak, Jan");
    }
}
