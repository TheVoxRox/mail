package org.voxrox.mailbackend.feature.auth.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.config.oauth2.client.CommonOAuth2Provider;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.voxrox.mailbackend.exception.AccountAlreadyExistsException;
import org.voxrox.mailbackend.exception.ErrorCode;
import org.voxrox.mailbackend.exception.MailOperationException;
import org.voxrox.mailbackend.exception.ValidationException;
import org.voxrox.mailbackend.feature.account.service.ExternalProviderLoginService;
import org.voxrox.mailbackend.feature.auth.service.ExternalUserClaims;
import org.voxrox.mailbackend.feature.auth.service.OAuth2ClaimsExtractor;
import org.voxrox.mailbackend.feature.auth.service.OAuth2ClaimsExtractorRegistry;
import org.voxrox.mailbackend.feature.auth.service.OAuth2LoginService;

@ExtendWith(MockitoExtension.class)
class OAuth2CallbackControllerTest {

    private static final String FAILURE_PAGE = "redirect:/auth-failed.html?reason=";
    private static final String EMAIL = "user@example.com";
    private static final String GMAIL_SCOPE = "https://mail.google.com/";
    private static final Set<String> OIDC_SCOPES = Set.of("openid", "email", "profile");

    @Mock
    private OAuth2LoginService loginService;
    @Mock
    private OAuth2AuthorizedClientService authorizedClientService;
    @Mock
    private ClientRegistrationRepository clientRegistrationRepository;
    @Mock
    private ExternalProviderLoginService externalProviderLoginService;

    private OAuth2CallbackController controller;
    private final MockHttpSession session = new MockHttpSession();
    private final MockHttpServletRequest request = new MockHttpServletRequest();

    @BeforeEach
    void setUp() {
        controller = new OAuth2CallbackController(loginService, authorizedClientService, clientRegistrationRepository);
        request.setSession(session);
    }

    @Test
    @DisplayName("start — unknown provider -> ValidationException (no open redirect)")
    void startRejectsUnknownProvider() {
        when(clientRegistrationRepository.findByRegistrationId("evil")).thenReturn(null);

        assertThatThrownBy(() -> controller.start("evil")).isInstanceOf(ValidationException.class);
    }

    /**
     * OAuth audit §1: the redirect {@code /start} issues goes to Spring's own
     * authorization endpoint, which builds the request to the provider (state,
     * PKCE) — never to a target taken from the request.
     */
    @Test
    @DisplayName("start — a known provider -> redirect to Spring's authorization endpoint for it")
    void startRedirectsToSpringAuthorizationEndpoint() {
        when(clientRegistrationRepository.findByRegistrationId("google"))
                .thenReturn(CommonOAuth2Provider.GOOGLE.getBuilder("google").clientId("client-id").build());

        assertThat(controller.start("google")).isEqualTo("redirect:/oauth2/authorization/google");
    }

    @Test
    @DisplayName("success — direct anonymous GET (null auth) -> the failure page, not an NPE")
    void successRejectsAnonymousInvocation() {
        // /api/v1/auth/oauth2/** is permitAll (post-login redirect target), so a
        // direct GET arrives with no authentication and Spring injects nulls.
        assertThat(controller.success(null, null, request)).isEqualTo(FAILURE_PAGE + "login_not_completed");
        assertThat(session.isInvalid()).isTrue();
    }

    @Test
    @DisplayName("success — happy path delegates and evicts the in-memory authorized client")
    void successDelegatesAndRemovesAuthorizedClient() {
        OAuth2AuthenticationToken token = mock(OAuth2AuthenticationToken.class);
        OAuth2User user = mock(OAuth2User.class);
        OAuth2AuthorizedClient client = mock(OAuth2AuthorizedClient.class);
        when(token.getAuthorizedClientRegistrationId()).thenReturn("google");
        when(token.getName()).thenReturn("principal");
        when(authorizedClientService.loadAuthorizedClient("google", "principal")).thenReturn(client);

        String view = controller.success(token, user, request);

        assertThat(view).isEqualTo("redirect:/auth-finished.html");
        verify(loginService).processLogin("google", user, client);
        // The plaintext refresh token must not outlive the flow — it is already
        // persisted encrypted by processLogin.
        verify(authorizedClientService).removeAuthorizedClient("google", "principal");
        // The sign-in the browser session carries must not outlive the flow (B2-1).
        assertThat(session.isInvalid()).isTrue();
    }

    @Test
    @DisplayName("success — a rejected login still evicts the authorized client and ends the session")
    void successRejectedLoginEvictsClientAndEndsSession() {
        OAuth2AuthenticationToken token = mock(OAuth2AuthenticationToken.class);
        OAuth2User user = mock(OAuth2User.class);
        OAuth2AuthorizedClient client = mock(OAuth2AuthorizedClient.class);
        when(token.getAuthorizedClientRegistrationId()).thenReturn("google");
        when(token.getName()).thenReturn("principal");
        when(authorizedClientService.loadAuthorizedClient("google", "principal")).thenReturn(client);
        doThrow(new MailOperationException(ErrorCode.MAIL_OAUTH2_SCOPE_NOT_GRANTED, "scope not granted",
                HttpStatus.UNAUTHORIZED, "error.mail.oauth2ScopeNotGranted")).when(loginService)
                .processLogin("google", user, client);

        assertThat(controller.success(token, user, request)).isEqualTo(FAILURE_PAGE + "missing_scope");

        verify(authorizedClientService).removeAuthorizedClient("google", "principal");
        assertThat(session.isInvalid()).isTrue();
    }

    @Test
    @DisplayName("success — authorized client missing from the store -> the failure page")
    void successRejectsMissingAuthorizedClient() {
        OAuth2AuthenticationToken token = mock(OAuth2AuthenticationToken.class);
        OAuth2User user = mock(OAuth2User.class);
        when(token.getAuthorizedClientRegistrationId()).thenReturn("google");
        when(token.getName()).thenReturn("principal");
        when(authorizedClientService.loadAuthorizedClient("google", "principal")).thenReturn(null);

        assertThat(controller.success(token, user, request)).isEqualTo(FAILURE_PAGE + "login_not_completed");
        assertThat(session.isInvalid()).isTrue();
    }

    static Stream<Arguments> guardRejections() {
        return Stream.of(
                Arguments.of("missing_email", new ExternalUserClaims(null, "User", "sub-1", "rt"), OIDC_SCOPES),
                Arguments.of("missing_external_id", new ExternalUserClaims(EMAIL, "User", null, "rt"), OIDC_SCOPES),
                Arguments.of("missing_refresh_token", new ExternalUserClaims(EMAIL, "User", "sub-1", null),
                        OIDC_SCOPES),
                Arguments.of("missing_scope", new ExternalUserClaims(EMAIL, "User", "sub-1", "rt"), OIDC_SCOPES));
    }

    /**
     * The reasons are keyed by the message keys the rejections carry, so each one
     * runs through the real {@link OAuth2LoginService}: a key renamed there would
     * otherwise turn its reason into {@code login_failed} with every test green.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("guardRejections")
    @DisplayName("success — each login guard's rejection names its own reason on the failure page")
    void successNamesEachGuardRejection(String reason, ExternalUserClaims claims, Set<String> grantedScopes) {
        assertThat(signInThroughTheGuards(claims, grantedScopes)).isEqualTo(FAILURE_PAGE + reason);
        assertThat(session.isInvalid()).isTrue();
    }

    @Test
    @DisplayName("success — an address another sign-in owns -> account_already_exists, and the address stays out")
    void successNamesAnAddressAnotherSignInOwns() {
        doThrow(new AccountAlreadyExistsException(EMAIL)).when(externalProviderLoginService)
                .processExternalProviderLogin("google", EMAIL, "User", "sub-1", "rt");

        String view = signInThroughTheGuards(new ExternalUserClaims(EMAIL, "User", "sub-1", "rt"),
                Set.of(GMAIL_SCOPE, "openid"));

        assertThat(view).isEqualTo(FAILURE_PAGE + "account_already_exists");
    }

    @Test
    @DisplayName("success — a failure no guard names -> login_failed, and its text stays out of the URL")
    void successNamesAnUnexpectedFailureWithoutItsText() {
        doThrow(new IllegalStateException("[SQLITE_BUSY] database is locked")).when(externalProviderLoginService)
                .processExternalProviderLogin("google", EMAIL, "User", "sub-1", "rt");

        String view = signInThroughTheGuards(new ExternalUserClaims(EMAIL, "User", "sub-1", "rt"),
                Set.of(GMAIL_SCOPE, "openid"));

        assertThat(view).isEqualTo(FAILURE_PAGE + "login_failed");
        verify(authorizedClientService).removeAuthorizedClient("google", "principal");
        assertThat(session.isInvalid()).isTrue();
    }

    /**
     * {@code /success} over the real login service, whose guards decide on these
     * claims and this grant; persistence is the mocked
     * {@link ExternalProviderLoginService}.
     */
    private String signInThroughTheGuards(ExternalUserClaims claims, Set<String> grantedScopes) {
        OAuth2ClaimsExtractor extractor = mock(OAuth2ClaimsExtractor.class);
        OAuth2User user = mock(OAuth2User.class);
        OAuth2AuthorizedClient client = mock(OAuth2AuthorizedClient.class);
        OAuth2AuthenticationToken token = mock(OAuth2AuthenticationToken.class);
        when(extractor.providerName()).thenReturn("google");
        when(extractor.extract(user, client)).thenReturn(claims);
        // Read only by the scope guard, which the identity guards stop short of.
        lenient().when(extractor.requiredMailScopes()).thenReturn(Set.of(GMAIL_SCOPE));
        lenient().when(client.getAccessToken()).thenReturn(new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER,
                "at-token", Instant.now(), Instant.now().plusSeconds(3600), grantedScopes));
        when(token.getAuthorizedClientRegistrationId()).thenReturn("google");
        when(token.getName()).thenReturn("principal");
        when(authorizedClientService.loadAuthorizedClient("google", "principal")).thenReturn(client);
        OAuth2LoginService guards = new OAuth2LoginService(externalProviderLoginService,
                new OAuth2ClaimsExtractorRegistry(List.of(extractor)));

        return new OAuth2CallbackController(guards, authorizedClientService, clientRegistrationRepository)
                .success(token, user, request);
    }
}
