package org.voxrox.mailbackend.feature.mail.service;

import static java.nio.charset.StandardCharsets.US_ASCII;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * An IMAP server written for the test: it speaks just enough of the protocol
 * for the backend to log in, list INBOX and open it, and it answers the open
 * with whatever lines the test hands it. Those are the responses no real server
 * sends and GreenMail cannot be made to — a message count or a UID range sized
 * to exhaust the client's heap (IMAP/SMTP audit B1-3).
 * <p>
 * It listens over TLS with {@link TestTls}'s certificate, because the backend
 * connects to nothing else (audit B1-4). The mailbox it describes is always
 * INBOX, empty unless the lines say otherwise; every command it does not know
 * gets a plain {@code OK}, which is enough for the handful the backend sends
 * around a sync.
 */
final class HostileImapServer implements AutoCloseable {

    private static final String CAPABILITIES = "IMAP4rev1";

    private final ServerSocket server;
    private final Set<Socket> open = ConcurrentHashMap.newKeySet();
    private final ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor();
    private volatile List<String> openResponse = List.of();

    HostileImapServer() throws IOException {
        this.server = TestTls.serverSocketFactory().createServerSocket(0, 50, InetAddress.getLoopbackAddress());
        threads.execute(this::acceptLoop);
    }

    int port() {
        return server.getLocalPort();
    }

    /**
     * Untagged lines every later EXAMINE or SELECT is answered with, after the ones
     * describing an empty INBOX — so a count in them is the one the client ends up
     * with.
     */
    void answerOpenWith(String... lines) {
        openResponse = List.of(lines);
    }

    @Override
    public void close() throws IOException {
        server.close();
        for (Socket socket : open) {
            closeQuietly(socket);
        }
        threads.shutdownNow();
    }

    private void acceptLoop() {
        while (!server.isClosed()) {
            try {
                Socket client = server.accept();
                open.add(client);
                threads.execute(() -> serve(client));
            } catch (IOException e) {
                // Closed by close(); anything else ends the test's server the same way.
                return;
            }
        }
    }

    private void serve(Socket client) {
        try (client; BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream(), US_ASCII))) {
            OutputStream out = client.getOutputStream();
            send(out, List.of("* OK [CAPABILITY " + CAPABILITIES + "] test server ready"));
            String line;
            while ((line = in.readLine()) != null) {
                String[] parts = line.split(" ", 3);
                String tag = parts[0];
                String command = parts.length > 1 ? parts[1].toUpperCase(Locale.ROOT) : "";
                String arguments = parts.length > 2 ? parts[2] : "";
                send(out, answer(tag, command, arguments));
                if (command.equals("LOGOUT")) {
                    return;
                }
            }
        } catch (IOException e) {
            // The client hung up, which is what a client refusing a response does.
        } finally {
            open.remove(client);
        }
    }

    private List<String> answer(String tag, String command, String arguments) {
        return switch (command) {
            case "CAPABILITY" -> List.of("* CAPABILITY " + CAPABILITIES, tag + " OK done");
            case "LOGIN" -> List.of(tag + " OK [CAPABILITY " + CAPABILITIES + "] logged in");
            case "LIST",
                    "LSUB" ->
                List.of(arguments.endsWith("\"\"")
                        ? "* " + command + " (\\Noselect) \"/\" \"\""
                        : "* " + command + " (\\HasNoChildren) \"/\" INBOX", tag + " OK done");
            case "STATUS" ->
                List.of("* STATUS INBOX (MESSAGES 0 RECENT 0 UIDNEXT 1 UIDVALIDITY 1 UNSEEN 0)", tag + " OK done");
            case "EXAMINE", "SELECT" -> {
                List<String> lines = new ArrayList<>(List.of("* FLAGS (\\Seen \\Flagged \\Answered)", "* 0 EXISTS",
                        "* 0 RECENT", "* OK [UIDVALIDITY 1] UIDs valid", "* OK [UIDNEXT 1] predicted"));
                lines.addAll(openResponse);
                lines.add(tag + " OK [" + (command.equals("EXAMINE") ? "READ-ONLY" : "READ-WRITE") + "] done");
                yield lines;
            }
            case "LOGOUT" -> List.of("* BYE logging out", tag + " OK done");
            default -> List.of(tag + " OK done");
        };
    }

    private static void send(OutputStream out, List<String> lines) throws IOException {
        StringBuilder text = new StringBuilder();
        for (String line : lines) {
            text.append(line).append("\r\n");
        }
        out.write(text.toString().getBytes(US_ASCII));
        out.flush();
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException e) {
            // Already closed, which is the point.
        }
    }
}
