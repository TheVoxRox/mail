package org.voxrox.mailbackend.core.diagnostic;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
}
