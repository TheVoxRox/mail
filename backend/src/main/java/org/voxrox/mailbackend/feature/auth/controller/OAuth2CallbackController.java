package org.voxrox.mailbackend.feature.auth.controller;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
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
import org.voxrox.mailbackend.exception.ValidationException;
import org.voxrox.mailbackend.feature.auth.service.OAuth2LoginService;

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
 * then resolves the matching claims extractor and token service.</li>
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
    public String success(OAuth2AuthenticationToken authToken, @AuthenticationPrincipal OAuth2User oauth2User) {
        String providerName = authToken.getAuthorizedClientRegistrationId();
        OAuth2AuthorizedClient authorizedClient = authorizedClientService.loadAuthorizedClient(providerName,
                authToken.getName());
        if (authorizedClient == null) {
            throw new ValidationException(
                    "OAuth2 authorized client for provider '" + providerName + "' was not found in the session.");
        }
        oauth2LoginService.processLogin(providerName, oauth2User, authorizedClient);
        return "redirect:/auth-finished.html";
    }
}
