package org.voxrox.mailbackend.core.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ParentProcessWatchdogTest {

    @Test
    @DisplayName("isEnabled is true only for the exact opt-in value")
    void isEnabledRecognisesOptInOnly() {
        assertThat(ParentProcessWatchdog.isEnabled("1")).isTrue();
        assertThat(ParentProcessWatchdog.isEnabled(" 1 ")).isTrue();

        assertThat(ParentProcessWatchdog.isEnabled(null)).isFalse();
        assertThat(ParentProcessWatchdog.isEnabled("")).isFalse();
        assertThat(ParentProcessWatchdog.isEnabled("0")).isFalse();
        assertThat(ParentProcessWatchdog.isEnabled("true")).isFalse();
        assertThat(ParentProcessWatchdog.isEnabled("stdin")).isFalse();
    }

    @Test
    @DisplayName("exits once the parent closes the pipe (EOF)")
    void exitsOnEof() {
        AtomicInteger exits = new AtomicInteger();

        ParentProcessWatchdog.watchUntilParentExits(new ByteArrayInputStream(new byte[0]), exits::incrementAndGet);

        assertThat(exits).hasValue(1);
    }

    @Test
    @DisplayName("ignores any bytes the parent sends and exits only on EOF")
    void ignoresBytesThenExitsOnEof() {
        AtomicInteger exits = new AtomicInteger();
        InputStream withBytes = new ByteArrayInputStream("noise".getBytes(StandardCharsets.UTF_8));

        ParentProcessWatchdog.watchUntilParentExits(withBytes, exits::incrementAndGet);

        assertThat(exits).hasValue(1);
    }

    @Test
    @DisplayName("treats a broken pipe (IOException) as the parent being gone")
    void exitsOnBrokenPipe() {
        AtomicInteger exits = new AtomicInteger();
        InputStream broken = new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("pipe broke on parent death");
            }
        };

        ParentProcessWatchdog.watchUntilParentExits(broken, exits::incrementAndGet);

        assertThat(exits).hasValue(1);
    }

    @Test
    @DisplayName("runs as a daemon thread that exits when the live pipe is closed")
    void daemonThreadExitsWhenPipeCloses() throws Exception {
        PipedOutputStream parentEnd = new PipedOutputStream();
        PipedInputStream sidecarEnd = new PipedInputStream(parentEnd);
        CountDownLatch exited = new CountDownLatch(1);

        Thread watcher = ParentProcessWatchdog.start(sidecarEnd, exited::countDown);
        assertThat(watcher.isDaemon()).isTrue();

        // Parent still alive: nothing has fired yet.
        assertThat(exited.await(100, TimeUnit.MILLISECONDS)).isFalse();

        // Parent dies -> OS closes the write end -> read() sees EOF.
        parentEnd.close();

        assertThat(exited.await(2, TimeUnit.SECONDS)).isTrue();
        watcher.join(TimeUnit.SECONDS.toMillis(2));
        assertThat(watcher.isAlive()).isFalse();
    }

    @Test
    @DisplayName("halts the JVM when the graceful exit hangs past the timeout")
    void haltsWhenGracefulExitHangs() throws Exception {
        CountDownLatch halted = new CountDownLatch(1);
        // A System.exit whose shutdown hooks never finish: blocks its caller forever.
        Runnable hangingExit = () -> {
            try {
                Thread.sleep(Long.MAX_VALUE);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };

        Thread caller = new Thread(
                () -> ParentProcessWatchdog.exitWithHaltFallback(hangingExit, halted::countDown, Duration.ofMillis(50)),
                "test-hanging-exit");
        caller.setDaemon(true);
        caller.start();

        assertThat(halted.await(2, TimeUnit.SECONDS)).isTrue();
        caller.interrupt(); // release the fake hung shutdown
    }

    @Test
    @DisplayName("does not halt before the timeout elapses")
    void doesNotHaltBeforeTimeout() throws Exception {
        AtomicInteger halts = new AtomicInteger();

        // The real exit terminates the process; returning normally here emulates
        // "shutdown still running" without blocking the test thread.
        Thread halter = ParentProcessWatchdog.exitWithHaltFallback(() -> {
        }, halts::incrementAndGet, Duration.ofSeconds(30));
        assertThat(halter.isDaemon()).isTrue();

        assertThat(halts).hasValue(0);

        // Interrupting the timer (test cleanup) must abort it without halting.
        halter.interrupt();
        halter.join(TimeUnit.SECONDS.toMillis(2));
        assertThat(halter.isAlive()).isFalse();
        assertThat(halts).hasValue(0);
    }
}
