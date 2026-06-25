package org.voxrox.mailbackend.core.config;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

import jakarta.servlet.DispatcherType;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestRedirectFilter;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.voxrox.mailbackend.core.security.ApiKeyFilter;
import org.voxrox.mailbackend.util.LogCategory;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    /*
     * Target on OAuth2 failure. The default Spring Security failure handler
     * redirects to /login?error and `DefaultLoginPageGeneratingFilter` renders a
     * page with "Invalid credentials" text plus links to every registered provider.
     * That message is for the form-login scenario (wrong password) — for an OAuth
     * flow (stale state, expired code, ...) it is deeply misleading because the
     * user never entered any credentials. The custom page in static/ clearly tells
     * the user what happened and what to do (go back to the app and click again).
     */
    static final String OAUTH2_FAILURE_REDIRECT = "/auth-failed.html";

    private final ApiKeyFilter apiKeyFilter;

    public SecurityConfig(ApiKeyFilter apiKeyFilter) {
        this.apiKeyFilter = apiKeyFilter;
    }

    /*
     * Public endpoints are limited to the local desktop model: the OAuth flow must
     * be served by the system browser without X-API-KEY and Spring Security
     * state/PKCE protects integrity. OpenAPI/Swagger paths are here for explicit
     * contract generation, but the production sidecar does not expose them by
     * default (springdoc is disabled there).
     *
     * Both `auth-finished.html` and `auth-failed.html` are static pages rendered in
     * the external browser after a (un)successful OAuth flow — they must be
     * reachable without an API key and without a session.
     *
     * The list is package-private and immutable so it cannot be altered from
     * outside. A single API key matches a single-user app; endpoints therefore do
     * not enforce per-account ownership.
     */
    static final List<String> PUBLIC_ENDPOINTS = List.of("/api/v1/auth/oauth2/**", "/oauth2/authorization/**",
            "/login/oauth2/code/**", "/auth-finished.html", "/auth-failed.html", "/error", "/v3/api-docs",
            "/v3/api-docs/**", "/swagger-ui.html", "/swagger-ui/**");

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http,
            OAuth2AuthorizationRequestResolver pkceAuthorizationRequestResolver) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable).cors(cors -> cors.configurationSource(corsConfigurationSource()))

                /*
                 * OAuth2 login needs a short-lived session for state/PKCE and the
                 * AuthorizedClient. Other /api/** endpoints stay behind the ApiKeyFilter and do
                 * not create sessions of their own.
                 */
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))

                .authorizeHttpRequests(auth -> auth
                        /*
                         * ASYNC/ERROR are server-internal dispatches that continue an
                         * already-authorized REQUEST — e.g. an SSE emitter
                         * (/api/v1/notifications/stream) timing out after 30 min. Spring Security 6
                         * re-runs the AuthorizationFilter on these dispatches; the X-API-KEY is not
                         * re-applied, so the re-auth is denied and — because the SSE response is
                         * already committed — Spring logs a noisy ERROR
                         * ("response is already committed") every 30 min. They cannot be triggered from
                         * outside the container, so permitting them is safe; this is the documented
                         * Spring Security pattern for async endpoints.
                         */
                        .dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR).permitAll()
                        .requestMatchers(PUBLIC_ENDPOINTS.toArray(String[]::new)).permitAll().anyRequest()
                        .authenticated())

                /*
                 * Explicit oauth2Login keeps Spring filters for the start/callback flow even
                 * with a custom SecurityFilterChain. The authorizationRequestResolver adds PKCE
                 * to the authorization request (see `pkceAuthorizationRequestResolver()` —
                 * confidential clients do not get it by default). The failureHandler logic is
                 * described in `oauth2FailureHandler()` — the Spring default would swallow the
                 * error at DEBUG level and bounce the user to /login?error with a misleading
                 * message.
                 */
                .oauth2Login(oauth -> oauth.authorizationEndpoint(
                        authorization -> authorization.authorizationRequestResolver(pkceAuthorizationRequestResolver))
                        .defaultSuccessUrl("/api/v1/auth/oauth2/success", true).failureHandler(oauth2FailureHandler()))

                .addFilterBefore(apiKeyFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    /**
     * Failure handler for the OAuth2 flow. Logs the actual error at WARN level (the
     * Spring default logs it only at DEBUG, so production logs were empty after a
     * failure) and redirects to the custom `auth-failed.html` instead of the
     * default Spring `/login?error` page.
     *
     * {@code OAuth2AuthenticationException.getError()} carries a code per RFC 6749
     * §5.2 (e.g. {@code invalid_grant}, {@code invalid_request}) or Spring-specific
     * codes ({@code authorization_request_not_found} — typically a stale state in
     * the session).
     */
    @Bean
    public AuthenticationFailureHandler oauth2FailureHandler() {
        return (request, response, exception) -> {
            String errorCode = "unknown";
            String description = null;
            if (exception instanceof OAuth2AuthenticationException oauthEx) {
                errorCode = oauthEx.getError().getErrorCode();
                description = oauthEx.getError().getDescription();
            }
            log.warn("{} OAuth2 flow failed: errorCode={} description={} exceptionType={} message={}",
                    LogCategory.SECURITY, errorCode, description, exception.getClass().getSimpleName(),
                    exception.getMessage());
            String encodedReason = URLEncoder.encode(errorCode, StandardCharsets.UTF_8);
            response.sendRedirect(OAUTH2_FAILURE_REDIRECT + "?reason=" + encodedReason);
        };
    }

    /**
     * Authorization-request resolver that adds PKCE (RFC 7636, {@code S256}) to the
     * authorization-code flow.
     *
     * <p>
     * Spring Security enables PKCE automatically only for public clients. Microsoft
     * is one ({@code client-authentication-method=none}), but Google is configured
     * as a confidential client ({@code client_secret_post} with a secret), so
     * without this customizer no {@code code_challenge} would be sent on the Google
     * flow. OAuth 2.1 and the OAuth Security BCP recommend PKCE for <em>all</em>
     * clients as defense against authorization-code injection, and Google accepts a
     * {@code client_secret} together with a {@code code_verifier}.
     */
    @Bean
    public OAuth2AuthorizationRequestResolver pkceAuthorizationRequestResolver(
            ClientRegistrationRepository clientRegistrationRepository) {
        DefaultOAuth2AuthorizationRequestResolver resolver = new DefaultOAuth2AuthorizationRequestResolver(
                clientRegistrationRepository,
                OAuth2AuthorizationRequestRedirectFilter.DEFAULT_AUTHORIZATION_REQUEST_BASE_URI);
        resolver.setAuthorizationRequestCustomizer(OAuth2AuthorizationRequestCustomizers.withPkce());
        return resolver;
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();

        /*
         * Origins: - http://localhost:[*] / http://127.0.0.1:[*] — `vite dev` / `tauri
         * dev` serve the frontend over a loopback HTTP server on a dynamic port, so a
         * fixed whitelist would fail on port collision. - http://tauri.localhost — the
         * origin of the PACKAGED Windows webview (WebView2 serves the bundled assets
         * from this synthetic host). Without it Spring's CORS processor rejects EVERY
         * request from the installed app with 403 before the API-key filter even runs,
         * even though the desktop model is loopback-only. `@tauri-apps/plugin-http`
         * forwards this Origin header, so the server-side check still applies. -
         * tauri://localhost — the equivalent webview origin on macOS/Linux, kept for
         * parity even though V0.1.0 ships Windows-only.
         */
        configuration.setAllowedOriginPatterns(List.of("http://localhost:[*]", "http://127.0.0.1:[*]",
                "http://tauri.localhost", "tauri://localhost", "file://*"));

        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-API-KEY", "Cache-Control"));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", configuration);
        return source;
    }
}
