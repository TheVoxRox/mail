package org.voxrox.mailbackend.feature.mail.controller;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.voxrox.mailbackend.core.security.InternalApiKeyProvider;
import org.voxrox.mailbackend.exception.AccountNotFoundException;
import org.voxrox.mailbackend.feature.mail.dto.FolderResponse;
import org.voxrox.mailbackend.feature.mail.dto.FolderRole;
import org.voxrox.mailbackend.feature.mail.service.MailFacade;

@WebMvcTest(controllers = MailFolderController.class, excludeAutoConfiguration = {SecurityAutoConfiguration.class,
        OAuth2ClientAutoConfiguration.class, OAuth2ClientWebSecurityAutoConfiguration.class})
@AutoConfigureMockMvc(addFilters = false)
class MailFolderControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MailFacade mailFacade;
    @MockitoBean
    private InternalApiKeyProvider apiKeyProvider;

    @Test
    @DisplayName("GET folders → 200 s listou FolderResponse")
    void listFoldersOk() throws Exception {
        when(mailFacade.getFolders(7L)).thenReturn(List.of(new FolderResponse("Inbox", "INBOX", 3, FolderRole.INBOX),
                new FolderResponse("Sent", "Sent", 0, FolderRole.SENT)));

        mockMvc.perform(get("/api/v1/accounts/7/folders")).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2)).andExpect(jsonPath("$[0].folderRef").value("INBOX"))
                .andExpect(jsonPath("$[0].role").value("INBOX")).andExpect(jsonPath("$[0].unreadCount").value(3))
                .andExpect(jsonPath("$[1].role").value("SENT"));
    }

    @Test
    @DisplayName("GET folders — non-existing account -> 404 ACCOUNT_NOT_FOUND")
    void listFoldersAccountNotFound() throws Exception {
        when(mailFacade.getFolders(99L)).thenThrow(new AccountNotFoundException(99L));

        mockMvc.perform(get("/api/v1/accounts/99/folders")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("ACCOUNT_NOT_FOUND"));
    }

    @Test
    @DisplayName("GET folders — non-positive accountId -> 400, facade not called")
    void listFoldersInvalidAccountId() throws Exception {
        mockMvc.perform(get("/api/v1/accounts/0/folders")).andExpect(status().isBadRequest());

        verifyNoInteractions(mailFacade);
    }
}
