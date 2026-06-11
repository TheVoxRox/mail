package org.voxrox.mailbackend.core.diagnostic;

import java.util.Map;

public record ClientBootDiagnosticsRequest(String reportedAt, String phase, String slowLevel, Map<String, Long> timings,
        String userAgent, String language, String route) {
}
