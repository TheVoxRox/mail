package org.voxrox.mailbackend.feature.contact.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
import org.voxrox.mailbackend.exception.ContactLabelNotFoundException;
import org.voxrox.mailbackend.exception.ContactNotFoundException;
import org.voxrox.mailbackend.exception.DuplicateContactLabelException;
import org.voxrox.mailbackend.exception.ValidationException;
import org.voxrox.mailbackend.feature.contact.dto.ContactLabelAssignmentRequest;
import org.voxrox.mailbackend.feature.contact.dto.ContactLabelAssignmentResponse;
import org.voxrox.mailbackend.feature.contact.dto.ContactLabelCreateRequest;
import org.voxrox.mailbackend.feature.contact.dto.ContactLabelResponse;
import org.voxrox.mailbackend.feature.contact.dto.ContactLabelUpdateRequest;
import org.voxrox.mailbackend.feature.contact.service.ContactLabelService;

import com.fasterxml.jackson.databind.ObjectMapper;

@WebMvcTest(controllers = ContactLabelController.class, excludeAutoConfiguration = {SecurityAutoConfiguration.class,
        OAuth2ClientAutoConfiguration.class, OAuth2ClientWebSecurityAutoConfiguration.class})
@AutoConfigureMockMvc(addFilters = false)
class ContactLabelControllerTest {

    private static final Long ACCOUNT_ID = 5L;
    private static final Long LABEL_ID = 7L;

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @MockitoBean
    private ContactLabelService contactLabelService;
    @MockitoBean
    private InternalApiKeyProvider apiKeyProvider;

    @Test
    @DisplayName("GET / → 200 with the account's labels")
    void listLabels() throws Exception {
        when(contactLabelService.listLabels())
                .thenReturn(List.of(new ContactLabelResponse(1L, "Clients"), new ContactLabelResponse(2L, "Family")));

        mockMvc.perform(get("/api/v1/contact-labels")).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2)).andExpect(jsonPath("$[0].name").value("Clients"))
                .andExpect(jsonPath("$[1].id").value(2));
    }

    @Test
    @DisplayName("POST / → 201 with a Location header")
    void createLabel() throws Exception {
        when(contactLabelService.createLabel(any())).thenReturn(new ContactLabelResponse(LABEL_ID, "Family"));

        mockMvc.perform(post("/api/v1/contact-labels").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new ContactLabelCreateRequest("Family"))))
                .andExpect(status().isCreated()).andExpect(header().string("Location", "/api/v1/contact-labels/7"))
                .andExpect(jsonPath("$.name").value("Family"));
    }

    @Test
    @DisplayName("POST / with a blank name → 400, the service is never called")
    void createLabelBlankName() throws Exception {
        mockMvc.perform(post("/api/v1/contact-labels").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new ContactLabelCreateRequest(""))))
                .andExpect(status().isBadRequest());
        verify(contactLabelService, never()).createLabel(any());
    }

    @Test
    @DisplayName("POST / with a name over 60 chars → 400")
    void createLabelTooLong() throws Exception {
        mockMvc.perform(post("/api/v1/contact-labels").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new ContactLabelCreateRequest("x".repeat(61)))))
                .andExpect(status().isBadRequest());
        verify(contactLabelService, never()).createLabel(any());
    }

    @Test
    @DisplayName("POST / with a duplicate name → 409 CONTACT_LABEL_DUPLICATE")
    void createLabelDuplicate() throws Exception {
        when(contactLabelService.createLabel(any())).thenThrow(new DuplicateContactLabelException("Family"));

        mockMvc.perform(post("/api/v1/contact-labels").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new ContactLabelCreateRequest("Family"))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.errorCode").value("CONTACT_LABEL_DUPLICATE"));
    }

    @Test
    @DisplayName("PATCH /{labelId} → 200 with the renamed label")
    void renameLabel() throws Exception {
        when(contactLabelService.renameLabel(eq(LABEL_ID), any()))
                .thenReturn(new ContactLabelResponse(LABEL_ID, "Clients"));

        mockMvc.perform(patch("/api/v1/contact-labels/{lid}", LABEL_ID).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new ContactLabelUpdateRequest("Clients"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Clients"));
    }

    @Test
    @DisplayName("PATCH /{labelId} of an unknown label → 404 CONTACT_LABEL_NOT_FOUND")
    void renameUnknownLabel() throws Exception {
        when(contactLabelService.renameLabel(eq(99L), any())).thenThrow(new ContactLabelNotFoundException(99L));

        mockMvc.perform(patch("/api/v1/contact-labels/{lid}", 99L).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new ContactLabelUpdateRequest("X"))))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.errorCode").value("CONTACT_LABEL_NOT_FOUND"));
    }

    @Test
    @DisplayName("DELETE /{labelId} → 204")
    void deleteLabel() throws Exception {
        mockMvc.perform(delete("/api/v1/contact-labels/{lid}", LABEL_ID)).andExpect(status().isNoContent());
        verify(contactLabelService).deleteLabel(LABEL_ID);
    }

    @Test
    @DisplayName("DELETE with a non-positive labelId → 400")
    void deleteLabelInvalidId() throws Exception {
        mockMvc.perform(delete("/api/v1/contact-labels/{lid}", 0L)).andExpect(status().isBadRequest());
        verify(contactLabelService, never()).deleteLabel(anyLong());
    }

    @Test
    @DisplayName("POST /assignments → 200 with total and changed")
    void assignLabels() throws Exception {
        when(contactLabelService.assignLabels(any())).thenReturn(new ContactLabelAssignmentResponse(3, 2));

        var req = new ContactLabelAssignmentRequest(List.of(10L, 11L, 12L), List.of(LABEL_ID), null);
        mockMvc.perform(post("/api/v1/contact-labels/assignments").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req))).andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(3)).andExpect(jsonPath("$.changed").value(2));
    }

    @Test
    @DisplayName("POST /assignments with an empty contact list → 400")
    void assignLabelsEmptyContacts() throws Exception {
        var req = new ContactLabelAssignmentRequest(List.of(), List.of(LABEL_ID), null);
        mockMvc.perform(post("/api/v1/contact-labels/assignments").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req))).andExpect(status().isBadRequest());
        verify(contactLabelService, never()).assignLabels(any());
    }

    @Test
    @DisplayName("POST /assignments over 100 contacts → 400")
    void assignLabelsTooManyContacts() throws Exception {
        List<Long> ids = java.util.stream.LongStream.rangeClosed(1, 101).boxed().toList();
        var req = new ContactLabelAssignmentRequest(ids, List.of(LABEL_ID), null);
        mockMvc.perform(post("/api/v1/contact-labels/assignments").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req))).andExpect(status().isBadRequest());
        verify(contactLabelService, never()).assignLabels(any());
    }

    @Test
    @DisplayName("POST /assignments with neither add nor remove → 400 from the service")
    void assignLabelsNothingToDo() throws Exception {
        when(contactLabelService.assignLabels(any()))
                .thenThrow(new ValidationException("nothing to do", "validation.contactLabel.assignmentEmpty"));

        var req = new ContactLabelAssignmentRequest(List.of(10L), null, null);
        mockMvc.perform(post("/api/v1/contact-labels/assignments").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"));
    }

    @Test
    @DisplayName("POST /assignments referencing a foreign contact → 404 CONTACT_NOT_FOUND")
    void assignLabelsUnknownContact() throws Exception {
        when(contactLabelService.assignLabels(any())).thenThrow(new ContactNotFoundException(99L));

        var req = new ContactLabelAssignmentRequest(List.of(99L), List.of(LABEL_ID), null);
        mockMvc.perform(post("/api/v1/contact-labels/assignments").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req))).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("CONTACT_NOT_FOUND"));
    }
}
