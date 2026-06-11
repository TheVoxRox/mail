package org.voxrox.mailbackend.core.diagnostic;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Hidden;

@Hidden
@RestController
public class ClientBootDiagnosticsController {

    private final ClientBootDiagnosticsService clientBootDiagnosticsService;

    public ClientBootDiagnosticsController(ClientBootDiagnosticsService clientBootDiagnosticsService) {
        this.clientBootDiagnosticsService = clientBootDiagnosticsService;
    }

    @PostMapping("/api/internal/client-boot")
    public ResponseEntity<Void> updateClientBoot(@RequestBody ClientBootDiagnosticsRequest request) {
        clientBootDiagnosticsService.update(request);
        return ResponseEntity.accepted().build();
    }
}
