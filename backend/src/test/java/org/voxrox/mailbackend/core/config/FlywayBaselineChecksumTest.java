package org.voxrox.mailbackend.core.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Arrays;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Freezes the checksum of {@code V1__init.sql}.
 *
 * <p>
 * Until the first public release the baseline was edited in place and dev
 * databases were reset — three commits even squashed later migrations back into
 * it. That workflow ends the day a build is installed on someone else's
 * machine: Flyway records V1's checksum in {@code flyway_schema_history}, and
 * {@code validate-on-migrate} (left at its {@code true} default, see
 * {@code application.properties}) rejects a migration whose file no longer
 * hashes to the recorded value. The sidecar then refuses to start on every
 * existing installation, and the failure cannot be repaired through the updater
 * — see the V1 freeze entry in {@code RELEASE_CHECKLIST.md}.
 *
 * <p>
 * The guard pins Flyway's <em>own</em> checksum rather than a file hash, so the
 * test compares exactly what the startup validation compares. It targets
 * version 1 explicitly, not {@code info().current()}, so it keeps guarding the
 * baseline once V2+ exist.
 *
 * <p>
 * Deliberately not updatable through a system property the way
 * {@link OpenApiSnapshotTest} is: re-pinning has to be a conscious edit of the
 * constant below, next to the comment explaining when that is allowed.
 */
class FlywayBaselineChecksumTest {

    /**
     * Flyway's checksum of {@code V1__init.sql}.
     *
     * <p>
     * <b>Before the first public release</b> this value may be updated together
     * with an intentional edit of the baseline — that is the pre-release workflow
     * and the reason this constant exists rather than the file being immutable.
     *
     * <p>
     * <b>After the first public release, never.</b> A schema change becomes a new
     * {@code V2__*.sql}. Updating this constant to silence the test would ship a
     * build that cannot start against any database created by an earlier version.
     */
    private static final int PINNED_V1_CHECKSUM = -1808531355;

    private static final MigrationVersion V1 = MigrationVersion.fromVersion("1");

    @Test
    @DisplayName("V1__init.sql still hashes to the pinned baseline checksum")
    void baselineChecksumIsFrozen(@TempDir Path tempDir) {
        Flyway flyway = Flyway.configure().dataSource("jdbc:sqlite:" + tempDir.resolve("checksum-probe.db"), null, null)
                .locations("classpath:db/migration").load();
        flyway.migrate();

        MigrationInfo baseline = Arrays.stream(flyway.info().all()).filter(info -> V1.equals(info.getVersion()))
                .findFirst().orElseThrow(() -> new AssertionError("No V1 migration found in classpath:db/migration"));

        assertThat(baseline.getChecksum()).as("""
                V1__init.sql changed (checksum %s, pinned %s).

                BEFORE the first public release: update PINNED_V1_CHECKSUM in this test \
                together with the baseline edit — that is the intended pre-release workflow.

                AFTER it: never. Add a V2__*.sql migration instead. An edited V1 makes every \
                installed copy fail at startup with a Flyway checksum mismatch, and the \
                updater cannot fix a build that refuses to boot (RELEASE_CHECKLIST.md, \
                "First Release Gate" -> the V1 freeze entry).\
                """, baseline.getChecksum(), PINNED_V1_CHECKSUM).isEqualTo(PINNED_V1_CHECKSUM);
    }
}
