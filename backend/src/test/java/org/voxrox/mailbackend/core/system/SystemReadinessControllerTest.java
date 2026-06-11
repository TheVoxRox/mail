package org.voxrox.mailbackend.core.system;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.voxrox.mailbackend.core.security.InternalApiKeyProvider;

@WebMvcTest(controllers = SystemReadinessController.class, excludeAutoConfiguration = {SecurityAutoConfiguration.class,
        OAuth2ClientAutoConfiguration.class, OAuth2ClientWebSecurityAutoConfiguration.class})
@AutoConfigureMockMvc(addFilters = false)
class SystemReadinessControllerTest {

    private static final Path DATA_DIR = Path.of("target", "test-tmp", "SystemReadinessControllerTest").toAbsolutePath()
            .normalize();

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
    private MockMvc mockMvc;

    @MockitoBean
    private SystemReadinessService readinessService;

    @MockitoBean
    private InternalApiKeyProvider apiKeyProvider;

    @Test
    @DisplayName("GET /api/v1/system/readiness returns the ready backend contract")
    void readinessReturnsReadyContract() throws Exception {
        when(readinessService.getReadiness()).thenReturn(new SystemReadinessResponse(true, SystemReadinessPhase.READY,
                "mail", "9.8.7-test", "1.0.0", "0.0.1", "1", null));

        mockMvc.perform(get("/api/v1/system/readiness")).andExpect(status().isOk())
                .andExpect(jsonPath("$.ready").value(true)).andExpect(jsonPath("$.phase").value("READY"))
                .andExpect(jsonPath("$.appName").value("mail")).andExpect(jsonPath("$.appVersion").value("9.8.7-test"))
                .andExpect(jsonPath("$.apiVersion").value("1.0.0"))
                .andExpect(jsonPath("$.minClientVersion").value("0.0.1"))
                .andExpect(jsonPath("$.dbSchemaVersion").value("1")).andExpect(jsonPath("$.reason").isEmpty());
    }

    @Test
    @DisplayName("Readiness returns 503 when the backend is not ready yet")
    void readinessReturnsUnavailableWhenNotReady() throws Exception {
        when(readinessService.getReadiness()).thenReturn(new SystemReadinessResponse(false, SystemReadinessPhase.READY,
                "mail", "9.8.7-test", "1.0.0", "0.0.1", "1", "Database is initializing."));

        mockMvc.perform(get("/api/v1/system/readiness")).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.ready").value(false))
                .andExpect(jsonPath("$.reason").value("Database is initializing."));
    }
}
