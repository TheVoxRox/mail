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

@WebMvcTest(controllers = ClientBootDiagnosticsController.class, excludeAutoConfiguration = {
        SecurityAutoConfiguration.class, OAuth2ClientAutoConfiguration.class,
        OAuth2ClientWebSecurityAutoConfiguration.class})
@AutoConfigureMockMvc(addFilters = false)
class ClientBootDiagnosticsControllerTest {

    private static final Path DATA_DIR = Path.of("target", "test-tmp", "ClientBootDiagnosticsControllerTest")
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
    ClientBootDiagnosticsService clientBootDiagnosticsService;

    @MockitoBean
    InternalApiKeyProvider apiKeyProvider;

    @Test
    @DisplayName("POST /api/internal/client-boot accepts a boot timing payload")
    void updateClientBootAcceptsPayload() throws Exception {
        String json = """
                {
                  "reportedAt": "2026-05-07T18:00:00Z",
                  "phase": "ready",
                  "slowLevel": "fast",
                  "timings": { "uiStart": 0, "appReady": 1234 },
                  "userAgent": "Playwright",
                  "language": "cs",
                  "route": "/mail/1/INBOX"
                }
                """;

        mockMvc.perform(post("/api/internal/client-boot").contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isAccepted());

        verify(clientBootDiagnosticsService).update(argThat(request -> "ready".equals(request.phase())
                && request.timings().get("appReady") == 1234L && "/mail/1/INBOX".equals(request.route())));
    }
}
