package org.voxrox.mailbackend.core.init;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.voxrox.mailbackend.util.LogCategory;

import module java.base;

@Service
public class StartupTimingService {

    private static final Logger log = LoggerFactory.getLogger(StartupTimingService.class);
    private static final long PROCESS_STARTED_NANOS = System.nanoTime();
    /*
     * Captured at class initialisation DELIBERATELY — this class loads in the first
     * moments of boot, so the value approximates the process start time exposed by
     * processStartedAt(). (TimeInStaticInitializer warns against accidental
     * load-time capture; here it is the whole point.)
     */
    @SuppressWarnings("TimeInStaticInitializer")
    private static final Instant PROCESS_STARTED_AT = Instant.now();
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
        StartupTimingSnapshot snapshot = new StartupTimingSnapshot(phase, durationMs,
                Math.max(0L, (startedNanos - PROCESS_STARTED_NANOS) / 1_000_000L), Instant.now().toString());
        TIMINGS.put(phase, snapshot);
        log.info("{} Startup timing: phase={} durationMs={}", LogCategory.BOOT, phase, durationMs);
    }

    public static Instant processStartedAt() {
        return PROCESS_STARTED_AT;
    }

    public record StartupTimingSnapshot(String phase, long durationMs, long startedAfterProcessMs, String finishedAt) {
    }
}
