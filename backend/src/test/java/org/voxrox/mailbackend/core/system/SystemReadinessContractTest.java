package org.voxrox.mailbackend.core.system;

import static org.mockito.Mockito.verifyNoInteractions;
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
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration;
import org.springframework.boot.security.oauth2.client.autoconfigure.OAuth2ClientAutoConfiguration;
import org.springframework.boot.security.oauth2.client.autoconfigure.servlet.OAuth2ClientWebSecurityAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.voxrox.mailbackend.core.health.SyncHealthIndicator;
import org.voxrox.mailbackend.core.init.HandshakeResponse;
import org.voxrox.mailbackend.core.init.HandshakeService;
import org.voxrox.mailbackend.core.security.InternalApiKeyProvider;

@WebMvcTest(controllers = SystemReadinessController.class, excludeAutoConfiguration = {SecurityAutoConfiguration.class,
        OAuth2ClientAutoConfiguration.class, OAuth2ClientWebSecurityAutoConfiguration.class})
@AutoConfigureMockMvc(addFilters = false)
@Import(SystemReadinessService.class)
class SystemReadinessContractTest {

    private static final Path DATA_DIR = Path.of("target", "test-tmp", "SystemReadinessContractTest").toAbsolutePath()
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
    private HandshakeService handshakeService;

    @MockitoBean
    private SyncHealthIndicator syncHealthIndicator;

    @MockitoBean
    private InternalApiKeyProvider apiKeyProvider;

    @Test
    @DisplayName("Readiness stays READY even when an active account requires re-auth")
    void readinessStaysReadyWhenAccountRequiresReauth() throws Exception {
        givenHandshake();
        when(syncHealthIndicator.health())
                .thenReturn(Health.up().withDetail("activeAccounts", 1).withDetail("requiresReauth", 1)
                        .withDetail("note", "All active accounts are waiting for re-auth.").build());

        mockMvc.perform(get("/api/v1/system/readiness")).andExpect(status().isOk())
                .andExpect(jsonPath("$.ready").value(true)).andExpect(jsonPath("$.phase").value("READY"));

        verifyNoInteractions(syncHealthIndicator);
    }

    @Test
    @DisplayName("Readiness stays READY during startup grace, before the first sync completes")
    void readinessStaysReadyBeforeFirstSyncCompletes() throws Exception {
        givenHandshake();
        when(syncHealthIndicator.health()).thenReturn(Health.up().withDetail("activeAccounts", 1)
                .withDetail("neverSynced", 1).withDetail("recentlySynced", 0).build());

        mockMvc.perform(get("/api/v1/system/readiness")).andExpect(status().isOk())
                .andExpect(jsonPath("$.ready").value(true)).andExpect(jsonPath("$.phase").value("READY"));

        verifyNoInteractions(syncHealthIndicator);
    }

    private void givenHandshake() {
        when(handshakeService.getHandshake())
                .thenReturn(new HandshakeResponse("mail", "9.8.7-test", "1.0.0", "0.0.1", "1"));
    }
}
