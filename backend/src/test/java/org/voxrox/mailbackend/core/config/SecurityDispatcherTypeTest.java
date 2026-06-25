package org.voxrox.mailbackend.core.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import jakarta.servlet.DispatcherType;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.voxrox.mailbackend.core.init.StorageContextInitializer;

/**
 * Verifies that the security filter chain authorizes the external REQUEST
 * dispatch but PERMITS the server-internal ASYNC/ERROR dispatches.
 *
 * <p>
 * Without permitting ASYNC, an SSE emitter
 * ({@code /api/v1/notifications/stream}) timing out after 30 min triggers an
 * async completion dispatch that the {@code AuthorizationFilter} re-runs and
 * denies (the X-API-KEY is not re-applied); because the SSE response is already
 * committed, Spring logs a noisy ERROR every 30 min. Permitting the internal
 * dispatch — which cannot be triggered from outside the container — removes the
 * error. See {@code SecurityConfig#filterChain}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ContextConfiguration(initializers = StorageContextInitializer.class)
class SecurityDispatcherTypeTest {

    private static final Path DATA_DIR = Path.of("target", "test-tmp", "SecurityDispatcherTypeTest").toAbsolutePath()
            .normalize();

    /**
     * A protected endpoint (not in PUBLIC_ENDPOINTS): an unauthenticated REQUEST is
     * redirected to login.
     */
    private static final String PROTECTED_PATH = "/api/v1/accounts";

    static {
        try {
            deleteRecursively(DATA_DIR);
            Files.createDirectories(DATA_DIR.resolve("logs"));
            System.setProperty("app.data-dir", DATA_DIR.toString());
            System.setProperty("logging.file.name", DATA_DIR.resolve("logs").resolve("mail.log").toString());
            System.setProperty("spring.security.oauth2.client.registration.google.client-id", "dummy-client-id");
            System.setProperty("spring.security.oauth2.client.registration.google.client-secret",
                    "dummy-client-secret");
            System.setProperty("spring.security.oauth2.client.registration.microsoft.client-id", "dummy-client-id");
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @AfterAll
    static void clearSystemProperties() {
        System.clearProperty("app.data-dir");
        System.clearProperty("logging.file.name");
        System.clearProperty("spring.security.oauth2.client.registration.google.client-id");
        System.clearProperty("spring.security.oauth2.client.registration.google.client-secret");
        System.clearProperty("spring.security.oauth2.client.registration.microsoft.client-id");
    }

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("unauthenticated REQUEST dispatch to a protected endpoint is denied (redirect to login)")
    void requestDispatchIsDenied() throws Exception {
        MvcResult result = mockMvc.perform(get(PROTECTED_PATH)).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(302);
    }

    @Test
    @DisplayName("internal ASYNC dispatch is permitted (SSE timeout completion is not re-denied)")
    void asyncDispatchIsPermitted() throws Exception {
        MvcResult result = mockMvc.perform(get(PROTECTED_PATH).with(request -> {
            request.setDispatcherType(DispatcherType.ASYNC);
            return request;
        })).andReturn();

        // Permitted by security → not the 302 login redirect a denied REQUEST gets.
        assertThat(result.getResponse().getStatus()).isNotEqualTo(302);
    }

    private static void deleteRecursively(Path path) throws Exception {
        if (Files.notExists(path)) {
            return;
        }
        try (var stream = Files.walk(path)) {
            stream.sorted(Comparator.reverseOrder()).forEach(item -> {
                try {
                    Files.deleteIfExists(item);
                } catch (Exception e) {
                    throw new IllegalStateException("Failed to delete test path " + item, e);
                }
            });
        }
    }
}
