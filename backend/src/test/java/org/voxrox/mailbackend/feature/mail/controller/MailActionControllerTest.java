package org.voxrox.mailbackend.feature.mail.controller;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
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
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.voxrox.mailbackend.core.security.InternalApiKeyProvider;
import org.voxrox.mailbackend.exception.AccountNotFoundException;
import org.voxrox.mailbackend.exception.ResourceNotFoundException;
import org.voxrox.mailbackend.exception.ValidationException;
import org.voxrox.mailbackend.feature.account.entity.AccountEntity;
import org.voxrox.mailbackend.feature.account.service.AccountService;
import org.voxrox.mailbackend.feature.mail.dto.MessageFlag;
import org.voxrox.mailbackend.feature.mail.dto.MoveRequest;
import org.voxrox.mailbackend.feature.mail.service.MailFacade;
import org.voxrox.mailbackend.feature.mail.service.MailSyncService;

import com.fasterxml.jackson.databind.ObjectMapper;

@WebMvcTest(controllers = MailActionController.class, excludeAutoConfiguration = {SecurityAutoConfiguration.class,
        OAuth2ClientAutoConfiguration.class, OAuth2ClientWebSecurityAutoConfiguration.class})
@AutoConfigureMockMvc(addFilters = false)
class MailActionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MailFacade mailFacade;
    @MockitoBean
    private MailSyncService mailSyncService;
    @MockitoBean
    private AccountService accountService;
    @MockitoBean
    private InternalApiKeyProvider apiKeyProvider;

    @Test
    @DisplayName("PATCH flags type=seen value=true -> 204, facade called with MessageFlag.SEEN=true")
    void flagSeenTrue() throws Exception {
        mockMvc.perform(patch("/api/v1/messages/abc123/flags").param("type", "seen").param("value", "true"))
                .andExpect(status().isNoContent());

        verify(mailFacade).updateMessageFlag("abc123", MessageFlag.SEEN, true);
    }

    @Test
    @DisplayName("PATCH flags type=FLAGGED case-insensitive, value=false")
    void flagCaseInsensitive() throws Exception {
        mockMvc.perform(patch("/api/v1/messages/abc123/flags").param("type", "FLAGGED").param("value", "false"))
                .andExpect(status().isNoContent());

        verify(mailFacade).updateMessageFlag("abc123", MessageFlag.FLAGGED, false);
    }

    @Test
    @DisplayName("PATCH flags type=answered -> MessageFlag.ANSWERED")
    void flagAnswered() throws Exception {
        mockMvc.perform(patch("/api/v1/messages/abc123/flags").param("type", "answered").param("value", "true"))
                .andExpect(status().isNoContent());

        verify(mailFacade).updateMessageFlag("abc123", MessageFlag.ANSWERED, true);
    }

    @Test
    @DisplayName("PATCH flags — unknown type -> 400 VALIDATION_ERROR")
    void flagUnknownType() throws Exception {
        mockMvc.perform(patch("/api/v1/messages/abc123/flags").param("type", "bogus").param("value", "true"))
                .andExpect(status().isBadRequest()).andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"));

        verifyNoInteractions(mailFacade);
    }

    @Test
    @DisplayName("PATCH flags — empty type -> 400 (ConstraintViolation @NotBlank)")
    void flagEmptyType() throws Exception {
        mockMvc.perform(patch("/api/v1/messages/abc123/flags").param("type", "").param("value", "true"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("DELETE a message -> 204, moveToTrash called")
    void deleteMessageOk() throws Exception {
        mockMvc.perform(delete("/api/v1/messages/abc123")).andExpect(status().isNoContent());

        verify(mailFacade).moveToTrash("abc123");
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @DisplayName("POST /move -> 204, facade calls moveToFolder with folderRef")
    void moveOk() throws Exception {
        mockMvc.perform(post("/api/v1/messages/abc123/move").contentType(MediaType.APPLICATION_JSON)
                .content(MAPPER.writeValueAsString(new MoveRequest("[Gmail]/Archive"))))
                .andExpect(status().isNoContent());

        verify(mailFacade).moveToFolder("abc123", "[Gmail]/Archive");
    }

    @Test
    @DisplayName("POST /move — empty folderRef -> 400 (Bean Validation)")
    void moveEmptyTarget() throws Exception {
        mockMvc.perform(post("/api/v1/messages/abc123/move").contentType(MediaType.APPLICATION_JSON)
                .content(MAPPER.writeValueAsString(new MoveRequest("")))).andExpect(status().isBadRequest());

        verifyNoInteractions(mailFacade);
    }

    @Test
    @DisplayName("POST /move — missing body -> 400")
    void moveMissingBody() throws Exception {
        mockMvc.perform(post("/api/v1/messages/abc123/move").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(mailFacade);
    }

    @Test
    @DisplayName("POST /move — facade throws ResourceNotFoundException -> 404")
    void moveNotFound() throws Exception {
        doThrow(new ResourceNotFoundException("Target folder does not exist: X")).when(mailFacade)
                .moveToFolder(eq("abc123"), eq("X"));

        mockMvc.perform(post("/api/v1/messages/abc123/move").contentType(MediaType.APPLICATION_JSON)
                .content(MAPPER.writeValueAsString(new MoveRequest("X")))).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    @DisplayName("POST /move — source == target throws ValidationException -> 400")
    void moveSameSourceTarget() throws Exception {
        doThrow(new ValidationException("Source and target folder are the same: INBOX")).when(mailFacade)
                .moveToFolder(eq("abc123"), eq("INBOX"));

        mockMvc.perform(post("/api/v1/messages/abc123/move").contentType(MediaType.APPLICATION_JSON)
                .content(MAPPER.writeValueAsString(new MoveRequest("INBOX")))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"));
    }

    @Test
    @DisplayName("POST sync -> 202, sync called with the entity from AccountService")
    void triggerSyncOk() throws Exception {
        AccountEntity account = new AccountEntity();
        account.setId(7L);
        when(accountService.getAccountOrThrow(7L)).thenReturn(account);

        mockMvc.perform(post("/api/v1/messages/account/7/sync")).andExpect(status().isAccepted());

        verify(mailSyncService).syncAllFolders(eq(account));
    }

    @Test
    @DisplayName("POST sync — non-existing account -> 404, sync is not called")
    void triggerSyncAccountNotFound() throws Exception {
        when(accountService.getAccountOrThrow(99L)).thenThrow(new AccountNotFoundException(99L));

        mockMvc.perform(post("/api/v1/messages/account/99/sync")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("ACCOUNT_NOT_FOUND"));

        verifyNoInteractions(mailSyncService);
    }

    @Test
    @DisplayName("POST sync — non-positive accountId -> 400, no interactions")
    void triggerSyncInvalidAccountId() throws Exception {
        mockMvc.perform(post("/api/v1/messages/account/0/sync")).andExpect(status().isBadRequest());

        verifyNoInteractions(accountService, mailSyncService);
    }
}
