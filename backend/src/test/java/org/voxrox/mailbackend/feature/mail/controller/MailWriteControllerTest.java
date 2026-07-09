package org.voxrox.mailbackend.feature.mail.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
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
import org.voxrox.mailbackend.exception.ResourceNotFoundException;
import org.voxrox.mailbackend.feature.mail.dto.MailRequest;
import org.voxrox.mailbackend.feature.mail.service.MailFacade;
import org.voxrox.mailbackend.feature.mail.service.SmtpMessageService;

import com.fasterxml.jackson.databind.ObjectMapper;

@WebMvcTest(controllers = MailWriteController.class, excludeAutoConfiguration = {SecurityAutoConfiguration.class,
        OAuth2ClientAutoConfiguration.class, OAuth2ClientWebSecurityAutoConfiguration.class})
@AutoConfigureMockMvc(addFilters = false)
class MailWriteControllerTest {

    @Autowired
    private MockMvc mockMvc;

    // @Autowired ObjectMapper fails in this @WebMvcTest setup (pre-existing —
    // JacksonAutoConfiguration does not provide the bean); AccountControllerTest
    // has the same issue. We instantiate manually; the default is enough for
    // DTO serialization in the test.
    private final ObjectMapper objectMapper = new ObjectMapper();

    @MockitoBean
    private MailFacade mailFacade;
    @MockitoBean
    private SmtpMessageService smtpService;
    @MockitoBean
    private InternalApiKeyProvider apiKeyProvider;

    private MailRequest validRequest() {
        return new MailRequest("to@x.cz", null, null, "Hello", "How are you?", List.of(), null, null);
    }

    @Test
    @DisplayName("POST send -> 202 Accepted with sendId, smtpService called asynchronously")
    void sendOk() throws Exception {
        MailRequest req = validRequest();

        mockMvc.perform(post("/api/v1/messages/account/7/send").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req))).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.sendId").isNotEmpty());

        verify(smtpService).sendEmailAsync(eq(7L), any(MailRequest.class), anyString());
    }

    @Test
    @DisplayName("POST send — non-positive accountId -> 400")
    void sendInvalidAccountId() throws Exception {
        mockMvc.perform(post("/api/v1/messages/account/0/send").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(validRequest()))).andExpect(status().isBadRequest());

        verifyNoInteractions(smtpService);
    }

    @Test
    @DisplayName("POST send — empty to/subject/body -> 400 ProblemDetail")
    void sendBodyValidationFail() throws Exception {
        MailRequest bad = new MailRequest("", null, null, "", "", List.of(), null, null);

        mockMvc.perform(post("/api/v1/messages/account/7/send").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(bad))).andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));

        verifyNoInteractions(smtpService);
    }

    @Test
    @DisplayName("POST send — subject over 500 characters -> 400")
    void sendSubjectTooLong() throws Exception {
        MailRequest bad = new MailRequest("to@x.cz", null, null, "a".repeat(501), "body", List.of(), null, null);

        mockMvc.perform(post("/api/v1/messages/account/7/send").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(bad))).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST send — body over the size cap -> 400 (payload bound)")
    void sendBodyTooLong() throws Exception {
        String hugeBody = "a".repeat(10 * 1024 * 1024 + 1);
        MailRequest bad = new MailRequest("to@x.cz", null, null, "subj", hugeBody, List.of(), null, null);

        mockMvc.perform(post("/api/v1/messages/account/7/send").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(bad))).andExpect(status().isBadRequest());

        verifyNoInteractions(smtpService);
    }

    @Test
    @DisplayName("POST send — more than 50 attachments -> 400 (attachment-count bound)")
    void sendTooManyAttachments() throws Exception {
        List<MailRequest.AttachmentRequest> many = java.util.stream.IntStream.rangeClosed(1, 51)
                .mapToObj(i -> new MailRequest.AttachmentRequest("f" + i + ".txt", "text/plain", "ZGF0YQ==")).toList();
        MailRequest bad = new MailRequest("to@x.cz", null, null, "subj", "body", many, null, null);

        mockMvc.perform(post("/api/v1/messages/account/7/send").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(bad))).andExpect(status().isBadRequest());

        verifyNoInteractions(smtpService);
    }

    @Test
    @DisplayName("POST send — invalid attachment (empty fileName) -> 400")
    void sendInvalidAttachment() throws Exception {
        MailRequest.AttachmentRequest badAtt = new MailRequest.AttachmentRequest("", "application/pdf", "ZGF0YQ==");
        MailRequest req = new MailRequest("to@x.cz", null, null, "subj", "body", List.of(badAtt), null, null);

        mockMvc.perform(post("/api/v1/messages/account/7/send").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req))).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST send — missing body -> 400 (HttpMessageNotReadable)")
    void sendMissingBody() throws Exception {
        mockMvc.perform(post("/api/v1/messages/account/7/send").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("GET reply -> 200 with MailRequest")
    void replyOk() throws Exception {
        MailRequest out = new MailRequest("orig@x.cz", null, null, "Re: Hello", "> How are you?", List.of(),
                "<msgid@x>", "<msgid@x>");
        when(mailFacade.prepareReply("abc123", false)).thenReturn(out);

        mockMvc.perform(get("/api/v1/messages/abc123/reply")).andExpect(status().isOk())
                .andExpect(jsonPath("$.to").value("orig@x.cz")).andExpect(jsonPath("$.subject").value("Re: Hello"))
                .andExpect(jsonPath("$.inReplyTo").value("<msgid@x>"));
    }

    @Test
    @DisplayName("GET reply?all=true -> passes the flag to the facade")
    void replyAll() throws Exception {
        when(mailFacade.prepareReply("abc123", true))
                .thenReturn(new MailRequest("a@x.cz,b@x.cz", null, null, "Re:", "q", List.of(), null, null));

        mockMvc.perform(get("/api/v1/messages/abc123/reply").param("all", "true")).andExpect(status().isOk());

        verify(mailFacade).prepareReply("abc123", true);
    }

    @Test
    @DisplayName("GET reply — message not found -> 404 RESOURCE_NOT_FOUND")
    void replyNotFound() throws Exception {
        when(mailFacade.prepareReply("missing", false)).thenThrow(new ResourceNotFoundException("Message not found"));

        mockMvc.perform(get("/api/v1/messages/missing/reply")).andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errorCode").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    @DisplayName("GET forward -> 200 with MailRequest")
    void forwardOk() throws Exception {
        MailRequest out = new MailRequest("", null, null, "Fwd: Hello", "---- Original ----\n...", List.of(), null,
                null);
        when(mailFacade.prepareForward("abc123")).thenReturn(out);

        mockMvc.perform(get("/api/v1/messages/abc123/forward")).andExpect(status().isOk())
                .andExpect(jsonPath("$.subject").value("Fwd: Hello"));
    }
}
