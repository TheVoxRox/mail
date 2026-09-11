package org.voxrox.mailbackend.core.init;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.management.ManagementFactory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.voxrox.mailbackend.core.init.StartupTimingService.StartupTimingSnapshot;

import module java.base;

class StartupTimingServiceTest {

    /**
     * Wall-clock granularity on Windows is coarse (a currentTimeMillis tick can be
     * ~15 ms), and the offset crosses from the wall clock onto the monotonic one,
     * so the bracket below needs slack at both ends. It is not a threshold the
     * assertion depends on for its meaning - the bracket is what carries that.
     */
    private static final long CLOCK_SLACK_MILLIS = 250L;

    @Test
    @DisplayName("processStartedAt is the JVM start, not the moment this class loaded")
    void processStartedAtIsTheJvmStart() {
        long jvmStart = ManagementFactory.getRuntimeMXBean().getStartTime();

        assertThat(StartupTimingService.processStartedAt().toEpochMilli()).isEqualTo(jvmStart);
    }

    @Test
    @DisplayName("a phase offset counts the boot that happened before this class loaded")
    void phaseOffsetIsMeasuredFromJvmStart() {
        long jvmStart = ManagementFactory.getRuntimeMXBean().getStartTime();
        long elapsedBefore = System.currentTimeMillis() - jvmStart;

        long started = StartupTimingService.startNanos();
        StartupTimingService.recordPhase("test.offset-anchor", started);

        long elapsedAfter = System.currentTimeMillis() - jvmStart;
        StartupTimingSnapshot snapshot = snapshotOf("test.offset-anchor");

        /*
         * The JVM has been up for the whole of JUnit's own start by the time this runs,
         * so an offset anchored on class initialisation instead would sit near zero and
         * fall out of the bracket's lower end.
         */
        assertThat(snapshot.startedAfterProcessMs()).isBetween(elapsedBefore - CLOCK_SLACK_MILLIS,
                elapsedAfter + CLOCK_SLACK_MILLIS);
    }

    private StartupTimingSnapshot snapshotOf(String phase) {
        return new StartupTimingService().snapshots().stream().filter(s -> s.phase().equals(phase)).findFirst()
                .orElseThrow(() -> new AssertionError("phase not recorded: " + phase));
    }
}
