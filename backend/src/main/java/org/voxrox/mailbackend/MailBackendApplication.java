package org.voxrox.mailbackend;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.voxrox.mailbackend.core.init.StorageContextInitializer;

@SpringBootApplication
public class MailBackendApplication {

    public static final String APP_NAME = "mail";
    static final int DEFAULT_PORT = 0;
    static final int EXIT_CONFIG = 78;

    public static void main(String[] args) {
        int port;
        try {
            port = resolveConfiguredPort(args);
        } catch (IllegalArgumentException e) {
            System.err.println("Mail backend cannot start: " + e.getMessage());
            System.exit(EXIT_CONFIG);
            return;
        }

        if (!isPortAvailable(port)) {
            System.err.println("Mail backend cannot start: port " + port
                    + " on 127.0.0.1 is already in use. Another instance of the application is likely running.");
            System.exit(EXIT_CONFIG);
        }

        SpringApplication app = new SpringApplication(MailBackendApplication.class);
        app.addInitializers(new StorageContextInitializer());
        try {
            app.run(args);
        } catch (SpringApplication.AbandonedRunException e) {
            // Build-time AOT processing (spring-boot:process-aot) invokes main() and
            // aborts the run with this exception on purpose — it must propagate.
            throw e;
        } catch (Throwable t) {
            /*
             * A failed context refresh leaves non-daemon threads behind, so the JVM would
             * keep running after "Application run failed" — the orphan sidecar then holds
             * the data dir and the next start dies with EXIT_CONFIG ("already running").
             * Spring has already logged the failure; force the process down.
             */
            System.exit(1);
        }
    }

    static int resolveConfiguredPort(String[] args) {
        String systemPort = System.getProperty("server.port");
        if (systemPort != null && !systemPort.isBlank()) {
            return parseConfiguredPort(systemPort, "system property server.port");
        }

        for (String arg : args) {
            if (arg.startsWith("--server.port=")) {
                return parseConfiguredPort(arg.substring("--server.port=".length()), "argument --server.port");
            }
        }
        return DEFAULT_PORT;
    }

    private static int parseConfiguredPort(String rawValue, String source) {
        try {
            int port = Integer.parseInt(rawValue);
            if (port < 0 || port > 65535) {
                throw new IllegalArgumentException(source + " must be in range 0-65535, got: " + rawValue);
            }
            return port;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(source + " must be an integer, got: " + rawValue, e);
        }
    }

    static boolean isPortAvailable(int port) {
        if (port <= 0) {
            return true;
        }
        try (ServerSocket socket = new ServerSocket()) {
            socket.setReuseAddress(false);
            socket.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 1);
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
