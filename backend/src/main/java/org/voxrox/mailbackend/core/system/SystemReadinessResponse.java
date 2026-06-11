package org.voxrox.mailbackend.core.system;

import io.swagger.v3.oas.annotations.media.Schema;

public record SystemReadinessResponse(
        @Schema(description = "True if the backend can serve user-facing API requests.", example = "true") boolean ready,
        @Schema(description = "Current phase of the readiness state machine.", example = "READY") SystemReadinessPhase phase,
        @Schema(description = "Backend application name.", example = "mail") String appName,
        @Schema(description = "Backend application version.", example = "0.1.0") String appVersion,
        @Schema(description = "API contract version.", example = "1.0.0") String apiVersion,
        @Schema(description = "Minimum supported frontend version.", example = "0.0.1") String minClientVersion,
        @Schema(description = "Current Flyway DB schema version.", example = "1") String dbSchemaVersion,
        @Schema(description = "Safe user-facing reason when ready=false.", nullable = true) String reason) {
}
