package org.voxrox.mailbackend.feature.mail.controller;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.voxrox.mailbackend.core.security.InternalApiKeyProvider;
import org.voxrox.mailbackend.feature.mail.service.SseNotificationService;

@WebMvcTest(controllers = NotificationController.class, excludeAutoConfiguration = {SecurityAutoConfiguration.class,
        OAuth2ClientAutoConfiguration.class, OAuth2ClientWebSecurityAutoConfiguration.class})
@AutoConfigureMockMvc(addFilters = false)
class NotificationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SseNotificationService sseNotificationService;
    @MockitoBean
    private InternalApiKeyProvider apiKeyProvider;

    @Test
    @DisplayName("GET /stream -> 200, async started, service.register() called")
    void streamReturns200() throws Exception {
        when(sseNotificationService.register()).thenReturn(new SseEmitter(30_000L));

        mockMvc.perform(get("/api/v1/notifications/stream").accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isOk()).andExpect(request().asyncStarted());

        verify(sseNotificationService).register();
    }

    @Test
    @DisplayName("GET /stream without Accept header -> 200 (produces is enforced)")
    void streamWithoutAcceptHeader() throws Exception {
        when(sseNotificationService.register()).thenReturn(new SseEmitter(30_000L));

        mockMvc.perform(get("/api/v1/notifications/stream")).andExpect(status().isOk());

        verify(sseNotificationService).register();
    }
}
