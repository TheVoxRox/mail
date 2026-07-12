package org.voxrox.mailbackend.feature.mail.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration;
import org.springframework.boot.security.oauth2.client.autoconfigure.OAuth2ClientAutoConfiguration;
import org.springframework.boot.security.oauth2.client.autoconfigure.servlet.OAuth2ClientWebSecurityAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.voxrox.mailbackend.core.config.MailClientProperties;
import org.voxrox.mailbackend.core.config.mail.SyncProperties;
import org.voxrox.mailbackend.core.security.InternalApiKeyProvider;
import org.voxrox.mailbackend.exception.ValidationException;
import org.voxrox.mailbackend.feature.mail.dto.DraftRequest;
import org.voxrox.mailbackend.feature.mail.dto.MailSummaryResponse;
import org.voxrox.mailbackend.feature.mail.service.MailFacade;
import org.voxrox.mailbackend.feature.mail.service.SmtpMessageService;

import com.fasterxml.jackson.databind.ObjectMapper;

@WebMvcTest(controllers = DraftController.class, excludeAutoConfiguration = {SecurityAutoConfiguration.class,
        OAuth2ClientAutoConfiguration.class, OAuth2ClientWebSecurityAutoConfiguration.class})
@AutoConfigureMockMvc(addFilters = false)
class DraftControllerTest {

    @Autowired
    private MockMvc mockMvc;

    // @Autowired ObjectMapper nefunguje v tomto WebMvcTest setupu (viz
    // MailWriteControllerTest).
    private final ObjectMapper objectMapper = new ObjectMapper();

    @MockitoBean
    private SmtpMessageService smtpService;
    @MockitoBean
    private MailFacade mailFacade;
    @MockitoBean
    private MailClientProperties mailProps;
    @MockitoBean
    private InternalApiKeyProvider apiKeyProvider;

    @BeforeEach
    void stubProps() {
        SyncProperties sync = new SyncProperties(100, 200, Duration.ofMinutes(5), Duration.ofSeconds(10), 50, 30, 300,
                4, 256, 200, Duration.ofMinutes(30), Duration.ofSeconds(30));
        when(mailProps.sync()).thenReturn(sync);
    }

    private DraftRequest emptyDraft() {
        return new DraftRequest(null, null, null, null, null, List.of(), null, null);
    }

    @Test
    @DisplayName("POST draft → 202, saveDraftAsync s replaces=null")
    void saveDraftOk() throws Exception {
        DraftRequest req = new DraftRequest("to@x.cz", null, null, "Subj", "Body", List.of(), null, null);

        mockMvc.perform(post("/api/v1/accounts/7/drafts").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req))).andExpect(status().isAccepted());

        verify(smtpService).saveDraftAsync(eq(7L), any(DraftRequest.class), isNull());
    }

    @Test
    @DisplayName("POST draft?replaces=abc -> 202, replaces is passed through")
    void saveDraftReplaces() throws Exception {
        mockMvc.perform(post("/api/v1/accounts/7/drafts").param("replaces", "abc123")
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(emptyDraft())))
                .andExpect(status().isAccepted());

        verify(smtpService).saveDraftAsync(eq(7L), any(DraftRequest.class), eq("abc123"));
    }

    @Test
    @DisplayName("POST draft — empty fields pass (all are optional)")
    void saveDraftAllNullFieldsOk() throws Exception {
        mockMvc.perform(post("/api/v1/accounts/7/drafts").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(emptyDraft()))).andExpect(status().isAccepted());
    }

    @Test
    @DisplayName("POST draft — subject over 500 characters -> 400")
    void saveDraftSubjectTooLong() throws Exception {
        DraftRequest bad = new DraftRequest(null, null, null, "a".repeat(501), null, List.of(), null, null);

        mockMvc.perform(post("/api/v1/accounts/7/drafts").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(bad))).andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));

        verifyNoInteractions(smtpService);
    }

    @Test
    @DisplayName("POST draft — non-positive accountId -> 400")
    void saveDraftInvalidAccountId() throws Exception {
        mockMvc.perform(post("/api/v1/accounts/0/drafts").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(emptyDraft()))).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST draft — replaces over 128 characters -> 400")
    void saveDraftReplacesTooLong() throws Exception {
        mockMvc.perform(post("/api/v1/accounts/7/drafts").param("replaces", "a".repeat(129))
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(emptyDraft())))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("GET drafts → 200, default page=0 size=50 z mailProps")
    void listDraftsDefault() throws Exception {
        MailSummaryResponse s = new MailSummaryResponse(1L, "d1", "Drafts", "Draft", "me@x.cz", "to@x.cz",
                LocalDateTime.of(2026, 1, 1, 10, 0), false, false, false, false, null, 1L);
        when(mailFacade.listDrafts(7L, 0, 50)).thenReturn(new PageImpl<>(List.of(s), PageRequest.of(0, 50), 1));

        mockMvc.perform(get("/api/v1/accounts/7/drafts")).andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].stableId").value("d1"));
    }

    @Test
    @DisplayName("GET drafts — custom page/size propagates to the facade")
    void listDraftsCustomParams() throws Exception {
        when(mailFacade.listDrafts(7L, 2, 10)).thenReturn(new PageImpl<>(List.of(), PageRequest.of(2, 10), 0));

        mockMvc.perform(get("/api/v1/accounts/7/drafts").param("page", "2").param("size", "10"))
                .andExpect(status().isOk());

        verify(mailFacade).listDrafts(7L, 2, 10);
    }

    @Test
    @DisplayName("GET drafts — size over apiMaxPageSize -> 400 VALIDATION_ERROR")
    void listDraftsSizeTooBig() throws Exception {
        mockMvc.perform(get("/api/v1/accounts/7/drafts").param("size", "500")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"));
    }

    @Test
    @DisplayName("GET drafts — non-positive accountId -> 400")
    void listDraftsInvalidAccountId() throws Exception {
        mockMvc.perform(get("/api/v1/accounts/0/drafts")).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST draft/{stableId}/send → 202 with sendId, verified then sendDraftAsync is invoked")
    void sendDraftOk() throws Exception {
        mockMvc.perform(post("/api/v1/accounts/7/drafts/abc123/send")).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.sendId").isNotEmpty());

        // The draft is validated synchronously before the async send is dispatched.
        verify(mailFacade).verifyDraftForSend(7L, "abc123");
        verify(smtpService).sendDraftAsync(eq(7L), eq("abc123"), anyString());
    }

    @Test
    @DisplayName("POST draft/{stableId}/send — stableId is not a draft -> 400, no async send")
    void sendDraftRejectsNonDraft() throws Exception {
        when(mailFacade.verifyDraftForSend(7L, "abc123")).thenThrow(new ValidationException(
                "Message abc123 is not in the Drafts folder", "validation.draft.notInDrafts", "abc123"));

        mockMvc.perform(post("/api/v1/accounts/7/drafts/abc123/send")).andExpect(status().isBadRequest());

        // A wrong stableId must not reach the async path that re-sends and expunges it.
        verify(smtpService, never()).sendDraftAsync(anyLong(), anyString(), anyString());
    }

    @Test
    @DisplayName("POST draft/{stableId}/send — non-positive accountId -> 400")
    void sendDraftInvalidAccountId() throws Exception {
        mockMvc.perform(post("/api/v1/accounts/0/drafts/abc123/send")).andExpect(status().isBadRequest());

        verifyNoInteractions(smtpService);
    }

    @Test
    @DisplayName("POST draft/{stableId}/send — stableId over 128 characters -> 400")
    void sendDraftStableIdTooLong() throws Exception {
        mockMvc.perform(post("/api/v1/accounts/7/drafts/" + "a".repeat(129) + "/send"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(smtpService);
    }
}
