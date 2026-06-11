package org.voxrox.mailbackend.core.init;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicBoolean;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.voxrox.mailbackend.MailBackendApplication;
import org.voxrox.mailbackend.core.backup.DatabaseBackupService;
import org.voxrox.mailbackend.core.config.ApplicationVersion;
import org.voxrox.mailbackend.core.service.FileSystemService;
import org.voxrox.mailbackend.util.AuditLog;
import org.voxrox.mailbackend.util.LogCategory;

import tools.jackson.databind.ObjectMapper;

@Component
public class HandshakeService {

    private static final Logger log = LoggerFactory.getLogger(HandshakeService.class);
    private static final int API_KEY_BYTES = 32;
    public static final String API_VERSION = "1.0.0";
    public static final String MIN_CLIENT_VERSION = "0.0.1";

    /**
     * SecureRandom is thread-safe (contended internally) and meant to be reused —
     * each {@code nextBytes} call delivers fresh entropy without re-seeding. Cached
     * as a class-level field so repeat callers do not pay the (small) construction
     * cost and so SpotBugs' DMI_RANDOM_USED_ONLY_ONCE pattern is not triggered. The
     * default provider on Windows is CryptGenRandom-backed, which never blocks on
     * initialization.
     */
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Environment environment;
    private final FileSystemService fileSystemService;
    private final ObjectMapper objectMapper;
    private final Flyway flyway;
    private final DatabaseBackupService databaseBackupService;
    private final StartupTimingService startupTimingService;
    private final String appDataDir;
    private final String appVersion;
    private final AtomicBoolean initialized = new AtomicBoolean(false);

    /**
     * Generated once per JVM process. Sidecar restarts produce a new key — the
     * frontend re-reads {@code session.json} on every sidecar start anyway (the
     * loopback port changes too), so persisting this across restarts would add no
     * value and only enlarge the attack surface.
     */
    private volatile String apiKey;

    public HandshakeService(Environment environment, FileSystemService fileSystemService, ObjectMapper objectMapper,
            Flyway flyway, DatabaseBackupService databaseBackupService, ApplicationVersion applicationVersion,
            StartupTimingService startupTimingService, @Value("${app.data-dir}") String appDataDir) {
        this.environment = environment;
        this.fileSystemService = fileSystemService;
        this.objectMapper = objectMapper;
        this.flyway = flyway;
        this.databaseBackupService = databaseBackupService;
        this.startupTimingService = startupTimingService;
        this.appDataDir = appDataDir;
        this.appVersion = applicationVersion.value();
    }

    /**
     * Shape of session.json — contract with the parent launcher (Electron / desktop
     * app), which reads this file at startup to know where to call the backend.
     */
    private record SessionPayload(String appName, String appVersion, String apiVersion, String minClientVersion,
            String dbSchemaVersion, int port, String apiKey, String baseUrl) {
    }

    public HandshakeResponse getHandshake() {
        return new HandshakeResponse(MailBackendApplication.APP_NAME, appVersion, API_VERSION, MIN_CLIENT_VERSION,
                resolveDbSchemaVersion());
    }

    public synchronized String getOrCreateApiKey() {
        if (apiKey == null) {
            apiKey = generateApiKey();
            log.info("{} Generated new in-memory handshake API key for this sidecar process.", LogCategory.SECURITY);
        }
        return apiKey;
    }

    private static String generateApiKey() {
        byte[] bytes = new byte[API_KEY_BYTES];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    @EventListener(ApplicationReadyEvent.class)
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public void onApplicationReady() {
        long applicationReadyStarted = startupTimingService.start();
        if (!initialized.compareAndSet(false, true)) {
            return;
        }

        int port = resolveRuntimePort();
        String apiKey = getOrCreateApiKey();
        String appName = MailBackendApplication.APP_NAME;
        String dbSchemaVersion = resolveDbSchemaVersion();

        Path configPath = Paths.get(appDataDir);
        Path sessionFile = configPath.resolve("session.json");
        Path readyFile = configPath.resolve(".ready");

        try {
            long sessionWriteStarted = startupTimingService.start();
            /*
             * The directory is guaranteed to be created by StartupInitializer
             * (ApplicationRunner). Here we just write session.json. Jackson handles proper
             * escaping of all values — important for apiKey in particular, which is base64
             * and could theoretically contain characters needing JSON escape if generation
             * changed.
             */
            SessionPayload payload = new SessionPayload(appName, appVersion, API_VERSION, MIN_CLIENT_VERSION,
                    dbSchemaVersion, port, apiKey, "http://127.0.0.1:" + port + "/api");
            byte[] json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(payload);

            fileSystemService.writeAtomic(sessionFile, json);
            fileSystemService.applyPrivatePermissions(sessionFile);

            fileSystemService.writeAtomic(readyFile, "ready\n".getBytes(StandardCharsets.UTF_8));
            fileSystemService.applyPrivatePermissions(readyFile);
            startupTimingService.record("handshake.session-write", sessionWriteStarted);

            log.info("{} Session data written to {}. Ready signal is in place.", LogCategory.SECURITY, sessionFile);

        } catch (Exception e) {
            log.error("{} Critical error while writing session.json", LogCategory.SECURITY, e);
        }

        String previousVersion = databaseBackupService != null ? databaseBackupService.previousAppVersion() : null;
        AuditLog.success("app_started", "system", "appVersion=" + appVersion + " dbSchemaVersion=" + dbSchemaVersion
                + " previousAppVersion=" + (previousVersion != null ? previousVersion : "<none>"));
        startupTimingService.record("spring.application-ready", applicationReadyStarted);
    }

    private String resolveDbSchemaVersion() {
        if (flyway == null) {
            return "<unknown>";
        }
        MigrationInfo current = flyway.info().current();
        if (current == null) {
            return "<none>";
        }
        MigrationVersion version = current.getVersion();
        return version != null ? version.getVersion() : "<none>";
    }

    private int resolveRuntimePort() {
        String rawPort = environment.getProperty("local.server.port", "0");
        int port = Integer.parseInt(rawPort);
        if (port <= 0) {
            throw new IllegalStateException("Actual backend runtime port was not available for session.json.");
        }
        return port;
    }

}
