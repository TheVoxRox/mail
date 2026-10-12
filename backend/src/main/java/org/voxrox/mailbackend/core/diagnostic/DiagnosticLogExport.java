package org.voxrox.mailbackend.core.diagnostic;

import org.jspecify.annotations.Nullable;

import module java.base;

/**
 * Copies the logs folder into the diagnostic bundle, through a
 * {@link DiagnosticLogRedactor}, newest first and within a budget.
 *
 * <p>
 * The folder holds three families: {@code mail.log} (the backend, rotated to
 * {@code mail.log.<date>.<n>.gz}), {@code audit.log} (rotated to
 * {@code audit.<date>.<n>.log.gz}) and {@code mail-frontend*.log} (the Tauri
 * shell). Each goes in under {@code logs/} with the name it has on disk, less a
 * {@code .gz}: the copy is redacted line by line, so it cannot be the
 * compressed file, and those are the names {@code npm run release:scan-logs}
 * reads, so the unpacked folder scans as it is.
 *
 * <p>
 * Order is what support reads first: the three live files, then the rotated
 * backend logs from the newest, then the rotated audit logs. Rotation caps one
 * file at 10 MB of text, so the budget always takes the live files whole; the
 * file it runs out in keeps its newest lines, and the rest are listed as left
 * out, so the bundle says what it does not carry.
 *
 * <p>
 * Files are opened through NIO, which on Windows shares delete access: a read
 * that is still running when Logback rotates {@code mail.log} must not make
 * that rename fail.
 */
final class DiagnosticLogExport {

    /** Uncompressed text the bundle takes from the logs, at most. */
    static final long DEFAULT_BUDGET_BYTES = 24L * 1024 * 1024;

    private static final String ENTRY_PREFIX = "logs/";

    private static final String GZ = ".gz";
    /** A gzip header (10 bytes) and trailer (8 bytes) around an empty body. */
    private static final int GZIP_MINIMUM_BYTES = 18;
    /** The most deflate can shrink data by (zlib technical details: 1032:1). */
    private static final long DEFLATE_MAX_RATIO = 1032;
    private static final List<String> LIVE = List.of("mail.log", "mail-frontend.log", "audit.log");
    private static final Pattern ROTATED_MAIL = Pattern
            .compile("^mail\\.log\\.(\\d{4}-\\d{2}-\\d{2})\\.(\\d+)(?:\\.gz)?$");
    private static final Pattern ROTATED_AUDIT = Pattern
            .compile("^audit\\.(\\d{4}-\\d{2}-\\d{2})\\.(\\d+)\\.log(?:\\.gz)?$");
    private static final Pattern FRONTEND = Pattern.compile("^mail-frontend.*\\.log$");

    private DiagnosticLogExport() {
    }

    /**
     * One file of the logs folder and what became of it in the bundle.
     * {@code textBytes} is the file's text, uncompressed, and {@code -1} when it
     * could not be told.
     */
    record LogFileDump(String file, long textBytes, long writtenBytes, String status) {
        private static final String COMPLETE = "complete";
        private static final String TRUNCATED = "truncated: oldest lines left out, log budget reached";
        private static final String LEFT_OUT = "left out: log budget reached";
    }

    /**
     * Writes every log file of {@code logsDir} the budget allows as an entry of
     * {@code zip}, and returns one record per file found, in the order they were
     * considered.
     */
    static List<LogFileDump> write(ZipOutputStream zip, Path logsDir, DiagnosticLogRedactor redactor, long budget)
            throws IOException {
        List<LogFileDump> result = new ArrayList<>();
        long remaining = budget;
        for (Path file : ordered(logsDir)) {
            String name = nameOf(file);
            long textBytes = textBytes(file);
            if (remaining <= 0) {
                result.add(new LogFileDump(name, textBytes, 0, LogFileDump.LEFT_OUT));
                continue;
            }
            Copy copy = copy(zip, file, entryName(name), redactor, textBytes, remaining);
            // What a failed read had written before it failed is in the entry, so
            // it counts against the budget like any other line.
            remaining -= copy.written();
            String status;
            if (copy.failure() != null) {
                status = copy.written() > 0
                        ? "unreadable after " + copy.written() + " bytes: " + copy.failure()
                        : "unreadable: " + copy.failure();
            } else {
                status = copy.truncated() ? LogFileDump.TRUNCATED : LogFileDump.COMPLETE;
            }
            result.add(new LogFileDump(name, textBytes, copy.written(), status));
            if (copy.truncated()) {
                remaining = 0;
            }
        }
        return result;
    }

    private static String entryName(String fileName) {
        String name = fileName.endsWith(GZ) ? fileName.substring(0, fileName.length() - GZ.length()) : fileName;
        return ENTRY_PREFIX + name;
    }

    private record Copy(long written, boolean truncated, @Nullable String failure) {
    }

    /**
     * Copies one file into its own entry. A file whose text is larger than the
     * budget keeps its newest lines: as many of its oldest are skipped as make it
     * fit, with a tenth of the budget to spare for a redacted line coming out
     * longer than it went in, and what still does not fit is cut at the end.
     *
     * <p>
     * A read that fails ends the entry where it got to and names the failure, so
     * the bundle carries what could be read and says why the rest is missing:
     * Logback compresses a rotated file in the background, and a dump made in that
     * moment meets a {@code .gz} that ends early.
     */
    private static Copy copy(ZipOutputStream zip, Path file, String entryName, DiagnosticLogRedactor redactor,
            long textBytes, long budget) throws IOException {
        zip.putNextEntry(new ZipEntry(entryName));
        long toSkip = textBytes > budget ? textBytes - (budget - budget / 10) : 0;
        long written = 0;
        boolean truncated = toSkip > 0;
        String failure = null;
        try (BufferedReader reader = open(file)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (toSkip > 0) {
                    toSkip -= line.getBytes(StandardCharsets.UTF_8).length + 1L;
                    continue;
                }
                byte[] bytes = (redactor.redact(line) + "\n").getBytes(StandardCharsets.UTF_8);
                if (written + bytes.length > budget) {
                    truncated = true;
                    break;
                }
                zip.write(bytes);
                written += bytes.length;
            }
        } catch (IOException | UncheckedIOException e) {
            failure = e.getClass().getSimpleName();
        }
        zip.closeEntry();
        return new Copy(written, truncated, failure);
    }

    /**
     * The size of a file's text: its size, or for a {@code .gz} the ISIZE field the
     * gzip trailer ends with (RFC 1952), which is the uncompressed length mod 2^32
     * of the one member Logback writes.
     */
    private static long textBytes(Path file) {
        try {
            if (!nameOf(file).endsWith(GZ)) {
                return Files.size(file);
            }
            try (SeekableByteChannel channel = Files.newByteChannel(file)) {
                long size = channel.size();
                if (size < GZIP_MINIMUM_BYTES) {
                    return -1;
                }
                ByteBuffer trailer = ByteBuffer.allocate(Integer.BYTES).order(ByteOrder.LITTLE_ENDIAN);
                channel.position(size - Integer.BYTES);
                while (trailer.hasRemaining() && channel.read(trailer) >= 0) {
                    // read until the four bytes are in or the file ends
                }
                if (trailer.hasRemaining()) {
                    return -1;
                }
                // A file Logback is still compressing has no trailer yet, and its
                // last four bytes are compressed data: a length deflate could not
                // have produced from this many bytes is that, not a length.
                long isize = Integer.toUnsignedLong(trailer.getInt(0));
                return isize >= size / 2 && isize <= size * DEFLATE_MAX_RATIO ? isize : -1;
            }
        } catch (IOException e) {
            return -1;
        }
    }

    /*
     * InputStreamReader replaces a malformed sequence instead of throwing, which
     * Files.newBufferedReader would do: one bad byte in a log must not cost the
     * rest of the file.
     */
    private static BufferedReader open(Path file) throws IOException {
        InputStream in = Files.newInputStream(file);
        try {
            InputStream source = nameOf(file).endsWith(GZ) ? new GZIPInputStream(in) : in;
            return new BufferedReader(new InputStreamReader(source, StandardCharsets.UTF_8));
        } catch (IOException e) {
            in.close();
            throw e;
        }
    }

    private static String nameOf(Path file) {
        Path name = file.getFileName();
        return name != null ? name.toString() : "";
    }

    /** The log files of {@code logsDir} in the order the bundle takes them. */
    private static List<Path> ordered(Path logsDir) throws IOException {
        if (!Files.isDirectory(logsDir)) {
            return List.of();
        }
        List<Path> files;
        try (Stream<Path> listing = Files.list(logsDir)) {
            files = listing.filter(Files::isRegularFile).toList();
        }
        Map<String, Path> byName = new HashMap<>();
        files.forEach(file -> byName.put(nameOf(file), file));

        List<Path> ordered = new ArrayList<>();
        for (String live : LIVE) {
            Path file = byName.get(live);
            if (file != null) {
                ordered.add(file);
            }
        }
        ordered.addAll(rotated(byName, ROTATED_MAIL));
        ordered.addAll(rotated(byName, ROTATED_AUDIT));
        byName.entrySet().stream()
                .filter(entry -> !LIVE.contains(entry.getKey()) && FRONTEND.matcher(entry.getKey()).matches())
                .sorted(Map.Entry.<String, Path>comparingByKey().reversed()).map(Map.Entry::getValue)
                .forEach(ordered::add);
        return ordered;
    }

    /** Rotated files of one family, newest first: by date, then by index. */
    private static List<Path> rotated(Map<String, Path> byName, Pattern family) {
        record Rotated(Path file, String date, int index) {
        }
        List<Rotated> found = new ArrayList<>();
        byName.forEach((name, file) -> {
            Matcher matcher = family.matcher(name);
            if (matcher.matches()) {
                found.add(new Rotated(file, matcher.group(1), parseIndex(matcher.group(2))));
            }
        });
        return found.stream().sorted(Comparator.comparing(Rotated::date).thenComparingInt(Rotated::index).reversed())
                .map(Rotated::file).toList();
    }

    private static int parseIndex(@Nullable String digits) {
        try {
            return digits != null ? Integer.parseInt(digits) : 0;
        } catch (NumberFormatException e) {
            return Integer.MAX_VALUE;
        }
    }
}
