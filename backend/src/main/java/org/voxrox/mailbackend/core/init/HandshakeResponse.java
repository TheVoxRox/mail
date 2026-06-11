package org.voxrox.mailbackend.core.init;

/**
 * Internal DTO assembled by {@link HandshakeService} during sidecar startup.
 * Carries the metadata that is written to {@code session.json} and consumed by
 * {@link org.voxrox.mailbackend.core.system.SystemReadinessService}. Not
 * exposed via REST (the public surface is {@code /api/v1/system/readiness}).
 */
public record HandshakeResponse(String appName, String appVersion, String apiVersion, String minClientVersion,
        String dbSchemaVersion) {
}
