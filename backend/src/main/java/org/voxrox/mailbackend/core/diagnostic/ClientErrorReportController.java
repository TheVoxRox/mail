package org.voxrox.mailbackend.core.diagnostic;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Hidden;

@Hidden
@RestController
public class ClientErrorReportController {

    private final ClientErrorReportService clientErrorReportService;

    public ClientErrorReportController(ClientErrorReportService clientErrorReportService) {
        this.clientErrorReportService = clientErrorReportService;
    }

    @PostMapping("/api/internal/client-errors")
    public ResponseEntity<Void> reportClientError(@RequestBody ClientErrorReportRequest request) {
        clientErrorReportService.report(request);
        return ResponseEntity.accepted().build();
    }
}
