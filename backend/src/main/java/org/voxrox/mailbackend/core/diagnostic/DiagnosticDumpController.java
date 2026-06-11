package org.voxrox.mailbackend.core.diagnostic;

import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import module java.base;

@RestController
public class DiagnosticDumpController {

    private static final MediaType APPLICATION_ZIP = MediaType.parseMediaType("application/zip");

    private final DiagnosticDumpService diagnosticDumpService;

    public DiagnosticDumpController(DiagnosticDumpService diagnosticDumpService) {
        this.diagnosticDumpService = diagnosticDumpService;
    }

    @GetMapping(value = "/api/internal/diagnostic-dump", produces = "application/zip")
    public ResponseEntity<byte[]> getDiagnosticDump() {
        byte[] dump = diagnosticDumpService.createDump();
        String filename = "mail-diagnostic-"
                + DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC).format(Instant.now())
                + ".zip";

        return ResponseEntity.ok().contentType(APPLICATION_ZIP).cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(filename).build().toString())
                .body(dump);
    }
}
