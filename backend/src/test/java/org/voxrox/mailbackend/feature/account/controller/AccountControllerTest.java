package org.voxrox.mailbackend.feature.account.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

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
import org.voxrox.mailbackend.exception.AccountNotFoundException;
import org.voxrox.mailbackend.exception.ValidationException;
import org.voxrox.mailbackend.feature.account.dto.AccountConnectionTestRequest;
import org.voxrox.mailbackend.feature.account.dto.AccountConnectionTestResponse;
import org.voxrox.mailbackend.feature.account.dto.AccountCreateRequest;
import org.voxrox.mailbackend.feature.account.dto.AccountResponse;
import org.voxrox.mailbackend.feature.account.dto.AccountUpdateRequest;
import org.voxrox.mailbackend.feature.account.dto.MailServerSettings;
import org.voxrox.mailbackend.feature.account.service.AccountConnectionTestService;
import org.voxrox.mailbackend.feature.account.service.AccountService;
import org.voxrox.mailbackend.feature.auth.dto.AuthType;

import com.fasterxml.jackson.databind.ObjectMapper;

@WebMvcTest(controllers = AccountController.class, excludeAutoConfiguration = {SecurityAutoConfiguration.class,
        OAuth2ClientAutoConfiguration.class, OAuth2ClientWebSecurityAutoConfiguration.class})
@AutoConfigureMockMvc(addFilters = false)
class AccountControllerTest {

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @MockitoBean
    private AccountService accountService;

    @MockitoBean
    private AccountConnectionTestService accountConnectionTestService;

    @MockitoBean
    private InternalApiKeyProvider apiKeyProvider;

    private AccountResponse sampleResponse(Long id) {
        return new AccountResponse(id, "My Gmail", "a@b.cz", "Me", 1L, "Gmail", "imap.gmail.com", 993, true,
                "smtp.gmail.com", 465, true, "user", AuthType.PASSWORD, null, true, false, null, null);
    }

    private AccountResponse customResponse(Long id) {
        return new AccountResponse(id, "Custom Acc", "x@y.cz", "Me", null, "Custom", "imap.custom.cz", 143, false,
                "smtp.custom.cz", 587, false, "user", AuthType.PASSWORD, null, true, false, null, null);
    }

    @Test
    @DisplayName("GET /{id} returns AccountResponse")
    void getById() throws Exception {
        when(accountService.getAccountById(5L)).thenReturn(sampleResponse(5L));

        mockMvc.perform(get("/api/v1/accounts/5")).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(5))
                .andExpect(jsonPath("$.email").value("a@b.cz"));
    }

    @Test
    @DisplayName("GET /{id} mapuje AccountNotFoundException → 404 ProblemDetail")
    void getByIdNotFound() throws Exception {
        when(accountService.getAccountById(99L)).thenThrow(new AccountNotFoundException(99L));

        mockMvc.perform(get("/api/v1/accounts/99")).andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errorCode").value("ACCOUNT_NOT_FOUND"));
    }

    @Test
    @DisplayName("GET / returns the list")
    void list() throws Exception {
        when(accountService.listAllAccounts()).thenReturn(List.of(sampleResponse(1L), sampleResponse(2L)));

        mockMvc.perform(get("/api/v1/accounts")).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    @DisplayName("POST /test-connection verifies the connection without creating an account")
    void testConnection() throws Exception {
        AccountConnectionTestRequest req = new AccountConnectionTestRequest(null, "a@b.cz", 1L, null, null, "user",
                "secret");
        when(accountConnectionTestService.testConnection(any()))
                .thenReturn(new AccountConnectionTestResponse(true, true, "OK"));

        mockMvc.perform(post("/api/v1/accounts/test-connection").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req))).andExpect(status().isOk())
                .andExpect(jsonPath("$.imapOk").value(true)).andExpect(jsonPath("$.smtpOk").value(true));
    }

    @Test
    @DisplayName("POST /test-connection for a new account without password -> 400 from validation")
    void testConnectionWithoutPasswordForNewAccount() throws Exception {
        AccountConnectionTestRequest req = new AccountConnectionTestRequest(null, "a@b.cz", 1L, null, null, "user", "");

        mockMvc.perform(post("/api/v1/accounts/test-connection").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req))).andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    @DisplayName("POST / with providerId -> 201 with service response")
    void createAccountWithProvider() throws Exception {
        AccountCreateRequest req = new AccountCreateRequest("My Acc", "Me", "a@b.cz", 1L, null, null, "user", "secret");
        when(accountService.createAccount(any())).thenReturn(sampleResponse(10L));

        mockMvc.perform(post("/api/v1/accounts").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req))).andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(10)).andExpect(jsonPath("$.providerName").value("Gmail"));
    }

    @Test
    @DisplayName("POST / with custom imap+smtp (no providerId) -> 201, providerName='Custom'")
    void createAccountWithCustomServers() throws Exception {
        AccountCreateRequest req = new AccountCreateRequest("Custom Acc", "Me", "x@y.cz", null,
                new MailServerSettings("imap.custom.cz", 143, false),
                new MailServerSettings("smtp.custom.cz", 587, false), "user", "secret");
        when(accountService.createAccount(any())).thenReturn(customResponse(11L));

        mockMvc.perform(post("/api/v1/accounts").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req))).andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(11)).andExpect(jsonPath("$.providerId").doesNotExist())
                .andExpect(jsonPath("$.providerName").value("Custom"))
                .andExpect(jsonPath("$.imapHost").value("imap.custom.cz")).andExpect(jsonPath("$.smtpPort").value(587));
    }

    @Test
    @DisplayName("POST / without providerId and without custom config -> 400 from @AssertTrue")
    void createAccountMissingProviderAndCustom() throws Exception {
        AccountCreateRequest req = new AccountCreateRequest("My Acc", "Me", "a@b.cz", null, null, null, "user",
                "secret");

        mockMvc.perform(post("/api/v1/accounts").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req))).andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    @DisplayName("POST / with incomplete imap (missing port) -> 400 from @NotNull")
    void createAccountIncompleteImap() throws Exception {
        // Build the JSON manually so the validator fails on port=null —
        // a record constructor would reject null, but Jackson passes it to validation.
        String json = "{\"accountName\":\"Acc\",\"displayName\":\"Me\",\"email\":\"a@b.cz\","
                + "\"imap\":{\"host\":\"imap.x.cz\",\"useSsl\":true},"
                + "\"smtp\":{\"host\":\"smtp.x.cz\",\"port\":465,\"useSsl\":true},"
                + "\"username\":\"user\",\"password\":\"secret\"}";

        mockMvc.perform(post("/api/v1/accounts").contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    @DisplayName("POST / with invalid body -> 400 ProblemDetail (validation)")
    void createAccountValidationFail() throws Exception {
        AccountCreateRequest bad = new AccountCreateRequest("", "", "not-an-email", null, null, null, "", "");

        mockMvc.perform(post("/api/v1/accounts").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(bad))).andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    @DisplayName("DELETE /{id} -> 204, service is invoked")
    void deleteAccount() throws Exception {
        mockMvc.perform(delete("/api/v1/accounts/7")).andExpect(status().isNoContent());

        verify(accountService).deleteAccount(7L);
    }

    @Test
    @DisplayName("GET /{id} with non-positive ID -> 400 (ConstraintViolation)")
    void getByIdNegativeId() throws Exception {
        mockMvc.perform(get("/api/v1/accounts/0")).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Accept-Language cs localizes method-level validation")
    void validationUsesCzechAcceptLanguage() throws Exception {
        mockMvc.perform(get("/api/v1/accounts/0").header("Accept-Language", "cs")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail")
                        .value(org.hamcrest.Matchers.containsString("Hodnota musí být větší než 0.")))
                .andExpect(jsonPath("$.messageKey").value("error.validation"));
    }

    @Test
    @DisplayName("Accept-Language en localizes method-level validation")
    void validationUsesEnglishAcceptLanguage() throws Exception {
        mockMvc.perform(get("/api/v1/accounts/0").header("Accept-Language", "en")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail")
                        .value(org.hamcrest.Matchers.containsString("Value must be greater than 0.")))
                .andExpect(jsonPath("$.messageKey").value("error.validation"));
    }

    @Test
    @DisplayName("PUT /{id} with password on an OAuth2 account -> 400 + VALIDATION_ERROR")
    void putOnOAuth2RejectedAsValidationError() throws Exception {
        AccountUpdateRequest req = new AccountUpdateRequest("My Acc", "a@b.cz", "Me", null, 1L, null, null, "user",
                "stolenpassword", true);
        when(accountService.updateAccount(eq(5L), any()))
                .thenThrow(new ValidationException("OAuth2 account cannot be updated by changing password or username. "
                        + "To change credentials, go through the Google login flow again."));

        mockMvc.perform(put("/api/v1/accounts/5").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req))).andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("OAuth2")));
    }

}
