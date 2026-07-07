package org.voxrox.mailbackend.core.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;

/**
 * Verifies the behavior of {@code oauth2FailureHandler}:
 *
 * <ul>
 * <li>Redirects to the custom {@code /auth-failed.html} (instead of the Spring
 * default {@code /login?error} with the misleading "Invalid credentials"
 * text).</li>
 * <li>Propagates the OAuth2 error code as a {@code ?reason=…} query parameter —
 * the frontend page shows it and the server logs it in parallel.</li>
 * <li>For non-OAuth2 exceptions (defensive case) uses the fallback code
 * {@code unknown} instead of a stack trace or empty redirect.</li>
 * </ul>
 *
 * <p>
 * Also verifies that {@code pkceAuthorizationRequestResolver} adds PKCE to the
 * authorization request even though the clients are confidential (Spring
 * Security would otherwise send no {@code code_challenge}).
 *
 * <p>
 * No Spring context — both beans are exercised directly with mock inputs.
 */
class SecurityConfigTest {

    private final OAuth2CompletedStateTracker completedStates = new OAuth2CompletedStateTracker();
    private final SecurityConfig config = new SecurityConfig(null, completedStates);
    private final AuthenticationFailureHandler handler = config.oauth2FailureHandler();

    @Test
    @DisplayName("OAuth2 error -> redirect to auth-failed.html with error code in query")
    void oauth2ExceptionRedirectsWithErrorCode() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        OAuth2AuthenticationException exception = new OAuth2AuthenticationException(
                new OAuth2Error("invalid_grant", "Authorization code expired", null));

        handler.onAuthenticationFailure(request, response, exception);

        assertThat(response.getStatus()).isEqualTo(302);
        assertThat(response.getRedirectedUrl()).isEqualTo("/auth-failed.html?reason=invalid_grant");
    }

    @Test
    @DisplayName("Error code with URL-unsafe characters is properly encoded into the query")
    void oauth2ExceptionEncodesUnsafeReasonChars() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        OAuth2AuthenticationException exception = new OAuth2AuthenticationException(
                new OAuth2Error("invalid grant&hack=1", null, null));

        handler.onAuthenticationFailure(request, response, exception);

        assertThat(response.getRedirectedUrl()).isEqualTo("/auth-failed.html?reason=invalid+grant%26hack%3D1");
    }

    @Test
    @DisplayName("Non-OAuth2 exception -> fallback reason=unknown (defense in depth)")
    void nonOAuth2ExceptionFallsBackToUnknownReason() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        AuthenticationException exception = new BadCredentialsException("not oauth2 path");

        handler.onAuthenticationFailure(request, response, exception);

        assertThat(response.getRedirectedUrl()).isEqualTo("/auth-failed.html?reason=unknown");
    }

    @Test
    @DisplayName("Success handler records the flow's state and redirects to the success endpoint")
    void successHandlerRecordsStateAndRedirects() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setParameter("state", "state-xyz");
        MockHttpServletResponse response = new MockHttpServletResponse();

        config.oauth2SuccessHandler().onAuthenticationSuccess(request, response,
                new TestingAuthenticationToken("user", "n/a"));

        assertThat(response.getRedirectedUrl()).isEqualTo("/api/v1/auth/oauth2/success");
        assertThat(completedStates.wasCompleted("state-xyz")).isTrue();
    }

    @Test
    @DisplayName("Duplicate callback (authorization_request_not_found) for a completed state -> success page")
    void duplicateCallbackForCompletedStateRedirectsToSuccess() throws Exception {
        completedStates.markCompleted("state-abc");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setParameter("state", "state-abc");
        MockHttpServletResponse response = new MockHttpServletResponse();
        OAuth2AuthenticationException exception = new OAuth2AuthenticationException(
                new OAuth2Error("authorization_request_not_found"));

        handler.onAuthenticationFailure(request, response, exception);

        assertThat(response.getStatus()).isEqualTo(302);
        assertThat(response.getRedirectedUrl()).isEqualTo("/auth-finished.html");
    }

    @Test
    @DisplayName("authorization_request_not_found for an unknown state -> real error page")
    void authorizationRequestNotFoundForUnknownStateRedirectsToFailure() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setParameter("state", "never-completed");
        MockHttpServletResponse response = new MockHttpServletResponse();
        OAuth2AuthenticationException exception = new OAuth2AuthenticationException(
                new OAuth2Error("authorization_request_not_found"));

        handler.onAuthenticationFailure(request, response, exception);

        assertThat(response.getRedirectedUrl()).isEqualTo("/auth-failed.html?reason=authorization_request_not_found");
    }

    @Test
    @DisplayName("authorization_request_not_found for a *different* account's failed login -> error page (no false success)")
    void authorizationRequestNotFoundForADifferentStateFails() throws Exception {
        // Account A completed; account B's login genuinely fails in the same run.
        completedStates.markCompleted("account-A-state");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setParameter("state", "account-B-state");
        MockHttpServletResponse response = new MockHttpServletResponse();
        OAuth2AuthenticationException exception = new OAuth2AuthenticationException(
                new OAuth2Error("authorization_request_not_found"));

        handler.onAuthenticationFailure(request, response, exception);

        assertThat(response.getRedirectedUrl()).isEqualTo("/auth-failed.html?reason=authorization_request_not_found");
    }

    @Test
    @DisplayName("A genuine error (invalid_grant) still fails even for a completed state")
    void genuineErrorForCompletedStateStillFails() throws Exception {
        completedStates.markCompleted("state-x");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setParameter("state", "state-x");
        MockHttpServletResponse response = new MockHttpServletResponse();
        OAuth2AuthenticationException exception = new OAuth2AuthenticationException(
                new OAuth2Error("invalid_grant", "Authorization code expired", null));

        handler.onAuthenticationFailure(request, response, exception);

        assertThat(response.getRedirectedUrl()).isEqualTo("/auth-failed.html?reason=invalid_grant");
    }

    @Test
    @DisplayName("CORS allows the packaged Tauri webview origin (http://tauri.localhost)")
    void corsAllowsPackagedTauriWebviewOrigin() {
        CorsConfigurationSource source = config.corsConfigurationSource();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/system/readiness");
        request.setServletPath("/api/v1/system/readiness");

        CorsConfiguration configuration = source.getCorsConfiguration(request);

        assertThat(configuration).isNotNull();
        // The installed Windows app's webview reports this origin; the dev origins
        // (loopback ports) must keep working too.
        assertThat(configuration.checkOrigin("http://tauri.localhost")).isEqualTo("http://tauri.localhost");
        assertThat(configuration.checkOrigin("http://localhost:5173")).isEqualTo("http://localhost:5173");
        assertThat(configuration.checkOrigin("http://127.0.0.1:1420")).isEqualTo("http://127.0.0.1:1420");
        // A foreign web origin must still be rejected.
        assertThat(configuration.checkOrigin("https://evil.example")).isNull();
    }

    @Test
    @DisplayName("Authorization request carries PKCE (S256) even for a confidential client")
    void authorizationRequestIncludesPkceForConfidentialClient() {
        ClientRegistration registration = ClientRegistration.withRegistrationId("microsoft").clientId("test-client-id")
                .clientSecret("test-secret").clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}").scope("openid", "email")
                .authorizationUri("https://login.microsoftonline.com/common/oauth2/v2.0/authorize")
                .tokenUri("https://login.microsoftonline.com/common/oauth2/v2.0/token").build();
        OAuth2AuthorizationRequestResolver resolver = config
                .pkceAuthorizationRequestResolver(new InMemoryClientRegistrationRepository(registration));

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/oauth2/authorization/microsoft");
        request.setServletPath("/oauth2/authorization/microsoft");
        OAuth2AuthorizationRequest authorizationRequest = resolver.resolve(request);

        assertThat(authorizationRequest).isNotNull();
        assertThat(authorizationRequest.getAdditionalParameters()).containsKey("code_challenge")
                .containsEntry("code_challenge_method", "S256");
    }
}
