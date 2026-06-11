package org.voxrox.mailbackend.core.system;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/v1/system")
@Tag(name = "System", description = "Local backend state for the desktop client")
public class SystemReadinessController {

    private final SystemReadinessService readinessService;

    public SystemReadinessController(SystemReadinessService readinessService) {
        this.readinessService = readinessService;
    }

    @GetMapping("/readiness")
    @Operation(summary = "Returns backend readiness state", description = "The desktop client uses this response at startup. Readiness means the API is ready to serve the UI; the first mail synchronization and accounts waiting for re-auth do not impact readiness.")
    public ResponseEntity<SystemReadinessResponse> getReadiness() {
        SystemReadinessResponse response = readinessService.getReadiness();
        return ResponseEntity.status(response.ready() ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE).body(response);
    }
}
