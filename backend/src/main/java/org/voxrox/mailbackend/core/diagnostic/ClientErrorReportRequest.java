package org.voxrox.mailbackend.core.diagnostic;

import java.util.Map;

import org.jspecify.annotations.Nullable;

/**
 * An uncaught error the frontend reports
 * ({@code frontend/src/lib/api/clientErrors.ts}). The client sends more than
 * this (its user agent, language and the backend's versions); those are in
 * {@code client-boot.json} and the bundle's own summary already, so they are
 * not read here. Any field may be missing.
 */
public record ClientErrorReportRequest(@Nullable String eventId, @Nullable String occurredAt, @Nullable String kind,
        @Nullable String message, @Nullable String name, @Nullable String stack, @Nullable String source,
        @Nullable Integer line, @Nullable Integer column, @Nullable String route,
        @Nullable Map<String, String> context) {
}
