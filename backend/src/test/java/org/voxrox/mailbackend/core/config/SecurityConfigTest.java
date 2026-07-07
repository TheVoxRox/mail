package org.voxrox.mailbackend.core.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
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

    private final AuthenticationFailureHandler handler = new SecurityConfig(null).oauth2FailureHandler();

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
    @DisplayName("Duplicate callback (authorization_request_not_found) after login in same session -> success page")
    void duplicateCallbackAfterLoginRedirectsToSuccess() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        // The first callback already saved an authenticated OAuth2 token to the
        // session.
        request.getSession(true).setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                authenticatedOAuthContext());
        MockHttpServletResponse response = new MockHttpServletResponse();
        OAuth2AuthenticationException exception = new OAuth2AuthenticationException(
                new OAuth2Error("authorization_request_not_found"));

        handler.onAuthenticationFailure(request, response, exception);

        assertThat(response.getStatus()).isEqualTo(302);
        assertThat(response.getRedirectedUrl()).isEqualTo("/auth-finished.html");
    }

    @Test
    @DisplayName("authorization_request_not_found with no prior login (no session) -> real error page")
    void authorizationRequestNotFoundWithoutSessionRedirectsToFailure() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        OAuth2AuthenticationException exception = new OAuth2AuthenticationException(
                new OAuth2Error("authorization_request_not_found"));

        handler.onAuthenticationFailure(request, response, exception);

        assertThat(response.getRedirectedUrl()).isEqualTo("/auth-failed.html?reason=authorization_request_not_found");
    }

    @Test
    @DisplayName("A genuine error (invalid_grant) still fails even with an authenticated session")
    void genuineErrorWithAuthenticatedSessionStillFails() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.getSession(true).setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                authenticatedOAuthContext());
        MockHttpServletResponse response = new MockHttpServletResponse();
        OAuth2AuthenticationException exception = new OAuth2AuthenticationException(
                new OAuth2Error("invalid_grant", "Authorization code expired", null));

        handler.onAuthenticationFailure(request, response, exception);

        assertThat(response.getRedirectedUrl()).isEqualTo("/auth-failed.html?reason=invalid_grant");
    }

    private static SecurityContext authenticatedOAuthContext() {
        OAuth2User principal = new DefaultOAuth2User(List.of(new SimpleGrantedAuthority("ROLE_USER")),
                Map.of("sub", "123"), "sub");
        // The OAuth2AuthenticationToken constructor marks the token authenticated.
        return new SecurityContextImpl(new OAuth2AuthenticationToken(principal, principal.getAuthorities(), "google"));
    }

    @Test
    @DisplayName("CORS allows the packaged Tauri webview origin (http://tauri.localhost)")
    void corsAllowsPackagedTauriWebviewOrigin() {
        CorsConfigurationSource source = new SecurityConfig(null).corsConfigurationSource();
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
        OAuth2AuthorizationRequestResolver resolver = new SecurityConfig(null)
                .pkceAuthorizationRequestResolver(new InMemoryClientRegistrationRepository(registration));

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/oauth2/authorization/microsoft");
        request.setServletPath("/oauth2/authorization/microsoft");
        OAuth2AuthorizationRequest authorizationRequest = resolver.resolve(request);

        assertThat(authorizationRequest).isNotNull();
        assertThat(authorizationRequest.getAdditionalParameters()).containsKey("code_challenge")
                .containsEntry("code_challenge_method", "S256");
    }
}
