package org.voxrox.mailbackend.core.diagnostic;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import module java.base;

class DiagnosticLogExportTest {

    @TempDir
    Path logs;

    @Test
    @DisplayName("Live files go first and rotated ones newest first; the file the budget runs out in keeps its newest lines")
    void budgetKeepsTheNewestLines() throws Exception {
        Files.writeString(logs.resolve("mail.log"), "live 1\nlive 2\n");
        Files.writeString(logs.resolve("mail.log.2026-10-10.0"), "older 1\nolder 2\nolder 3\nolder 4\n");
        gzip(logs.resolve("mail.log.2026-10-11.1.gz"), "newer 1\nnewer 2\n");
        gzip(logs.resolve("mail.log.2026-10-11.0.gz"), "new 1\n");

        // Room for the live file (14 bytes), both rotated files of the 11th (16 and 6)
        // and 24 of the 32 bytes of the 10th: with a tenth of that kept spare, its
        // newest two lines.
        Export export = export(14 + 16 + 6 + 24);

        assertThat(export.files().stream().map(DiagnosticLogExport.LogFileDump::file).toList()).containsExactly(
                "mail.log", "mail.log.2026-10-11.1.gz", "mail.log.2026-10-11.0.gz", "mail.log.2026-10-10.0");
        assertThat(export.files().stream().map(DiagnosticLogExport.LogFileDump::status).toList()).containsExactly(
                "complete", "complete", "complete", "truncated: oldest lines left out, log budget reached");
        assertThat(export.entries()).containsEntry("logs/mail.log", "live 1\nlive 2\n")
                .containsEntry("logs/mail.log.2026-10-11.1", "newer 1\nnewer 2\n")
                .containsEntry("logs/mail.log.2026-10-11.0", "new 1\n")
                .containsEntry("logs/mail.log.2026-10-10.0", "older 3\nolder 4\n");
    }

    @Test
    @DisplayName("A file past the budget is listed as left out, not dropped in silence")
    void filesPastTheBudgetAreListed() throws Exception {
        Files.writeString(logs.resolve("mail.log"), "live 1\n");
        Files.writeString(logs.resolve("audit.log"), "audit 1\n");

        Export export = export(3);

        assertThat(export.files()).extracting(DiagnosticLogExport.LogFileDump::status).containsExactly(
                "truncated: oldest lines left out, log budget reached", "left out: log budget reached");
        assertThat(export.entries()).doesNotContainKey("logs/audit.log");
    }

    @Test
    @DisplayName("A file that fails partway keeps what was read, and that counts against the budget")
    void partialReadCountsAgainstTheBudget() throws Exception {
        // A .gz cut short, as one Logback is still compressing looks: no trailer.
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 5000; i++) {
            text.append("line ").append(i).append(" of a rotated log ").append(i * 7919 % 1000).append('\n');
        }
        Path whole = logs.resolve("whole.gz");
        gzip(whole, text.toString());
        byte[] bytes = Files.readAllBytes(whole);
        Files.write(logs.resolve("mail.log.2026-10-11.0.gz"), Arrays.copyOf(bytes, bytes.length / 2));
        Files.delete(whole);
        Files.writeString(logs.resolve("audit.log"), "audit 1\naudit 2\n");

        Export export = export(DiagnosticLogExport.DEFAULT_BUDGET_BYTES);

        DiagnosticLogExport.LogFileDump cut = export.files().get(1);
        assertThat(cut.status()).matches("unreadable after \\d+ bytes: EOFException");
        assertThat(cut.writtenBytes()).isPositive()
                .isEqualTo(export.entries().get("logs/mail.log.2026-10-11.0").getBytes(StandardCharsets.UTF_8).length);
        assertThat(export.entries().get("logs/mail.log.2026-10-11.0")).startsWith("line 0 of a rotated log 0\n");

        // With room for only what the cut file gave, nothing is left for a file after
        // it.
        gzip(logs.resolve("mail.log.2026-10-10.0.gz"), "older 1\n");
        Export tight = export(export.files().get(0).writtenBytes() + cut.writtenBytes());
        assertThat(tight.files().get(2).status()).isEqualTo("left out: log budget reached");
    }

    @Test
    @DisplayName("A file that cannot be read is named as such and the rest still go in")
    void unreadableFileDoesNotStopTheRest() throws Exception {
        Files.writeString(logs.resolve("mail.log.2026-10-11.0.gz"), "not gzip");
        Files.writeString(logs.resolve("audit.log"), "audit 1\n");

        Export export = export(DiagnosticLogExport.DEFAULT_BUDGET_BYTES);

        assertThat(export.files()).extracting(DiagnosticLogExport.LogFileDump::status).containsExactly("complete",
                "unreadable: ZipException");
        assertThat(export.entries()).containsEntry("logs/audit.log", "audit 1\n");
    }

    private record Export(List<DiagnosticLogExport.LogFileDump> files, Map<String, String> entries) {
    }

    private Export export(long budget) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        List<DiagnosticLogExport.LogFileDump> files;
        try (ZipOutputStream zip = new ZipOutputStream(buffer, StandardCharsets.UTF_8)) {
            files = DiagnosticLogExport.write(zip, logs, new DiagnosticLogRedactor(null), budget);
        }
        Map<String, String> entries = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(buffer.toByteArray()),
                StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                entries.put(entry.getName(), new String(zip.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return new Export(files, entries);
    }

    private static void gzip(Path file, String text) throws IOException {
        try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(file))) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }
}
