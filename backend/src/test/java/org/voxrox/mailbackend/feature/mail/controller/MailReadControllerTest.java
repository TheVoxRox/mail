package org.voxrox.mailbackend.feature.mail.controller;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.voxrox.mailbackend.core.config.MailClientProperties;
import org.voxrox.mailbackend.core.config.mail.SyncProperties;
import org.voxrox.mailbackend.core.security.InternalApiKeyProvider;
import org.voxrox.mailbackend.exception.ResourceNotFoundException;
import org.voxrox.mailbackend.feature.mail.dto.MailContentResponse;
import org.voxrox.mailbackend.feature.mail.dto.MailDetailResponse;
import org.voxrox.mailbackend.feature.mail.dto.MailSummaryResponse;
import org.voxrox.mailbackend.feature.mail.dto.ThreadResponse;
import org.voxrox.mailbackend.feature.mail.service.MailFacade;

@WebMvcTest(controllers = MailReadController.class, excludeAutoConfiguration = {SecurityAutoConfiguration.class,
        OAuth2ClientAutoConfiguration.class, OAuth2ClientWebSecurityAutoConfiguration.class})
@AutoConfigureMockMvc(addFilters = false)
class MailReadControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MailFacade mailFacade;

    // ApiKeyFilter is @Component -> WebMvcTest wants to construct it even when
    // filters are disabled.
    @MockitoBean
    private InternalApiKeyProvider apiKeyProvider;

    /**
     * MailClientProperties is @ConfigurationProperties; auto-binding would require
     * complete mail.client.* in properties. A mock + real SyncProperties is
     * simpler, providing the three values the controller actually reads.
     */
    @MockitoBean
    private MailClientProperties mailProps;

    @BeforeEach
    void stubMailProps() {
        SyncProperties sync = new SyncProperties(100, 200, Duration.ofMinutes(5), Duration.ofSeconds(10), 50, 30, 300,
                4, 256, 200, Duration.ofMinutes(30), Duration.ofSeconds(30));
        when(mailProps.sync()).thenReturn(sync);
    }

    private MailSummaryResponse summary(long id, String subject) {
        return new MailSummaryResponse(id, "stable-" + id, "INBOX", subject, "from@x.cz", "to@x.cz",
                LocalDateTime.of(2026, 1, 1, 10, 0), false, false, false, false, null, 100L);
    }

    @Test
    @DisplayName("GET listing of messages -> 200 with Page")
    void listMessagesOk() throws Exception {
        Page<MailSummaryResponse> page = new PageImpl<>(List.of(summary(1, "Hello"), summary(2, "World")),
                PageRequest.of(0, 50), 2);
        when(mailFacade.getEmails(7L, "INBOX", 0, 50)).thenReturn(page);

        mockMvc.perform(get("/api/v1/messages/account/7/folder").param("folderRef", "INBOX")).andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.content[0].subject").value("Hello"))
                .andExpect(jsonPath("$.content[0].uid").doesNotExist());
    }

    @Test
    @DisplayName("GET listing with custom page/size honors the parameters")
    void listMessagesWithParams() throws Exception {
        when(mailFacade.getEmails(7L, "INBOX", 2, 10)).thenReturn(new PageImpl<>(List.of(), PageRequest.of(2, 10), 0));

        mockMvc.perform(get("/api/v1/messages/account/7/folder").param("folderRef", "INBOX").param("page", "2")
                .param("size", "10")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("GET listing supports a Gmail folderRef with a slash via the query parameter")
    void listMessagesWithSlashFolderRef() throws Exception {
        String folderRef = "[Gmail]/All Mail";
        when(mailFacade.getEmails(7L, folderRef, 1, 25))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(1, 25), 0));

        mockMvc.perform(get("/api/v1/messages/account/7/folder").param("folderRef", folderRef).param("page", "1")
                .param("size", "25")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("GET listing — size over apiMaxPageSize -> 400 (ValidationException)")
    void listMessagesSizeTooBig() throws Exception {
        mockMvc.perform(get("/api/v1/messages/account/7/folder").param("folderRef", "INBOX").param("size", "500"))
                .andExpect(status().isBadRequest()).andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"));
    }

    @Test
    @DisplayName("GET listing — non-positive accountId -> 400 (ConstraintViolation)")
    void listMessagesInvalidAccountId() throws Exception {
        mockMvc.perform(get("/api/v1/messages/account/0/folder").param("folderRef", "INBOX"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("GET listing — negative page -> 400")
    void listMessagesNegativePage() throws Exception {
        mockMvc.perform(get("/api/v1/messages/account/7/folder").param("folderRef", "INBOX").param("page", "-1"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("GET listing — size=0 -> 400 (@Min(1))")
    void listMessagesZeroSize() throws Exception {
        mockMvc.perform(get("/api/v1/messages/account/7/folder").param("folderRef", "INBOX").param("size", "0"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("GET search -> 200, query is trimmed (strip)")
    void searchOk() throws Exception {
        when(mailFacade.searchEmails(eq(7L), eq("hello"), anyInt(), anyInt()))
                .thenReturn(new PageImpl<>(List.of(summary(1, "re: hello")), PageRequest.of(0, 50), 1));

        mockMvc.perform(get("/api/v1/messages/account/7/search").param("q", "  hello  ")).andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].subject").value("re: hello"));
    }

    @Test
    @DisplayName("GET search — empty query -> 400 VALIDATION_ERROR")
    void searchEmptyQuery() throws Exception {
        mockMvc.perform(get("/api/v1/messages/account/7/search").param("q", "   ")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"));
    }

    @Test
    @DisplayName("GET search — query too long -> 400")
    void searchQueryTooLong() throws Exception {
        String huge = "a".repeat(300);
        mockMvc.perform(get("/api/v1/messages/account/7/search").param("q", huge)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"));
    }

    @Test
    @DisplayName("GET detail → 200")
    void detailOk() throws Exception {
        MailDetailResponse dto = new MailDetailResponse("abc123", 42L, "subj", "from@x.cz", "to@x.cz", null, null,
                "<p>body</p>", LocalDateTime.of(2026, 1, 1, 10, 0), true, false, false, "<msgid>", null, null, false,
                List.of(), null, null);
        when(mailFacade.getEmailDetailByStableId("abc123")).thenReturn(dto);

        mockMvc.perform(get("/api/v1/messages/abc123")).andExpect(status().isOk())
                .andExpect(jsonPath("$.stableId").value("abc123")).andExpect(jsonPath("$.subject").value("subj"))
                .andExpect(jsonPath("$.uid").doesNotExist());
    }

    @Test
    @DisplayName("GET detail — not found -> 404 ProblemDetail")
    void detailNotFound() throws Exception {
        when(mailFacade.getEmailDetailByStableId("missing"))
                .thenThrow(new ResourceNotFoundException("Message missing not found"));

        mockMvc.perform(get("/api/v1/messages/missing")).andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errorCode").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    @DisplayName("GET content -> 200 with body")
    void contentOk() throws Exception {
        when(mailFacade.getMessageContentOnly("abc123"))
                .thenReturn(new MailContentResponse("<html>ok</html>", "sender@example.com", false));

        mockMvc.perform(get("/api/v1/messages/abc123/content")).andExpect(status().isOk())
                .andExpect(jsonPath("$.content").value("<html>ok</html>"))
                .andExpect(jsonPath("$.senderEmail").value("sender@example.com"))
                .andExpect(jsonPath("$.remoteImagesAllowedForSender").value(false));
    }

    @Test
    @DisplayName("GET attachment — Content-Disposition with both ASCII and UTF-8 name, PDF mime type")
    void downloadAttachmentOk() throws Exception {
        // The body is streamed via StreamingResponseBody (async) and MockMvc
        // does not produce it without a dispatch; we therefore verify the
        // headers set synchronously in the controller (content-type, disposition).
        when(mailFacade.getAttachment(eq("abc123"), eq("1.2")))
                .thenReturn(new ByteArrayInputStream("PDF-DATA".getBytes(java.nio.charset.StandardCharsets.UTF_8)));

        mockMvc.perform(get("/api/v1/messages/abc123/attachments/1.2").param("fileName", "report.pdf"))
                .andExpect(status().isOk()).andExpect(header().string("Content-Type", "application/pdf"))
                .andExpect(header().string("Content-Disposition",
                        "attachment; filename=\"report.pdf\"; filename*=UTF-8''report.pdf"));
    }

    @Test
    @DisplayName("GET attachment — without fileName -> fallback 'unnamed' + octet-stream")
    void downloadAttachmentDefaultName() throws Exception {
        when(mailFacade.getAttachment(anyString(), anyString()))
                .thenReturn(new ByteArrayInputStream(new byte[]{1, 2, 3}));

        mockMvc.perform(get("/api/v1/messages/abc123/attachments/1.2")).andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",
                        "attachment; filename=\"unnamed\"; filename*=UTF-8''unnamed"))
                .andExpect(content().contentType(MediaType.APPLICATION_OCTET_STREAM));
    }

    @Test
    @DisplayName("GET attachment — UTF-8 name is URL-encoded in the RFC 5987 portion")
    void downloadAttachmentUtf8Name() throws Exception {
        when(mailFacade.getAttachment(anyString(), anyString())).thenReturn(new ByteArrayInputStream(new byte[0]));

        mockMvc.perform(get("/api/v1/messages/abc123/attachments/1.2").param("fileName", "soubor šťávnatý.pdf"))
                .andExpect(status().isOk()).andExpect(header().string("Content-Disposition", org.hamcrest.Matchers
                        .containsString("filename*=UTF-8''soubor%20%C5%A1%C5%A5%C3%A1vnat%C3%BD.pdf")));
    }

    @Test
    @DisplayName("GET /account/{id}/threads/{tid} -> 200 with thread payload")
    void getThreadOk() throws Exception {
        ThreadResponse resp = new ThreadResponse("8b4-uuid", "<root@x.cz>", 2, 1,
                List.of(summary(1, "Hi"), summary(2, "Re: Hi")));
        when(mailFacade.getThread(7L, "8b4-uuid")).thenReturn(resp);

        mockMvc.perform(get("/api/v1/messages/account/7/threads/8b4-uuid")).andExpect(status().isOk())
                .andExpect(jsonPath("$.threadId").value("8b4-uuid"))
                .andExpect(jsonPath("$.rootMessageId").value("<root@x.cz>"))
                .andExpect(jsonPath("$.participantsTotal").value(2)).andExpect(jsonPath("$.unreadCount").value(1))
                .andExpect(jsonPath("$.messages.length()").value(2));
    }

    @Test
    @DisplayName("GET /account/{id}/threads/{tid} — non-positive accountId -> 400")
    void getThreadInvalidAccountId() throws Exception {
        mockMvc.perform(get("/api/v1/messages/account/0/threads/8b4-uuid")).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("GET /account/{id}/threads/{tid} — service throws ResourceNotFoundException -> 404")
    void getThreadNotFound() throws Exception {
        when(mailFacade.getThread(7L, "unknown"))
                .thenThrow(new org.voxrox.mailbackend.exception.ResourceNotFoundException("Thread not found: unknown"));

        mockMvc.perform(get("/api/v1/messages/account/7/threads/unknown")).andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errorCode").value("RESOURCE_NOT_FOUND"));
    }
}
