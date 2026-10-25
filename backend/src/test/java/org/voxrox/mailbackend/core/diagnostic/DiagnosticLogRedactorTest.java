package org.voxrox.mailbackend.core.diagnostic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import module java.base;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class DiagnosticLogRedactorTest {

    private static final String HEAD = "2026-10-12T10:00:00.000+02:00  INFO 1 --- [mail] [main] o.v.m.MailSyncService : ";

    @Test
    @DisplayName("A line that keeps the log's rules goes into the bundle unchanged, folder names and paths included")
    void lineThatKeepsTheRulesIsUnchanged() {
        DiagnosticLogRedactor redactor = new DiagnosticLogRedactor("canary-api-key-4417-abcdefghij");
        String line = HEAD + "[SYNC] Initial sync of folder Lawyer/Divorce for j***k@seznam.cz, data in "
                + "C:\\Users\\Lukas\\AppData\\Local\\VoxRox\\Mail";

        assertThat(redactor.redact(line)).isEqualTo(line);
        assertThat(redactor.counts()).isEqualTo(new DiagnosticLogRedactor.Counts(0, 0, 0));
    }

    @Test
    @DisplayName("An address a server wrote into an error is masked whole, also with a non-ASCII local part")
    void addressesAreMasked() {
        DiagnosticLogRedactor redactor = new DiagnosticLogRedactor(null);

        assertThat(redactor.redact(HEAD + "550 <jürgen.müller@example.de> unknown"))
                .isEqualTo(HEAD + "550 <j***r@example.de> unknown");
        assertThat(redactor.counts().addresses()).isEqualTo(1);
    }

    @Test
    @DisplayName("An address or Message-ID with an apostrophe, '=' or '+' in its local part is masked whole")
    void localPartPunctuationIsMaskedWhole() {
        DiagnosticLogRedactor redactor = new DiagnosticLogRedactor(null);

        assertThat(redactor.redact(HEAD + "550 o'brien.jan@firma.cz: unknown"))
                .isEqualTo(HEAD + "550 o***n@firma.cz: unknown");
        assertThat(redactor.redact(HEAD + "References <CAFoo=Xy+abc@mail.gmail.com>"))
                .isEqualTo(HEAD + "References <C***c@mail.gmail.com>");
    }

    @Test
    @DisplayName("Token shapes and the session API key are replaced")
    void tokensAndTheApiKeyAreReplaced() {
        DiagnosticLogRedactor redactor = new DiagnosticLogRedactor("canary-api-key-4417-abcdefghij");

        assertThat(redactor.redact(HEAD + "Authorization: Bearer abcdefghijklmnopqrstuvwxyz"))
                .isEqualTo(HEAD + "Authorization: [token]");
        assertThat(redactor.redact(HEAD + "got ya29.a0AfH6SMBabcdefghijklmnop and refresh_token=1//0abcdefghijklmnop"))
                .isEqualTo(HEAD + "got [token] and [token]");
        assertThat(redactor.redact(HEAD + "X-API-KEY canary-api-key-4417-abcdefghij rejected"))
                .isEqualTo(HEAD + "X-API-KEY [api-key] rejected");
        assertThat(redactor.counts()).isEqualTo(new DiagnosticLogRedactor.Counts(0, 3, 1));
    }

    /*
     * API_SURFACE_AUDIT.md A5. Java's \b walked back through every mark before each
     * position it was tried at, and the address lookbehind, reading one UTF-16 unit
     * back, let a match start after every character outside the Basic Multilingual
     * Plane along a run; each of these lines took minutes.
     */
    @Test
    @DisplayName("A long run of combining marks, or of characters outside the BMP, is redacted in linear time")
    void longRunsAreRedactedInLinearTime() {
        DiagnosticLogRedactor redactor = new DiagnosticLogRedactor(null);
        String marks = "a" + "ͅ".repeat(200_000);
        String pairs = "x@ " + "𝐀a".repeat(200_000);

        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            assertThat(redactor.redact(marks + "Bearer abcdefghijklmnopqrstuvwxyz")).isEqualTo(marks + "[token]");
            assertThat(redactor.redact(pairs)).isEqualTo(pairs);
        });
    }

    /*
     * A6. A regex group over the labels took a stack frame a label, and from about
     * two thousand of them the dump failed with StackOverflowError.
     */
    @Test
    @DisplayName("An address whose domain has a hundred thousand labels is masked, not a stack overflow")
    void domainOfManyLabelsIsMasked() {
        DiagnosticLogRedactor redactor = new DiagnosticLogRedactor(null);
        String labels = ".cc".repeat(100_000);

        assertThat(redactor.redact("a@b" + labels + "-")).isEqualTo("a***@b" + labels + "-");
        assertThat(redactor.counts().addresses()).isEqualTo(1);
    }

    /*
     * The vectors the shell's Save logs ZIP runs too, in redact.rs: the same line
     * comes out of both redactors the same, with the same replacements counted,
     * known misses included.
     */
    static Stream<Arguments> sharedVectors() throws IOException {
        JsonNode file;
        try (InputStream in = DiagnosticLogRedactorTest.class
                .getResourceAsStream("/diagnostic/redaction-vectors.json")) {
            file = new ObjectMapper().readTree(Objects.requireNonNull(in, "redaction-vectors.json"));
        }
        String apiKey = file.get("apiKey").asString();
        List<Arguments> vectors = new ArrayList<>();
        for (JsonNode vector : file.get("vectors")) {
            vectors.add(Arguments.of(vector.get("why").asString(), apiKey, vector.get("line").asString(),
                    vector.get("redacted").asString(),
                    new DiagnosticLogRedactor.Counts(vector.get("addresses").asLong(), vector.get("tokens").asLong(),
                            vector.get("apiKeys").asLong())));
        }
        return vectors.stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("sharedVectors")
    @DisplayName("A shared vector comes out as the shell's Save logs ZIP writes it")
    void sharedVectorComesOutAsTheShellWritesIt(String why, String apiKey, String line, String redacted,
            DiagnosticLogRedactor.Counts counts) {
        DiagnosticLogRedactor redactor = new DiagnosticLogRedactor(apiKey);

        assertThat(redactor.redact(line)).as(why).isEqualTo(redacted);
        assertThat(redactor.counts()).as(why).isEqualTo(counts);
    }
}
