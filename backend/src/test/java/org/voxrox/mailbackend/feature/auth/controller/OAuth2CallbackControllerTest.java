package org.voxrox.mailbackend.feature.auth.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.voxrox.mailbackend.exception.ErrorCode;
import org.voxrox.mailbackend.exception.MailOperationException;
import org.voxrox.mailbackend.exception.ValidationException;
import org.voxrox.mailbackend.feature.auth.service.OAuth2LoginService;

@ExtendWith(MockitoExtension.class)
class OAuth2CallbackControllerTest {

    @Mock
    private OAuth2LoginService loginService;
    @Mock
    private OAuth2AuthorizedClientService authorizedClientService;
    @Mock
    private ClientRegistrationRepository clientRegistrationRepository;

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

    @Test
    @DisplayName("success — direct anonymous GET (null auth) -> ValidationException, not NPE")
    void successRejectsAnonymousInvocation() {
        // /api/v1/auth/oauth2/** is permitAll (post-login redirect target), so a
        // direct GET arrives with no authentication and Spring injects nulls.
        assertThatThrownBy(() -> controller.success(null, null, request)).isInstanceOf(ValidationException.class)
                .hasMessageContaining("outside a completed login flow")
                .extracting(e -> ((ValidationException) e).getMessageKey())
                .isEqualTo("validation.oauth2.loginNotCompleted");
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

        assertThatThrownBy(() -> controller.success(token, user, request)).isInstanceOf(MailOperationException.class);

        verify(authorizedClientService).removeAuthorizedClient("google", "principal");
        assertThat(session.isInvalid()).isTrue();
    }

    @Test
    @DisplayName("success — authorized client missing from the store -> ValidationException")
    void successRejectsMissingAuthorizedClient() {
        OAuth2AuthenticationToken token = mock(OAuth2AuthenticationToken.class);
        OAuth2User user = mock(OAuth2User.class);
        when(token.getAuthorizedClientRegistrationId()).thenReturn("google");
        when(token.getName()).thenReturn("principal");
        when(authorizedClientService.loadAuthorizedClient("google", "principal")).thenReturn(null);

        assertThatThrownBy(() -> controller.success(token, user, request)).isInstanceOf(ValidationException.class)
                .extracting(e -> ((ValidationException) e).getMessageKey())
                .isEqualTo("validation.oauth2.loginNotCompleted");
        assertThat(session.isInvalid()).isTrue();
    }
}
