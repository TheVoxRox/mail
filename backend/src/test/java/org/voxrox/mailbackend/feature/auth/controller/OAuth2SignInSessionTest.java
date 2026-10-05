package org.voxrox.mailbackend.feature.auth.controller;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.web.util.UriComponentsBuilder;
import org.voxrox.mailbackend.core.init.StorageContextInitializer;
import org.voxrox.mailbackend.core.security.InternalApiKeyProvider;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;

/**
 * Audit B2-1: the session an OAuth sign-in leaves in the system browser must
 * not stand in for the {@code X-API-KEY}.
 *
 * <p>
 * Drives the real filter chain through the three requests of a Google sign-in —
 * {@code /oauth2/authorization/google}, {@code /login/oauth2/code/google} and
 * {@code /api/v1/auth/oauth2/success} — with the provider's token and user-info
 * endpoints faked by WireMock ({@code openid} is dropped from the requested
 * scopes, so no id_token has to be signed). The API is then called with the
 * session the flow left and no key.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "mail.test-context=OAuth2SignInSessionTest", "mail.client.sync.initial-delay=PT1H"})
@AutoConfigureMockMvc
@ContextConfiguration(initializers = StorageContextInitializer.class)
class OAuth2SignInSessionTest {

    private static final Path DATA_DIR = Path.of("target", "test-tmp", "OAuth2SignInSessionTest").toAbsolutePath()
            .normalize();

    private static final String PROTECTED_PATH = "/api/v1/accounts";

    private static final WireMockServer PROVIDER = new WireMockServer(WireMockConfiguration.options().dynamicPort());

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
        PROVIDER.start();
        PROVIDER.stubFor(post(urlPathEqualTo("/token"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody("""
                        {"access_token":"probe-access-token","token_type":"Bearer","expires_in":3600,
                         "refresh_token":"probe-refresh-token","scope":"https://mail.google.com/ email profile"}""")));
        PROVIDER.stubFor(get(urlPathEqualTo("/userinfo"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody("""
                        {"sub":"probe-subject","email":"probe@gmail.com","name":"Probe"}""")));
    }

    @DynamicPropertySource
    static void fakeProvider(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.client.provider.google.token-uri", () -> PROVIDER.baseUrl() + "/token");
        registry.add("spring.security.oauth2.client.provider.google.user-info-uri",
                () -> PROVIDER.baseUrl() + "/userinfo");
        registry.add("spring.security.oauth2.client.registration.google.scope",
                () -> "https://mail.google.com/,email,profile");
    }

    @AfterAll
    static void tearDown() {
        PROVIDER.stop();
        System.clearProperty("app.data-dir");
        System.clearProperty("logging.file.name");
        System.clearProperty("spring.security.oauth2.client.registration.google.client-id");
        System.clearProperty("spring.security.oauth2.client.registration.google.client-secret");
        System.clearProperty("spring.security.oauth2.client.registration.microsoft.client-id");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private InternalApiKeyProvider apiKeyProvider;

    @Test
    @DisplayName("the session a completed callback leaves does not authorize the API without the key")
    void signedInSessionIsRefusedByTheApi() throws Exception {
        MockHttpSession session = completeCallback();

        // The window between the callback and /success: the session holds the
        // sign-in's OAuth2AuthenticationToken, and the API must still refuse it.
        MvcResult result = mockMvc.perform(
                MockMvcRequestBuilders.get(PROTECTED_PATH).session(session).header("Origin", "http://localhost:5173"))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    @DisplayName("/success ends the session the sign-in ran in")
    void successEndsTheSession() throws Exception {
        MockHttpSession session = completeCallback();

        MvcResult result = mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/auth/oauth2/success").session(session))
                .andReturn();

        assertThat(result.getResponse().getRedirectedUrl()).isEqualTo("/auth-finished.html");
        assertThat(session.isInvalid()).isTrue();
    }

    @Test
    @DisplayName("the key still authorizes the API")
    void keyAuthorizesTheApi() throws Exception {
        MvcResult result = mockMvc
                .perform(MockMvcRequestBuilders.get(PROTECTED_PATH).header("X-API-KEY", apiKeyProvider.getKey()))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
    }

    /**
     * Runs the first two requests of a sign-in — the authorization redirect and the
     * provider's callback — and returns the session they ran in.
     */
    private MockHttpSession completeCallback() throws Exception {
        MockHttpSession session = new MockHttpSession();
        MvcResult authorization = mockMvc
                .perform(MockMvcRequestBuilders.get("/oauth2/authorization/google").session(session)).andReturn();
        // The redirect carries the state URL-encoded (its Base64 padding is %3D).
        String state = UriComponentsBuilder.fromUri(URI.create(authorization.getResponse().getRedirectedUrl())).build()
                .getQueryParams().getFirst("state");
        state = state == null ? null : URLDecoder.decode(state, StandardCharsets.UTF_8);
        assertThat(state).as("authorization redirect carries a state").isNotBlank();

        MvcResult callback = mockMvc.perform(MockMvcRequestBuilders.get("/login/oauth2/code/google")
                .param("code", "probe-code").param("state", state).session(session)).andReturn();
        assertThat(callback.getResponse().getRedirectedUrl()).as("the callback completed the sign-in")
                .isEqualTo("/api/v1/auth/oauth2/success");
        return session;
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
