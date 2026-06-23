package org.voxrox.mailbackend.feature.mail.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
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
import org.voxrox.mailbackend.exception.ResourceNotFoundException;
import org.voxrox.mailbackend.feature.account.entity.AccountEntity;
import org.voxrox.mailbackend.feature.account.service.AccountService;
import org.voxrox.mailbackend.feature.mail.service.ThreadingBackfillService;

@WebMvcTest(controllers = ThreadingInternalController.class, excludeAutoConfiguration = {
        SecurityAutoConfiguration.class, OAuth2ClientAutoConfiguration.class,
        OAuth2ClientWebSecurityAutoConfiguration.class})
@AutoConfigureMockMvc(addFilters = false)
class ThreadingInternalControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ThreadingBackfillService backfillService;
    @MockitoBean
    private AccountService accountService;
    @MockitoBean
    private InternalApiKeyProvider apiKeyProvider;

    @Test
    @DisplayName("POST recompute -> 200 with the assigned count; backfill runs on the resolved account")
    void recomputeReturnsAssignedCount() throws Exception {
        AccountEntity account = new AccountEntity();
        when(accountService.getAccountOrThrow(5L)).thenReturn(account);
        when(backfillService.backfillAccount(account)).thenReturn(7);

        mockMvc.perform(post("/api/internal/threading/recompute").param("accountId", "5")).andExpect(status().isOk())
                .andExpect(content().string("7"));

        verify(backfillService).backfillAccount(account);
    }

    @Test
    @DisplayName("POST recompute without accountId -> 400 and the backfill never runs")
    void recomputeMissingAccountIdIsBadRequest() throws Exception {
        mockMvc.perform(post("/api/internal/threading/recompute")).andExpect(status().isBadRequest());

        verify(backfillService, never()).backfillAccount(any());
    }

    @Test
    @DisplayName("POST recompute for a missing account -> 404 and the backfill never runs")
    void recomputeUnknownAccountIsNotFound() throws Exception {
        when(accountService.getAccountOrThrow(99L)).thenThrow(new ResourceNotFoundException("Account not found: 99"));

        mockMvc.perform(post("/api/internal/threading/recompute").param("accountId", "99"))
                .andExpect(status().isNotFound());

        verify(backfillService, never()).backfillAccount(any());
    }
}
