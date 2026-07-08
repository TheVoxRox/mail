package org.voxrox.mailbackend.feature.mail.controller;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration;
import org.springframework.boot.security.oauth2.client.autoconfigure.OAuth2ClientAutoConfiguration;
import org.springframework.boot.security.oauth2.client.autoconfigure.servlet.OAuth2ClientWebSecurityAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.voxrox.mailbackend.core.security.InternalApiKeyProvider;
import org.voxrox.mailbackend.feature.mail.service.RemoteImageAllowlistService;

@WebMvcTest(controllers = RemoteImageAllowlistController.class, excludeAutoConfiguration = {
        SecurityAutoConfiguration.class, OAuth2ClientAutoConfiguration.class,
        OAuth2ClientWebSecurityAutoConfiguration.class})
@AutoConfigureMockMvc(addFilters = false)
class RemoteImageAllowlistControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RemoteImageAllowlistService service;

    // ApiKeyFilter is a @Component -> WebMvcTest wants to construct it even when
    // filters are disabled.
    @MockitoBean
    private InternalApiKeyProvider apiKeyProvider;

    @Test
    @DisplayName("PUT with a valid body -> 204 and the sender is allowed")
    void putAllowsSender() throws Exception {
        mockMvc.perform(put("/api/v1/remote-images/allowlist").contentType(MediaType.APPLICATION_JSON)
                .content("{\"accountId\":5,\"senderEmail\":\"news@example.com\"}")).andExpect(status().isNoContent());

        verify(service).allow(5L, "news@example.com");
    }

    @Test
    @DisplayName("PUT with an invalid email -> 400 and the service is never called")
    void putRejectsInvalidEmail() throws Exception {
        mockMvc.perform(put("/api/v1/remote-images/allowlist").contentType(MediaType.APPLICATION_JSON)
                .content("{\"accountId\":5,\"senderEmail\":\"not-an-email\"}")).andExpect(status().isBadRequest());

        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("DELETE with query params -> 204 and the sender is removed")
    void deleteDisallowsSender() throws Exception {
        mockMvc.perform(delete("/api/v1/remote-images/allowlist").param("accountId", "5").param("senderEmail",
                "news@example.com")).andExpect(status().isNoContent());

        verify(service).disallow(5L, "news@example.com");
    }

    @Test
    @DisplayName("GET -> 200 with the allowed sender emails")
    void getListsSenders() throws Exception {
        when(service.listAllowedSenders(5L)).thenReturn(List.of("a@x.com", "b@y.com"));

        mockMvc.perform(get("/api/v1/remote-images/allowlist").param("accountId", "5")).andExpect(status().isOk())
                .andExpect(jsonPath("$[0]").value("a@x.com")).andExpect(jsonPath("$[1]").value("b@y.com"));
    }
}
