package org.voxrox.mailbackend.core.lifecycle;

import java.io.IOException;
import java.io.InputStream;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.voxrox.mailbackend.util.LogCategory;

/**
 * Self-terminates the backend when the desktop frontend that spawned it dies,
 * so a force-killed parent (Task Manager "End task", {@code kill -9}, a crash)
 * can never leave the sidecar JVM running as an orphan.
 *
 * <p>
 * The frontend launches the sidecar via the Tauri shell plugin, which wires the
 * child's stdin to a pipe owned by the frontend process. The frontend never
 * writes to that pipe, so a {@code read()} here blocks indefinitely while the
 * parent is alive. The moment the parent goes away — gracefully or not — the OS
 * closes the write end and the read returns EOF (or the pipe breaks with an
 * {@link IOException}). Either way we treat it as "parent gone" and exit.
 *
 * <p>
 * This is the OS-agnostic counterpart to a Windows job object: it needs no
 * native code, covers every kind of parent death (not just the clean shutdown
 * the frontend's {@code beforeunload}/{@code kill()} path already handles), and
 * triggers regardless of how the process tree is nested. Without it, the
 * sidecar runs on an ephemeral port, so the single-instance guard (exit 78 in
 * {@link org.voxrox.mailbackend.MailBackendApplication}) never fires against
 * the zombie and a later launch can start a second backend over the same
 * database.
 *
 * <p>
 * Gated on {@value #WATCH_PARENT_ENV}{@code =1}, set only by the frontend when
 * it spawns the sidecar (see {@code frontend/src/lib/backend/sidecar.ts}).
 * Plain {@code mvn spring-boot:run}, the test suite and the build-time AOT run
 * never set it, so the watchdog is a no-op there and never reads their stdin.
 * Mirrors the env-gated lifecycle convention of
 * {@link org.voxrox.mailbackend.core.init.AotTrainingExitListener}.
 *
 * <p>
 * Started from {@code main()} rather than as a Spring bean so it is armed
 * during the 2–5 s Spring startup window too, not only once the context is
 * ready.
 */
public final class ParentProcessWatchdog {

    static final String WATCH_PARENT_ENV = "MAIL_SIDECAR_WATCH_PARENT";
    private static final Logger log = LoggerFactory.getLogger(ParentProcessWatchdog.class);

    private ParentProcessWatchdog() {
    }

    /**
     * Starts the watchdog iff {@value #WATCH_PARENT_ENV} requests it. Safe to call
     * unconditionally from {@code main()}; a no-op outside the spawned sidecar.
     */
    public static void startIfEnabled() {
        if (!isEnabled(System.getenv(WATCH_PARENT_ENV))) {
            return;
        }
        start(System.in, () -> System.exit(0));
    }

    static boolean isEnabled(@Nullable String envValue) {
        return envValue != null && "1".equals(envValue.trim());
    }

    /** Spawns the daemon watcher thread. Package-private for tests. */
    static Thread start(InputStream parentPipe, Runnable onParentExit) {
        Thread thread = new Thread(() -> watchUntilParentExits(parentPipe, onParentExit), "parent-process-watchdog");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    /**
     * Blocks reading {@code parentPipe} until EOF or a broken pipe, then runs
     * {@code onParentExit}. Any bytes the parent might send are intentionally
     * discarded — only the end of the stream carries meaning. Package-private so
     * tests can drive it with a {@link java.io.PipedInputStream} and a fake exit.
     */
    static void watchUntilParentExits(InputStream parentPipe, Runnable onParentExit) {
        try {
            // read() returns -1 (EOF) once the parent closes its end of the pipe,
            // i.e. the parent process is gone. It does not normally write, so this
            // simply blocks for the lifetime of the parent.
            while (parentPipe.read() >= 0) {
                // Parent still alive; ignore the byte and keep waiting.
            }
        } catch (IOException e) {
            // A broken pipe on abrupt parent death is expected, not an error.
            log.debug("{} Sidecar stdin pipe broke while watching the parent process.", LogCategory.BOOT, e);
        }
        log.warn("{} Parent process is gone (stdin closed); shutting the sidecar down to avoid an orphaned backend.",
                LogCategory.BOOT);
        onParentExit.run();
    }
}
