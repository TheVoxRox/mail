package org.voxrox.mailbackend.feature.account.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration;
import org.springframework.boot.security.oauth2.client.autoconfigure.OAuth2ClientAutoConfiguration;
import org.springframework.boot.security.oauth2.client.autoconfigure.servlet.OAuth2ClientWebSecurityAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.voxrox.mailbackend.core.security.InternalApiKeyProvider;
import org.voxrox.mailbackend.feature.account.dto.MailProviderResponse;
import org.voxrox.mailbackend.feature.account.service.AccountProviderService;

@WebMvcTest(controllers = MailProviderController.class, excludeAutoConfiguration = {SecurityAutoConfiguration.class,
        OAuth2ClientAutoConfiguration.class, OAuth2ClientWebSecurityAutoConfiguration.class})
@AutoConfigureMockMvc(addFilters = false)
class MailProviderControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AccountProviderService providerService;
    @MockitoBean
    private InternalApiKeyProvider apiKeyProvider;

    private MailProviderResponse gmail() {
        return new MailProviderResponse(1L, "Gmail", "imap.gmail.com", 993, true, "smtp.gmail.com", 465, true,
                "gmail.com", true, "google");
    }

    @Test
    @DisplayName("GET providers -> 200 with the list")
    void listAll() throws Exception {
        when(providerService.getAllProviders()).thenReturn(List.of(gmail(), new MailProviderResponse(2L, "Seznam",
                "imap.seznam.cz", 993, true, "smtp.seznam.cz", 465, true, "seznam.cz", false, null)));

        mockMvc.perform(get("/api/v1/accounts/providers")).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2)).andExpect(jsonPath("$[0].name").value("Gmail"));
    }

    @Test
    @DisplayName("GET resolve?email=me@gmail.com -> 200 with the provider")
    void resolveFound() throws Exception {
        when(providerService.findProviderByEmail("me@gmail.com")).thenReturn(Optional.of(gmail()));

        mockMvc.perform(get("/api/v1/accounts/providers/resolve").param("email", "me@gmail.com"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Gmail"));
    }

    @Test
    @DisplayName("GET resolve - unknown domain -> 404 (empty body)")
    void resolveNotFound() throws Exception {
        when(providerService.findProviderByEmail("x@unknown.xyz")).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/accounts/providers/resolve").param("email", "x@unknown.xyz"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET resolve - invalid email format -> 400")
    void resolveInvalidEmail() throws Exception {
        mockMvc.perform(get("/api/v1/accounts/providers/resolve").param("email", "not-an-email"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("GET resolve - empty email -> 400 (@NotBlank)")
    void resolveEmptyEmail() throws Exception {
        mockMvc.perform(get("/api/v1/accounts/providers/resolve").param("email", ""))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("GET provider/{id} -> 200")
    void getByIdFound() throws Exception {
        when(providerService.getProviderById(1L)).thenReturn(Optional.of(gmail()));

        mockMvc.perform(get("/api/v1/accounts/providers/1")).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1));
    }

    @Test
    @DisplayName("GET provider/{id} - does not exist -> 404")
    void getByIdNotFound() throws Exception {
        when(providerService.getProviderById(99L)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/accounts/providers/99")).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET provider/{id} - non-positive ID -> 400")
    void getByIdInvalid() throws Exception {
        mockMvc.perform(get("/api/v1/accounts/providers/0")).andExpect(status().isBadRequest());
    }
}
