package org.voxrox.mailbackend.core.diagnostic;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration;
import org.springframework.boot.security.oauth2.client.autoconfigure.OAuth2ClientAutoConfiguration;
import org.springframework.boot.security.oauth2.client.autoconfigure.servlet.OAuth2ClientWebSecurityAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.voxrox.mailbackend.core.security.InternalApiKeyProvider;

@WebMvcTest(controllers = DiagnosticDumpController.class, excludeAutoConfiguration = {SecurityAutoConfiguration.class,
        OAuth2ClientAutoConfiguration.class, OAuth2ClientWebSecurityAutoConfiguration.class})
@AutoConfigureMockMvc(addFilters = false)
class DiagnosticDumpControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    DiagnosticDumpService diagnosticDumpService;

    @MockitoBean
    InternalApiKeyProvider apiKeyProvider;

    @Test
    @DisplayName("GET /api/internal/diagnostic-dump returns a ZIP as attachment")
    void getDiagnosticDumpReturnsZipAttachment() throws Exception {
        byte[] zipBytes = new byte[]{0x50, 0x4b, 0x03, 0x04};
        when(diagnosticDumpService.createDump()).thenReturn(zipBytes);

        mockMvc.perform(get("/api/internal/diagnostic-dump")).andExpect(status().isOk())
                .andExpect(content().contentType("application/zip"))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, containsString("mail-diagnostic-")))
                .andExpect(content().bytes(zipBytes));
    }
}
