package org.voxrox.mailbackend.core.diagnostic;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

import module java.base;

@Service
public class ClientBootDiagnosticsService {

    private static final int MAX_TEXT_LENGTH = 512;
    private static final long MAX_TIMING_MS = 10 * 60 * 1000L;
    private static final Set<String> ALLOWED_TIMINGS = Set.of("uiStart", "sidecarSpawnRequested", "sidecarRunning",
            "sessionFound", "handshakeOk", "readinessOk", "clientConfigOk", "accountsLoaded", "appReady");

    private final AtomicReference<ClientBootDiagnosticsSnapshot> latest = new AtomicReference<>();

    public void update(@Nullable ClientBootDiagnosticsRequest request) {
        if (request == null) {
            return;
        }
        latest.set(new ClientBootDiagnosticsSnapshot(parseInstant(request.reportedAt()), sanitizeText(request.phase()),
                sanitizeText(request.slowLevel()), sanitizeTimings(request.timings()),
                sanitizeText(request.userAgent()), sanitizeText(request.language()), sanitizeRoute(request.route())));
    }

    /** {@code null} until the client reports its first boot diagnostics. */
    public @Nullable ClientBootDiagnosticsSnapshot latest() {
        return latest.get();
    }

    private String parseInstant(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return now();
        }
        try {
            return Instant.parse(value).toString();
        } catch (DateTimeParseException ignored) {
            return now();
        }
    }

    private Map<String, Long> sanitizeTimings(@Nullable Map<String, Long> timings) {
        Map<String, Long> result = new LinkedHashMap<>();
        if (timings == null) {
            return result;
        }
        for (String key : ALLOWED_TIMINGS) {
            Long value = timings.get(key);
            if (value != null && value >= 0 && value <= MAX_TIMING_MS) {
                result.put(key, value);
            }
        }
        return result;
    }

    private @Nullable String sanitizeText(@Nullable String value) {
        if (value == null) {
            return null;
        }
        String stripped = value.replaceAll("[\\r\\n\\t]", " ").trim();
        if (stripped.isEmpty()) {
            return null;
        }
        return stripped.length() > MAX_TEXT_LENGTH ? stripped.substring(0, MAX_TEXT_LENGTH) : stripped;
    }

    private @Nullable String sanitizeRoute(@Nullable String value) {
        String route = sanitizeText(value);
        if (route == null) {
            return null;
        }
        int queryIndex = route.indexOf('?');
        int hashIndex = route.indexOf('#');
        int cutIndex = queryIndex >= 0 ? queryIndex : hashIndex;
        if (hashIndex >= 0 && (cutIndex < 0 || hashIndex < cutIndex)) {
            cutIndex = hashIndex;
        }
        return cutIndex >= 0 ? route.substring(0, cutIndex) : route;
    }

    private String now() {
        return Instant.now().toString();
    }

    public record ClientBootDiagnosticsSnapshot(String reportedAt, @Nullable String phase, @Nullable String slowLevel,
            Map<String, Long> timings, @Nullable String userAgent, @Nullable String language, @Nullable String route) {
    }
}
