package org.voxrox.mailbackend.core.clientconfig;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

@WebMvcTest(controllers = ClientConfigController.class, excludeAutoConfiguration = {SecurityAutoConfiguration.class,
        OAuth2ClientAutoConfiguration.class, OAuth2ClientWebSecurityAutoConfiguration.class})
@AutoConfigureMockMvc(addFilters = false)
class ClientConfigControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ClientConfigService clientConfigService;

    @MockitoBean
    private InternalApiKeyProvider apiKeyProvider;

    @Test
    @DisplayName("GET /api/v1/client-config returns safe limits for the frontend")
    void getClientConfig() throws Exception {
        when(clientConfigService.getClientConfig()).thenReturn(new ClientConfigResponse(50, 200, 256, 20, 100, 10, 20,
                10 * 1024 * 1024L, 25 * 1024 * 1024L, 5 * 1024 * 1024L));

        mockMvc.perform(get("/api/v1/client-config")).andExpect(status().isOk())
                .andExpect(jsonPath("$.mailDefaultPageSize").value(50))
                .andExpect(jsonPath("$.mailApiMaxPageSize").value(200))
                .andExpect(jsonPath("$.searchQueryMaxLength").value(256))
                .andExpect(jsonPath("$.contactDefaultPageSize").value(20))
                .andExpect(jsonPath("$.contactQueryMaxLength").value(100))
                .andExpect(jsonPath("$.contactAutocompleteDefaultLimit").value(10))
                .andExpect(jsonPath("$.contactAutocompleteMaxLimit").value(20))
                .andExpect(jsonPath("$.attachmentMaxBytes").value(10 * 1024 * 1024L))
                .andExpect(jsonPath("$.attachmentTotalMaxBytes").value(25 * 1024 * 1024L))
                .andExpect(jsonPath("$.largeAttachmentWarningBytes").value(5 * 1024 * 1024L));
    }
}
