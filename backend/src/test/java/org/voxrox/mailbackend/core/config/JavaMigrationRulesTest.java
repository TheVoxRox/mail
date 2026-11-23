package org.voxrox.mailbackend.core.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.CRC32;

import org.flywaydb.core.api.migration.JavaMigration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The two rules a Java migration in {@code db.migration} follows, read from its
 * source (#997; {@code docs/DECISIONS.md}, "A released migration is frozen by
 * its tag").
 *
 * <p>
 * {@code check:migrations} freezes the file of a released migration, and for a
 * SQL migration Flyway adds a second guard: it records a checksum of the file,
 * so an edit that gets out anyway stops the start with exit code 70 instead of
 * leaving two schemas under one version. A Java migration gets that guard only
 * with a checksum of its own. So each one declares
 * {@code private static final int CHECKSUM = <n>;} on a line of its own,
 * returns it from {@code getChecksum()}, and this test requires it to be the
 * CRC32 of the class's source without that line. An edit then fails here, or it
 * changes the checksum, which Flyway refuses on every installation that ran the
 * old one ({@link FlywayAlteredMigrationDetectionTest}).
 *
 * <p>
 * A migration is also a frozen snapshot: what it does has to be in files that
 * {@code check:migrations} freezes. No class in that package names anything in
 * {@code org.voxrox}, so a later change to the application cannot change what a
 * released migration does on the installations that run it later. Logic it
 * needs from the application is copied into it.
 *
 * <p>
 * Read from {@code src/main/java} as checked out, line endings normalized to
 * LF, so the checksum is the one of the file git stores whatever the checkout
 * does to line endings.
 */
class JavaMigrationRulesTest {

    private static final Path JAVA_SOURCES = Path.of("src", "main", "java");

    /**
     * Walked with its subpackages, as Flyway scans {@code classpath:db/migration}
     * and {@code check:migrations} reads the directory.
     */
    private static final Path JAVA_MIGRATIONS = JAVA_SOURCES.resolve(Path.of("db", "migration"));

    /** A versioned Java migration, as Flyway names one: {@code V3__Text}. */
    private static final Pattern VERSIONED = Pattern.compile("V\\d+(?:_\\d+)*__\\w+\\.java");

    /** The declaration left out of the checksum, newline included. */
    private static final Pattern CHECKSUM_LINE = Pattern
            .compile("(?m)^[ \\t]*private static final int CHECKSUM = -?\\d+;[ \\t]*\\n");

    /**
     * The one exception to both rules. It shipped in {@code v0.2.1-beta.2} with
     * neither: every installation of that release recorded it without a checksum,
     * and Flyway never compares a recorded null, so a checksum added now would
     * guard only the installations made after it. It calls {@code TextFold} and
     * {@code StoredText} on purpose, as its javadoc says; their javadoc says what a
     * change to them takes. Its file is frozen by {@code check:migrations} like
     * every released migration's.
     */
    private static final String EXCEPTION = "db.migration.V2__Fold_keys_and_strip_text";

    @Test
    @DisplayName("every Java migration after V2 returns the checksum of its own source")
    void everyJavaMigrationAfterV2CarriesItsSourceChecksum() throws Exception {
        List<String> problems = new ArrayList<>();
        for (Path file : javaFiles()) {
            String name = className(file);
            if (name.equals(EXCEPTION) || !VERSIONED.matcher(file.getFileName().toString()).matches()) {
                continue;
            }
            String source = read(file);
            int expected = sourceChecksum(source);
            long declarations = CHECKSUM_LINE.matcher(source).results().count();
            if (declarations != 1) {
                problems.add(name + " declares `private static final int CHECKSUM = <n>;` on a line of its own "
                        + declarations + " time(s); once, returned from getChecksum(), is the rule. "
                        + "Its source checksum is " + expected + ".");
                continue;
            }
            Integer actual = Class.forName(name).asSubclass(JavaMigration.class).getDeclaredConstructor().newInstance()
                    .getChecksum();
            if (actual == null || actual != expected) {
                problems.add(name + ".getChecksum() returns " + actual + "; its source checksum is " + expected + ".");
            }
        }

        assertThat(problems).as("""
                A Java migration's checksum does not match its source.

                BEFORE its first release: set CHECKSUM to the value above with each edit. A dev \
                database that ran the earlier value then fails at startup, as after editing a SQL \
                migration; reset it.

                AFTER it: do not edit the migration at all. check:migrations refuses the edit; \
                add V<n+1> instead.\
                """).isEmpty();
    }

    @Test
    @DisplayName("no class in db.migration but V2 names application code")
    void noJavaMigrationAfterV2ReachesApplicationCode() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path file : javaFiles()) {
            if (!className(file).equals(EXCEPTION) && read(file).contains("org.voxrox")) {
                offenders.add(className(file));
            }
        }

        assertThat(offenders).as("""
                These files in db.migration name org.voxrox, in an import, a qualified name or a \
                comment. A migration may not call application code: a later change to it would \
                change what a released migration does on the installations that run it later, \
                while check:migrations sees the migration's own file unchanged. Copy the logic \
                into the migration instead.\
                """).isEmpty();
    }

    private static List<Path> javaFiles() throws IOException {
        try (Stream<Path> files = Files.walk(JAVA_MIGRATIONS)) {
            return files.filter(file -> file.getFileName().toString().endsWith(".java")).sorted().toList();
        }
    }

    private static String className(Path file) {
        String relative = JAVA_SOURCES.relativize(file).toString().replace(file.getFileSystem().getSeparator(), ".");
        return relative.substring(0, relative.length() - ".java".length());
    }

    private static String read(Path file) throws IOException {
        return Files.readString(file, StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    private static int sourceChecksum(String source) {
        CRC32 crc = new CRC32();
        crc.update(CHECKSUM_LINE.matcher(source).replaceAll("").getBytes(StandardCharsets.UTF_8));
        return (int) crc.getValue();
    }
}
