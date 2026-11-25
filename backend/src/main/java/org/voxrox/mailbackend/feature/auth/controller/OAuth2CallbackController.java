package org.voxrox.mailbackend.feature.auth.controller;

import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Controller;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.voxrox.mailbackend.exception.AppException;
import org.voxrox.mailbackend.exception.ValidationException;
import org.voxrox.mailbackend.feature.auth.service.OAuth2LoginService;
import org.voxrox.mailbackend.util.LogCategory;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Generic OAuth2 login flow across all registered providers.
 *
 * <ul>
 * <li>{@code GET /start?provider=google} — entry point for the client.
 * Validates the provider against {@link ClientRegistrationRepository} (no open
 * redirect) and redirects to {@code /oauth2/authorization/{provider}}, where
 * Spring Security starts the flow (state, PKCE, redirect to the provider).</li>
 * <li>{@code GET /success} — shared callback after a successful authorization.
 * {@link OAuth2AuthenticationToken#getAuthorizedClientRegistrationId()}
 * determines which provider completed the login; {@link OAuth2LoginService}
 * then resolves the matching claims extractor and token service. A sign-in it
 * rejects ends on {@code /auth-failed.html} with a fixed reason code, like a
 * callback Spring's filter rejects.</li>
 * </ul>
 *
 * <p>
 * Adding a new provider = a new {@code @Component} extractor + a Spring
 * Security {@code ClientRegistration} in {@code application.properties}. No
 * change to the controller.
 */
@Tag(name = "Authentication", description = "Generic OAuth2 login flow. The client opens "
        + "GET /api/v1/auth/oauth2/start?provider={google|microsoft|...} in the OS browser; the backend "
        + "redirects to the provider, and once the user consents the provider calls "
        + "/login/oauth2/code/{provider} (Spring Security filter), which completes the code-for-token "
        + "exchange and redirects to /api/v1/auth/oauth2/success, where the account is saved or updated. "
        + "The final page /auth-finished.html signals the end of the flow to the client.")
@Controller
@RequestMapping("/api/v1/auth/oauth2")
@Validated
public class OAuth2CallbackController {

    private static final Logger log = LoggerFactory.getLogger(OAuth2CallbackController.class);

    /*
     * Where the system browser lands when /success rejects the sign-in: the page
     * SecurityConfig's failure handler sends a callback the filter rejected to
     * (OAUTH2_FAILURE_REDIRECT there). The browser is the user's, not the app's
     * client, so a problem document would leave the user reading JSON.
     */
    private static final String FAILURE_REDIRECT = "redirect:/auth-failed.html?reason=";

    /*
     * The reason code a rejection puts on the failure page, by the message key of
     * the exception that rejected the sign-in. A fixed vocabulary in the shape of
     * the OAuth2 error codes the filter's failures carry, so the URL never holds
     * exception text, an address or a localized sentence, and needs no encoding.
     * The page shows the code for support and has words of its own for the codes
     * whose advice differs from "sign in again". The names follow the audit log's
     * reasons for the same rejections (OAuth2LoginService).
     */
    private static final Map<String, String> REASON_BY_MESSAGE_KEY = Map.ofEntries(
            Map.entry("validation.oauth2.loginNotCompleted", "login_not_completed"),
            Map.entry("error.mail.oauth2NoEmail", "missing_email"),
            Map.entry("error.mail.oauth2NoUserId", "missing_external_id"),
            Map.entry("error.mail.oauth2NoRefreshToken", "missing_refresh_token"),
            Map.entry("error.mail.oauth2ScopeNotGranted", "missing_scope"),
            Map.entry("error.account.alreadyExists", "account_already_exists"));

    /** The reason for a failure no guard names: a bug, the database, the vault. */
    private static final String UNEXPECTED_REASON = "login_failed";

    private final OAuth2LoginService oauth2LoginService;
    private final OAuth2AuthorizedClientService authorizedClientService;
    private final ClientRegistrationRepository clientRegistrationRepository;

    public OAuth2CallbackController(OAuth2LoginService oauth2LoginService,
            OAuth2AuthorizedClientService authorizedClientService,
            ClientRegistrationRepository clientRegistrationRepository) {
        this.oauth2LoginService = oauth2LoginService;
        this.authorizedClientService = authorizedClientService;
        this.clientRegistrationRepository = clientRegistrationRepository;
    }

    /**
     * Entry endpoint for the client. Validates {@code provider} against
     * {@link ClientRegistrationRepository} — without the check, a value in the
     * query string could be used to open-redirect to anything the Spring Security
     * filter recognizes as its own path.
     */
    @Operation(summary = "Start the OAuth2 login flow", description = "Entry point for the client. Open it in the OS browser — the backend "
            + "redirects to the selected provider, and after authorization completes the user lands on "
            + "/auth-finished.html. On success the account is created (or updated) "
            + "with authType=OAUTH2 and oauth2Provider matching the query parameter.")
    @GetMapping("/start")
    public String start(
            @RequestParam @NotBlank(message = "{validation.notBlank}") @Size(max = 50, message = "{validation.size.max}") @Pattern(regexp = "^[a-z0-9_-]+$", message = "{validation.oauth2.providerFormat}") String provider) {
        if (clientRegistrationRepository.findByRegistrationId(provider) == null) {
            throw new ValidationException("Unknown OAuth2 provider: " + provider, "validation.oauth2.unknownProvider",
                    provider);
        }
        return "redirect:/oauth2/authorization/" + provider;
    }

    @Operation(summary = "OAuth2 callback (success)", description = "Target endpoint after a successful authorization at any provider. Spring Security "
            + "redirects here after the flow completes. The controller hands the tokens to the service "
            + "and sends the user to a static page the client captures. The client should not call this "
            + "endpoint directly — the entry point is /api/v1/auth/oauth2/start?provider=...")
    @GetMapping("/success")
    public String success(OAuth2AuthenticationToken authToken, @AuthenticationPrincipal OAuth2User oauth2User,
            HttpServletRequest request) {
        try {
            /*
             * The endpoint is permitAll (it is the post-login redirect target), so a direct
             * anonymous GET arrives without authentication and Spring injects null for both
             * parameters — reject cleanly instead of an NPE/500.
             */
            if (authToken == null || oauth2User == null) {
                throw new ValidationException("OAuth2 callback invoked outside a completed login flow.",
                        "validation.oauth2.loginNotCompleted");
            }
            String providerName = authToken.getAuthorizedClientRegistrationId();
            OAuth2AuthorizedClient authorizedClient = authorizedClientService.loadAuthorizedClient(providerName,
                    authToken.getName());
            if (authorizedClient == null) {
                throw new ValidationException(
                        "OAuth2 authorized client for provider '" + providerName + "' was not found in the session.",
                        "validation.oauth2.loginNotCompleted");
            }
            try {
                oauth2LoginService.processLogin(providerName, oauth2User, authorizedClient);
            } finally {
                /*
                 * On success the refresh token is persisted encrypted by now; on a rejected
                 * login it is not wanted at all. Either way drop the plaintext copy Spring
                 * keeps in the in-memory authorized-client store so it does not outlive the
                 * login flow.
                 */
                authorizedClientService.removeAuthorizedClient(providerName, authToken.getName());
            }
            return "redirect:/auth-finished.html";
        } catch (RuntimeException e) {
            return FAILURE_REDIRECT + reasonFor(e);
        } finally {
            /*
             * The flow is over, whatever its outcome: end the system browser's session so
             * the sign-in it carries does not outlive it. The chain no longer accepts that
             * sign-in for the API (audit B2-1), and this keeps it from lingering for the
             * container's idle timeout.
             */
            HttpSession session = request.getSession(false);
            if (session != null) {
                session.invalidate();
            }
            SecurityContextHolder.clearContext();
        }
    }

    /**
     * The reason code for a sign-in {@code /success} rejected, with the cause
     * logged here, since {@code GlobalExceptionHandler} no longer sees it. The
     * levels are the ones that handler gave the same exceptions: an
     * {@link AppException} is a rejection the code planned for and is logged at
     * WARN with its message, anything else keeps its ERROR and stack trace.
     */
    private static String reasonFor(RuntimeException e) {
        if (e instanceof AppException rejection) {
            String key = rejection.getMessageKey();
            String reason = key == null
                    ? UNEXPECTED_REASON
                    : REASON_BY_MESSAGE_KEY.getOrDefault(key, UNEXPECTED_REASON);
            log.warn("{} OAuth2 sign-in rejected at /success: reason={}, code={}, message={}", LogCategory.AUTH, reason,
                    rejection.getCode(), rejection.getMessage());
            return reason;
        }
        log.error("{} OAuth2 sign-in failed at /success: reason={}", LogCategory.CRITICAL, UNEXPECTED_REASON, e);
        return UNEXPECTED_REASON;
    }
}
