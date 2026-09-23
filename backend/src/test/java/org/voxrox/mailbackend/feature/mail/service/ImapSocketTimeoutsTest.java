package org.voxrox.mailbackend.feature.mail.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.eclipse.angus.mail.util.PropUtil;
import org.eclipse.angus.mail.util.SocketFetcher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.voxrox.mailbackend.core.config.mail.ImapProperties;

/**
 * The socket bounds an IMAP session carries, and the write one of them end to
 * end — IMAP/SMTP audit B1-6. The write timeout is worth proving over a real
 * socket rather than by reading the property back: it only works if Angus finds
 * the key it looks for, and a property it does not recognize leaves the
 * connection exactly as unbounded as it was, with nothing to say so.
 */
class ImapSocketTimeoutsTest {

    private static final int MEGABYTE = 1024 * 1024;

    @Test
    @DisplayName("Angus reads every bound, and the scheduler, back out of the session properties")
    void everyBoundReachesAngus() {
        Properties props = new Properties();

        ImapSocketTimeouts.configure(props, "imaps", imapProperties(Duration.ofSeconds(7)));

        assertThat(PropUtil.getIntProperty(props, "mail.imaps.timeout", -1)).isEqualTo(60_000);
        assertThat(PropUtil.getIntProperty(props, "mail.imaps.connectiontimeout", -1)).isEqualTo(30_000);
        assertThat(PropUtil.getIntProperty(props, "mail.imaps.writetimeout", -1)).isEqualTo(7_000);
        assertThat(PropUtil.getScheduledExecutorServiceProperty(props, "mail.imaps.executor.writetimeout"))
                .isSameAs(MailWriteTimeoutScheduler.shared());
    }

    /**
     * A server that accepts the connection and then reads nothing from it. The
     * client's writes fill the send buffer, then the peer's receive window, and the
     * next one blocks — the shape of B1-6, where the blocked thread also holds its
     * account's IMAP lane. The budget here is a second, and the wait for the
     * failure is far longer than that: without the timeout the write never returns
     * and this is the line that ends the test.
     */
    @Test
    @DisplayName("A write to a server that stops reading fails on the timeout instead of blocking for good")
    void aWriteToASilentServerFails() throws Exception {
        Properties props = new Properties();
        ImapSocketTimeouts.configure(props, "imap", imapProperties(Duration.ofSeconds(1)));

        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            ExecutorService writer = Executors.newVirtualThreadPerTaskExecutor();
            try (Socket socket = SocketFetcher.getSocket("127.0.0.1", server.getLocalPort(), props, "mail.imap", false);
                    Socket accepted = server.accept()) {
                Future<Throwable> write = writer.submit(() -> writeUntilItFails(socket));

                assertThat(write.get(30, TimeUnit.SECONDS)).isInstanceOf(IOException.class);
            } finally {
                writer.shutdownNow();
            }
        }
    }

    /**
     * Writes until the socket refuses, in megabyte blocks and far past anything the
     * two ends buffer, and reports what stopped it.
     */
    private static Throwable writeUntilItFails(Socket socket) {
        byte[] block = new byte[MEGABYTE];
        try {
            OutputStream out = socket.getOutputStream();
            for (int written = 0; written < 256; written++) {
                out.write(block);
                out.flush();
            }
            return new AssertionError("The socket took 256 MB without the peer reading any of it");
        } catch (IOException | RuntimeException e) {
            return e;
        }
    }

    private static ImapProperties imapProperties(Duration writeTimeout) {
        return new ImapProperties(993, Duration.ofSeconds(30), Duration.ofSeconds(60), writeTimeout, "imaps", "imap",
                Duration.ofSeconds(1), Duration.ofMinutes(5));
    }
}
