package org.voxrox.mailbackend.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import module java.base;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * {@link StoredText} over the vectors {@code frontend/src/lib/storedText.ts}
 * runs too: a value comes out of both the same, so what the form shows is what
 * the backend stores.
 */
class StoredTextTest {

    static Stream<Arguments> sharedVectors() throws IOException {
        JsonNode file;
        try (InputStream in = StoredTextTest.class.getResourceAsStream("/text/stored-text-vectors.json")) {
            file = new ObjectMapper().readTree(Objects.requireNonNull(in, "stored-text-vectors.json"));
        }
        List<Arguments> vectors = new ArrayList<>();
        for (JsonNode vector : file.get("vectors")) {
            vectors.add(Arguments.of(vector.get("why").asString(), vector.get("input").asString(),
                    textOrNull(vector.get("stripToNull")), textOrNull(vector.get("stripBlankLinesToNull"))));
        }
        return vectors.stream();
    }

    private static @Nullable String textOrNull(JsonNode node) {
        return node.isNull() ? null : node.asString();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("sharedVectors")
    @DisplayName("A shared vector is stored as the frontend sends it, as a single line and as a block")
    void sharedVectorIsStoredAsTheFrontendSendsIt(String why, String input, @Nullable String singleLine,
            @Nullable String block) {
        assertThat(StoredText.stripToNull(input)).as(why).isEqualTo(singleLine);
        assertThat(StoredText.strip(input)).as(why).isEqualTo(singleLine == null ? "" : singleLine);
        assertThat(StoredText.stripBlankLinesToNull(input)).as(why).isEqualTo(block);
    }

    @Test
    @DisplayName("Nothing given is no value, as a single line and as a block")
    void nothingGivenIsNoValue() {
        assertThat(StoredText.stripToNull(null)).isNull();
        assertThat(StoredText.stripBlankLinesToNull(null)).isNull();
    }
}
