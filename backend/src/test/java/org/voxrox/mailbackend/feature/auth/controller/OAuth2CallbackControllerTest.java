package org.voxrox.mailbackend.feature.auth.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.user.OAuth2User;
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

    @BeforeEach
    void setUp() {
        controller = new OAuth2CallbackController(loginService, authorizedClientService, clientRegistrationRepository);
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
        assertThatThrownBy(() -> controller.success(null, null)).isInstanceOf(ValidationException.class)
                .hasMessageContaining("outside a completed login flow");
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

        String view = controller.success(token, user);

        assertThat(view).isEqualTo("redirect:/auth-finished.html");
        verify(loginService).processLogin("google", user, client);
        // The plaintext refresh token must not outlive the flow — it is already
        // persisted encrypted by processLogin.
        verify(authorizedClientService).removeAuthorizedClient("google", "principal");
    }

    @Test
    @DisplayName("success — authorized client missing from the store -> ValidationException")
    void successRejectsMissingAuthorizedClient() {
        OAuth2AuthenticationToken token = mock(OAuth2AuthenticationToken.class);
        OAuth2User user = mock(OAuth2User.class);
        when(token.getAuthorizedClientRegistrationId()).thenReturn("google");
        when(token.getName()).thenReturn("principal");
        when(authorizedClientService.loadAuthorizedClient("google", "principal")).thenReturn(null);

        assertThatThrownBy(() -> controller.success(token, user)).isInstanceOf(ValidationException.class);
    }
}
