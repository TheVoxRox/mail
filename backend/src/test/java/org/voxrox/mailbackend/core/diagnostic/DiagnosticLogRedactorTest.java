package org.voxrox.mailbackend.core.diagnostic;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DiagnosticLogRedactorTest {

    private static final String HEAD = "2026-10-12T10:00:00.000+02:00  INFO 1 --- [mail] [main] o.v.m.MailSyncService : ";

    @Test
    @DisplayName("An address is masked whole, also with a non-ASCII local part, and a masked one is left as it is")
    void addressesAreMasked() {
        DiagnosticLogRedactor redactor = DiagnosticLogRedactor.builder().build();

        assertThat(redactor.redact(HEAD + "550 <jürgen.müller@example.de> unknown"))
                .isEqualTo(HEAD + "550 <j***r@example.de> unknown");
        assertThat(redactor.redact(HEAD + "Starting account sync: j***k@seznam.cz"))
                .isEqualTo(HEAD + "Starting account sync: j***k@seznam.cz");
        assertThat(redactor.counts().addresses()).isEqualTo(1);
    }

    @Test
    @DisplayName("An address or Message-ID with an apostrophe, '=' or '+' in its local part is masked whole")
    void localPartPunctuationIsMaskedWhole() {
        DiagnosticLogRedactor redactor = DiagnosticLogRedactor.builder().build();

        assertThat(redactor.redact(HEAD + "550 o'brien.jan@firma.cz: unknown"))
                .isEqualTo(HEAD + "550 o***n@firma.cz: unknown");
        assertThat(redactor.redact(HEAD + "References <CAFoo=Xy+abc@mail.gmail.com>"))
                .isEqualTo(HEAD + "References <C***c@mail.gmail.com>");
    }

    @Test
    @DisplayName("A known value is replaced as a whole word, as written and percent-encoded")
    void knownValuesAreReplacedAsWholeWords() {
        DiagnosticLogRedactor redactor = DiagnosticLogRedactor.builder().known("Lawyer Divorce", "folder-1")
                .known("Taxes", "folder-2").build();

        assertThat(redactor.redact(HEAD + "Initial sync of folder Lawyer Divorce: done"))
                .isEqualTo(HEAD + "Initial sync of folder folder-1: done");
        assertThat(redactor.redact(HEAD + "URI: /api/v1/accounts/1/folders/Lawyer%20Divorce/messages"))
                .isEqualTo(HEAD + "URI: /api/v1/accounts/1/folders/folder-1/messages");
        assertThat(redactor.redact(HEAD + "Taxes, MyTaxes and Taxes2"))
                .isEqualTo(HEAD + "folder-2, MyTaxes and Taxes2");
    }

    @Test
    @DisplayName("The longer of two overlapping values is replaced as itself")
    void longerValueWins() {
        DiagnosticLogRedactor redactor = DiagnosticLogRedactor.builder().known("Projects", "folder-1")
                .known("Projects/Client", "folder-2").build();

        assertThat(redactor.redact(HEAD + "Moving UID 7 from Projects to Projects/Client"))
                .isEqualTo(HEAD + "Moving UID 7 from folder-1 to folder-2");
    }

    @Test
    @DisplayName("A line's head stays as written, whatever a folder is called")
    void headIsLeftAlone() {
        DiagnosticLogRedactor redactor = DiagnosticLogRedactor.builder().known("main", "folder-1")
                .known("mail", "folder-2").build();

        assertThat(redactor.redact(HEAD + "Opened folder main")).isEqualTo(HEAD + "Opened folder folder-1");
        String audit = "2026-10-12 10:00:00.000 [main] INFO  SUCCESS action=folder_opened detail=main";
        assertThat(redactor.redact(audit))
                .isEqualTo("2026-10-12 10:00:00.000 [main] INFO  SUCCESS action=folder_opened detail=folder-1");
        String tauri = "[2026-10-12][08:00:00][mail_lib][INFO] Opened mail";
        assertThat(redactor.redact(tauri)).isEqualTo("[2026-10-12][08:00:00][mail_lib][INFO] Opened folder-2");
    }

    @Test
    @DisplayName("A value shorter than three characters or without a letter is not replaced")
    void shortAndLetterlessValuesAreKept() {
        DiagnosticLogRedactor redactor = DiagnosticLogRedactor.builder().known("2025", "folder-1")
                .known("AB", "folder-2").build();

        assertThat(redactor.redact(HEAD + "UID 2025 in AB")).isEqualTo(HEAD + "UID 2025 in AB");
    }

    @Test
    @DisplayName("The home directory becomes ~ in either separator and any case, but not a sibling that starts the same")
    void homeIsReplaced() {
        DiagnosticLogRedactor redactor = DiagnosticLogRedactor.builder().home("C:\\Users\\Lukas\\").build();

        assertThat(redactor.redact(HEAD + "jdbc:sqlite:c:\\users\\lukas\\AppData/db/mail.db"))
                .isEqualTo(HEAD + "jdbc:sqlite:~\\AppData/db/mail.db");
        assertThat(redactor.redact(HEAD + "C:/Users/Lukas/AppData")).isEqualTo(HEAD + "~/AppData");
        assertThat(redactor.redact(HEAD + "(C:\\Users\\Lukas)")).isEqualTo(HEAD + "(~)");
        assertThat(redactor.redact(HEAD + "C:\\Users\\Lukas2\\AppData")).isEqualTo(HEAD + "C:\\Users\\Lukas2\\AppData");
    }

    @Test
    @DisplayName("The Windows account name is replaced as a word in any case")
    void userNameIsReplaced() {
        DiagnosticLogRedactor redactor = DiagnosticLogRedactor.builder().userName("lukas").build();

        assertThat(redactor.redact(HEAD + "app.jar started by Lukas in C:\\app"))
                .isEqualTo(HEAD + "app.jar started by [user] in C:\\app");
        assertThat(redactor.redact(HEAD + "Lukasiewicz")).isEqualTo(HEAD + "Lukasiewicz");
    }

    @Test
    @DisplayName("Token shapes are replaced, as npm run release:scan-logs would report them")
    void tokensAreReplaced() {
        DiagnosticLogRedactor redactor = DiagnosticLogRedactor.builder().build();

        assertThat(redactor.redact(HEAD + "Authorization: Bearer abcdefghijklmnopqrstuvwxyz"))
                .isEqualTo(HEAD + "Authorization: [token]");
        assertThat(redactor.redact(HEAD + "got ya29.a0AfH6SMBabcdefghijklmnop and refresh_token=1//0abcdefghijklmnop"))
                .isEqualTo(HEAD + "got [token] and [token]");
        assertThat(redactor.counts().tokens()).isEqualTo(3);
    }
}
