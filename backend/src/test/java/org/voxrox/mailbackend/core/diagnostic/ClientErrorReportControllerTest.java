package org.voxrox.mailbackend.core.diagnostic;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration;
import org.springframework.boot.security.oauth2.client.autoconfigure.OAuth2ClientAutoConfiguration;
import org.springframework.boot.security.oauth2.client.autoconfigure.servlet.OAuth2ClientWebSecurityAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.voxrox.mailbackend.core.security.InternalApiKeyProvider;

@WebMvcTest(controllers = ClientErrorReportController.class, excludeAutoConfiguration = {
        SecurityAutoConfiguration.class, OAuth2ClientAutoConfiguration.class,
        OAuth2ClientWebSecurityAutoConfiguration.class})
@AutoConfigureMockMvc(addFilters = false)
class ClientErrorReportControllerTest {

    private static final Path DATA_DIR = Path.of("target", "test-tmp", "ClientErrorReportControllerTest")
            .toAbsolutePath().normalize();

    static {
        try {
            Files.createDirectories(DATA_DIR.resolve("logs"));
            System.setProperty("app.data-dir", DATA_DIR.toString());
            System.setProperty("logging.file.name", DATA_DIR.resolve("logs").resolve("mail.log").toString());
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @AfterAll
    static void clearSystemProperties() {
        System.clearProperty("app.data-dir");
        System.clearProperty("logging.file.name");
    }

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    ClientErrorReportService clientErrorReportService;

    @MockitoBean
    InternalApiKeyProvider apiKeyProvider;

    @Test
    @DisplayName("POST /api/internal/client-errors accepts the payload the client sends, extra fields included")
    void reportAcceptsTheClientPayload() throws Exception {
        String json = """
                {
                  "eventId": "1b4e28ba-2fa1-11d2-883f-0016d3cca427",
                  "occurredAt": "2026-10-12T10:00:00Z",
                  "kind": "window_error",
                  "message": "boom",
                  "name": "Error",
                  "stack": null,
                  "source": "tauri://localhost/_app/immutable/x.js",
                  "line": 12,
                  "column": 34,
                  "route": "/settings/about",
                  "userAgent": "Playwright",
                  "language": "cs",
                  "backend": { "appName": "mail", "appVersion": "0.1.0" },
                  "context": { "boundary": "contact-list" }
                }
                """;

        mockMvc.perform(post("/api/internal/client-errors").contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isAccepted());

        verify(clientErrorReportService).report(argThat(request -> "boom".equals(request.message())
                && Integer.valueOf(12).equals(request.line()) && "/settings/about".equals(request.route())
                && request.context() != null && "contact-list".equals(request.context().get("boundary"))));
    }
}
