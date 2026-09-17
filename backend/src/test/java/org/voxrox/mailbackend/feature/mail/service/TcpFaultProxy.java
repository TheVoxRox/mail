package org.voxrox.mailbackend.feature.mail.service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A TCP relay between the backend and a test mail server that fails the way a
 * network does, so the sync path meets the failure on the wire rather than
 * through a mocked exception.
 * <ul>
 * <li>{@link #dropConnections()} closes every relayed connection, which is what
 * a machine finds after sleep: the pooled connection looks alive and is
 * not.</li>
 * <li>{@link #refuse()} accepts a new connection and closes it at once, the
 * shape of a server that cannot be reached.</li>
 * <li>{@link #silence()} keeps the connections open and forwards nothing, the
 * shape of a network that went quiet; only a read timeout ends the wait.</li>
 * </ul>
 * The proxy listens on the loopback address and a port of its own, so the port
 * the account points at stays the same across every fault.
 */
final class TcpFaultProxy implements AutoCloseable {

    private enum Mode {
        PASS, REFUSE, SILENT
    }

    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int SILENCE_POLL_MS = 20;

    private final InetSocketAddress target;
    private final ServerSocket server;
    private final Set<Socket> open = ConcurrentHashMap.newKeySet();
    private final ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor();
    private final AtomicInteger accepted = new AtomicInteger();
    private volatile Mode mode = Mode.PASS;

    TcpFaultProxy(String targetHost, int targetPort) throws IOException {
        this.target = new InetSocketAddress(targetHost, targetPort);
        this.server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        threads.execute(this::acceptLoop);
    }

    int port() {
        return server.getLocalPort();
    }

    /** Connections the proxy has accepted so far, refused ones included. */
    int acceptedConnections() {
        return accepted.get();
    }

    void dropConnections() {
        for (Socket socket : open) {
            closeQuietly(socket);
        }
        open.clear();
    }

    void refuse() {
        mode = Mode.REFUSE;
        dropConnections();
    }

    void silence() {
        mode = Mode.SILENT;
    }

    void restore() {
        mode = Mode.PASS;
    }

    @Override
    public void close() throws IOException {
        mode = Mode.PASS;
        server.close();
        dropConnections();
        threads.shutdownNow();
    }

    private void acceptLoop() {
        while (!server.isClosed()) {
            Socket client;
            try {
                client = server.accept();
            } catch (IOException e) {
                return;
            }
            accepted.incrementAndGet();
            if (mode == Mode.REFUSE) {
                closeQuietly(client);
                continue;
            }
            Socket upstream = new Socket();
            try {
                upstream.connect(target, CONNECT_TIMEOUT_MS);
            } catch (IOException e) {
                closeQuietly(upstream);
                closeQuietly(client);
                continue;
            }
            open.add(client);
            open.add(upstream);
            threads.execute(() -> pump(client, upstream));
            threads.execute(() -> pump(upstream, client));
        }
    }

    private void pump(Socket from, Socket to) {
        byte[] buffer = new byte[8192];
        try {
            InputStream in = from.getInputStream();
            OutputStream out = to.getOutputStream();
            int read;
            while ((read = in.read(buffer)) != -1) {
                // Silence holds what arrived: the connection stays open and says nothing.
                while (mode == Mode.SILENT && !from.isClosed()) {
                    Thread.sleep(SILENCE_POLL_MS);
                }
                out.write(buffer, 0, read);
                out.flush();
            }
        } catch (IOException e) {
            // A dropped or refused connection ends here; the finally block tidies up.
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            closeQuietly(from);
            closeQuietly(to);
            open.remove(from);
            open.remove(to);
        }
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException e) {
            // Already closed from the other side.
        }
    }
}
