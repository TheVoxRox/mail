package org.voxrox.mailbackend.core.init;

import java.lang.management.ManagementFactory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.voxrox.mailbackend.util.LogCategory;

import module java.base;

@Service
public class StartupTimingService {

    private static final Logger log = LoggerFactory.getLogger(StartupTimingService.class);

    /*
     * JVM start, read from the runtime MXBean rather than from a clock read here.
     *
     * This was Instant.now() in the static initialiser, on the premise that the
     * class loads in the first moments of boot and so approximates the process
     * start. On a packaged sidecar it does not: class initialisation landed 3.81 s
     * and 4.02 s after JVM start across two boots of the same build, because the
     * jpackage launcher, the JVM itself and Spring's AOT class loading all run
     * before anything here can observe a clock. That time is boot the user waits
     * through, so leaving it out understated the whole sequence - a 17.4 s start
     * was reported as 13.3 s, and this dump exists to diagnose slow starts.
     *
     * java.management is in the sidecar's --add-modules list in
     * package-sidecar-windows.ps1, and the jdeps step there would fail the build if
     * it were not.
     */
    private static final long JVM_STARTED_AT_MILLIS = ManagementFactory.getRuntimeMXBean().getStartTime();

    /*
     * How much of the boot had already passed when this class loaded. Wall clock
     * deliberately: it is the only clock that can be related to a value the MXBean
     * reports in epoch millis. Everything measured after this point uses nanoTime
     * instead, so a clock adjustment mid-boot can shift this one constant but
     * cannot distort a phase duration.
     *
     * (TimeInStaticInitializer warns against accidental load-time capture; here the
     * load-time capture is the measurement.)
     */
    @SuppressWarnings("TimeInStaticInitializer")
    private static final long ELAPSED_BEFORE_CLASS_INIT_MILLIS = System.currentTimeMillis() - JVM_STARTED_AT_MILLIS;

    private static final long CLASS_INIT_NANOS = System.nanoTime();
    private static final Instant PROCESS_STARTED_AT = Instant.ofEpochMilli(JVM_STARTED_AT_MILLIS);
    private static final Map<String, StartupTimingSnapshot> TIMINGS = new ConcurrentHashMap<>();

    public long start() {
        return startNanos();
    }

    public void record(String phase, long startedNanos) {
        recordPhase(phase, startedNanos);
    }

    public List<StartupTimingSnapshot> snapshots() {
        List<StartupTimingSnapshot> snapshots = new ArrayList<>(TIMINGS.values());
        snapshots.sort(Comparator.comparing(StartupTimingSnapshot::finishedAt));
        return snapshots;
    }

    public static long startNanos() {
        return System.nanoTime();
    }

    public static void recordPhase(String phase, long startedNanos) {
        long durationMs = Math.max(0L, (System.nanoTime() - startedNanos) / 1_000_000L);
        long startedAfterProcessMs = Math.max(0L,
                ELAPSED_BEFORE_CLASS_INIT_MILLIS + (startedNanos - CLASS_INIT_NANOS) / 1_000_000L);
        StartupTimingSnapshot snapshot = new StartupTimingSnapshot(phase, durationMs, startedAfterProcessMs,
                Instant.now().toString());
        TIMINGS.put(phase, snapshot);
        log.info("{} Startup timing: phase={} durationMs={}", LogCategory.BOOT, phase, durationMs);
    }

    public static Instant processStartedAt() {
        return PROCESS_STARTED_AT;
    }

    public record StartupTimingSnapshot(String phase, long durationMs, long startedAfterProcessMs, String finishedAt) {
    }
}
