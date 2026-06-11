package org.voxrox.mailbackend.feature.contact.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.voxrox.mailbackend.core.config.ClientConfigProperties;
import org.voxrox.mailbackend.core.config.MailClientProperties;
import org.voxrox.mailbackend.core.config.mail.SyncProperties;
import org.voxrox.mailbackend.core.security.InternalApiKeyProvider;
import org.voxrox.mailbackend.exception.ContactNotFoundException;
import org.voxrox.mailbackend.exception.DuplicateContactException;
import org.voxrox.mailbackend.exception.ResourceNotFoundException;
import org.voxrox.mailbackend.exception.ValidationException;
import org.voxrox.mailbackend.feature.contact.EmailLabel;
import org.voxrox.mailbackend.feature.contact.dto.BulkContactCreateRequest;
import org.voxrox.mailbackend.feature.contact.dto.BulkContactCreateResponse;
import org.voxrox.mailbackend.feature.contact.dto.BulkContactCreateResponse.BulkContactCreateResult;
import org.voxrox.mailbackend.feature.contact.dto.BulkContactDeleteRequest;
import org.voxrox.mailbackend.feature.contact.dto.BulkContactDeleteResponse;
import org.voxrox.mailbackend.feature.contact.dto.BulkContactDeleteResponse.BulkContactDeleteResult;
import org.voxrox.mailbackend.feature.contact.dto.ContactAutocompleteResponse;
import org.voxrox.mailbackend.feature.contact.dto.ContactCreateRequest;
import org.voxrox.mailbackend.feature.contact.dto.ContactEmailRequest;
import org.voxrox.mailbackend.feature.contact.dto.ContactEmailResponse;
import org.voxrox.mailbackend.feature.contact.dto.ContactMergeRequest;
import org.voxrox.mailbackend.feature.contact.dto.ContactPatchRequest;
import org.voxrox.mailbackend.feature.contact.dto.ContactResponse;
import org.voxrox.mailbackend.feature.contact.dto.ContactUpdateRequest;
import org.voxrox.mailbackend.feature.contact.service.ContactBulkService;
import org.voxrox.mailbackend.feature.contact.service.ContactService;

import com.fasterxml.jackson.databind.ObjectMapper;

@WebMvcTest(controllers = ContactController.class, excludeAutoConfiguration = {SecurityAutoConfiguration.class,
        OAuth2ClientAutoConfiguration.class, OAuth2ClientWebSecurityAutoConfiguration.class})
@AutoConfigureMockMvc(addFilters = false)
class ContactControllerTest {

    private static final Long ACCOUNT_ID = 5L;
    private static final Long CONTACT_ID = 42L;

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @MockitoBean
    private ContactService contactService;
    @MockitoBean
    private ContactBulkService contactBulkService;
    @MockitoBean
    private MailClientProperties mailProps;
    @MockitoBean
    private ClientConfigProperties clientConfigProps;
    @MockitoBean
    private InternalApiKeyProvider apiKeyProvider;

    @BeforeEach
    void stubProps() {
        SyncProperties sync = new SyncProperties(100, 200, Duration.ofMinutes(5), Duration.ofSeconds(10), 50, 30, 300,
                4, 256, 200, Duration.ofMinutes(30), Duration.ofSeconds(30));
        when(mailProps.sync()).thenReturn(sync);
        when(clientConfigProps.contactDefaultPageSize()).thenReturn(20);
        when(clientConfigProps.contactQueryMaxLength()).thenReturn(100);
        when(clientConfigProps.contactAutocompleteDefaultLimit()).thenReturn(10);
        when(clientConfigProps.contactAutocompleteMaxLimit()).thenReturn(20);
    }

    private ContactResponse sample(Long id, String email) {
        return new ContactResponse(id, List.of(new ContactEmailResponse(1L, email, EmailLabel.WORK, true)), "Alice",
                "Liddell", "VIP", LocalDateTime.of(2026, 4, 15, 10, 0), LocalDateTime.of(2026, 4, 15, 10, 0));
    }

    private ContactEmailRequest emailReq(String email) {
        return new ContactEmailRequest(email, null);
    }

    @Test
    @DisplayName("GET / → 200 s paginated seznamem (default page/size)")
    void listContacts() throws Exception {
        when(contactService.listContacts(eq(ACCOUNT_ID), eq(0), eq(20), eq(null), eq(null)))
                .thenReturn(new PageImpl<>(List.of(sample(10L, "a@x.cz"), sample(11L, "b@x.cz"))));

        mockMvc.perform(get("/api/v1/accounts/{aid}/contacts", ACCOUNT_ID)).andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.content[0].emails[0].email").value("a@x.cz"))
                .andExpect(jsonPath("$.content[0].emails[0].primary").value(true))
                .andExpect(jsonPath("$.page").value(0)).andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.first").value(true)).andExpect(jsonPath("$.last").value(true));
    }

    @Test
    @DisplayName("GET /?q=alice -> delegated to search")
    void searchContacts() throws Exception {
        when(contactService.searchContacts(eq(ACCOUNT_ID), eq("alice"), eq(0), eq(20), eq(null), eq(null)))
                .thenReturn(new PageImpl<>(List.of(sample(10L, "alice@x.cz"))));

        mockMvc.perform(get("/api/v1/accounts/{aid}/contacts", ACCOUNT_ID).param("q", "alice"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content[0].emails[0].email").value("alice@x.cz"));

        verify(contactService, never()).listContacts(any(), anyInt(), anyInt(), any(), any());
    }

    @Test
    @DisplayName("GET /?sort=name&label=WORK -> delegated to listContacts with parameters")
    void listContactsWithSortAndLabel() throws Exception {
        when(contactService.listContacts(eq(ACCOUNT_ID), eq(0), eq(20), eq("name"), eq(EmailLabel.WORK)))
                .thenReturn(new PageImpl<>(List.of(sample(10L, "alice.work@x.cz"))));

        mockMvc.perform(get("/api/v1/accounts/{aid}/contacts", ACCOUNT_ID).param("sort", "name").param("label", "WORK"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content.length()").value(1));
    }

    @Test
    @DisplayName("GET /?size=500 -> 400 (exceeds apiMaxPageSize=200)")
    void listContactsSizeOverCap() throws Exception {
        mockMvc.perform(get("/api/v1/accounts/{aid}/contacts", ACCOUNT_ID).param("size", "500"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    @DisplayName("GET /?page=-1 -> 400 (@Min(0) guard)")
    void listContactsNegativePage() throws Exception {
        mockMvc.perform(get("/api/v1/accounts/{aid}/contacts", ACCOUNT_ID).param("page", "-1"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("GET / with accountId=0 -> 400 (@Positive guard)")
    void listContactsInvalidAccount() throws Exception {
        mockMvc.perform(get("/api/v1/accounts/0/contacts")).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("GET /{id} -> 200 with detail")
    void getContact() throws Exception {
        when(contactService.getContact(ACCOUNT_ID, CONTACT_ID)).thenReturn(sample(CONTACT_ID, "a@x.cz"));

        mockMvc.perform(get("/api/v1/accounts/{aid}/contacts/{cid}", ACCOUNT_ID, CONTACT_ID)).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(CONTACT_ID)).andExpect(jsonPath("$.emails[0].email").value("a@x.cz"))
                .andExpect(jsonPath("$.emails[0].label").value("WORK"));
    }

    @Test
    @DisplayName("GET /{id} missing → 404 CONTACT_NOT_FOUND")
    void getContactMissing() throws Exception {
        when(contactService.getContact(ACCOUNT_ID, CONTACT_ID))
                .thenThrow(new ContactNotFoundException(ACCOUNT_ID, CONTACT_ID));

        mockMvc.perform(get("/api/v1/accounts/{aid}/contacts/{cid}", ACCOUNT_ID, CONTACT_ID))
                .andExpect(status().isNotFound()).andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errorCode").value("CONTACT_NOT_FOUND"));
    }

    @Test
    @DisplayName("POST / -> 201 + Location header")
    void createContact() throws Exception {
        var req = new ContactCreateRequest(List.of(new ContactEmailRequest("a@x.cz", EmailLabel.WORK),
                new ContactEmailRequest("home@x.cz", EmailLabel.HOME)), "Alice", "Liddell", "VIP");
        when(contactService.createContact(eq(ACCOUNT_ID), any())).thenReturn(sample(CONTACT_ID, "a@x.cz"));

        mockMvc.perform(post("/api/v1/accounts/{aid}/contacts", ACCOUNT_ID).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req))).andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/accounts/" + ACCOUNT_ID + "/contacts/" + CONTACT_ID))
                .andExpect(jsonPath("$.id").value(CONTACT_ID)).andExpect(jsonPath("$.emails[0].label").value("WORK"));
    }

    @Test
    @DisplayName("POST / with an invalid email in the list -> 400 validation")
    void createContactInvalidEmail() throws Exception {
        var bad = new ContactCreateRequest(List.of(new ContactEmailRequest("not-an-email", null)), null, null, null);

        mockMvc.perform(post("/api/v1/accounts/{aid}/contacts", ACCOUNT_ID).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(bad))).andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    @DisplayName("POST / with an empty emails list -> 400 validation")
    void createContactEmptyEmails() throws Exception {
        var bad = new ContactCreateRequest(List.of(), null, null, null);

        mockMvc.perform(post("/api/v1/accounts/{aid}/contacts", ACCOUNT_ID).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(bad))).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST / with duplicate email -> 409 CONTACT_DUPLICATE")
    void createContactDuplicate() throws Exception {
        var req = new ContactCreateRequest(List.of(emailReq("dup@x.cz")), null, null, null);
        when(contactService.createContact(eq(ACCOUNT_ID), any()))
                .thenThrow(new DuplicateContactException(ACCOUNT_ID, "dup@x.cz"));

        mockMvc.perform(post("/api/v1/accounts/{aid}/contacts", ACCOUNT_ID).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req))).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("CONTACT_DUPLICATE"));
    }

    @Test
    @DisplayName("PUT /{id} -> 200 with the updated contact")
    void updateContact() throws Exception {
        var req = new ContactUpdateRequest(List.of(new ContactEmailRequest("a@x.cz", EmailLabel.WORK)), "Alice",
                "New Surname", "new note");
        when(contactService.updateContact(eq(ACCOUNT_ID), eq(CONTACT_ID), any())).thenReturn(
                new ContactResponse(CONTACT_ID, List.of(new ContactEmailResponse(1L, "a@x.cz", EmailLabel.WORK, true)),
                        "Alice", "New Surname", "new note", LocalDateTime.now(), LocalDateTime.now()));

        mockMvc.perform(put("/api/v1/accounts/{aid}/contacts/{cid}", ACCOUNT_ID, CONTACT_ID)
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Alice"))
                .andExpect(jsonPath("$.surname").value("New Surname"))
                .andExpect(jsonPath("$.emails[0].email").value("a@x.cz"));
    }

    @Test
    @DisplayName("PATCH /{id} with partial body -> delegated to service")
    void patchContact() throws Exception {
        var req = new ContactPatchRequest(null, "OnlyName", null, null);
        when(contactService.patchContact(eq(ACCOUNT_ID), eq(CONTACT_ID), any()))
                .thenReturn(new ContactResponse(CONTACT_ID, List.of(new ContactEmailResponse(1L, "a@x.cz", null, true)),
                        "OnlyName", "Liddell", null, LocalDateTime.now(), LocalDateTime.now()));

        mockMvc.perform(patch("/api/v1/accounts/{aid}/contacts/{cid}", ACCOUNT_ID, CONTACT_ID)
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("OnlyName"))
                .andExpect(jsonPath("$.surname").value("Liddell"));
    }

    @Test
    @DisplayName("DELETE /{id} -> 204, service is invoked")
    void deleteContact() throws Exception {
        mockMvc.perform(delete("/api/v1/accounts/{aid}/contacts/{cid}", ACCOUNT_ID, CONTACT_ID))
                .andExpect(status().isNoContent());

        verify(contactService).deleteContact(ACCOUNT_ID, CONTACT_ID);
    }

    @Test
    @DisplayName("DELETE /{id} missing → 404")
    void deleteContactMissing() throws Exception {
        org.mockito.Mockito.doThrow(new ContactNotFoundException(ACCOUNT_ID, CONTACT_ID)).when(contactService)
                .deleteContact(ACCOUNT_ID, CONTACT_ID);

        mockMvc.perform(delete("/api/v1/accounts/{aid}/contacts/{cid}", ACCOUNT_ID, CONTACT_ID))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.errorCode").value("CONTACT_NOT_FOUND"));
    }

    @Test
    @DisplayName("POST /{cid}/emails -> 201 with the added address")
    void addEmail() throws Exception {
        var req = new ContactEmailRequest("new@x.cz", EmailLabel.HOME);
        when(contactService.addEmail(eq(ACCOUNT_ID), eq(CONTACT_ID), any()))
                .thenReturn(new ContactEmailResponse(7L, "new@x.cz", EmailLabel.HOME, false));

        mockMvc.perform(post("/api/v1/accounts/{aid}/contacts/{cid}/emails", ACCOUNT_ID, CONTACT_ID)
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.id").value(7))
                .andExpect(jsonPath("$.email").value("new@x.cz")).andExpect(jsonPath("$.label").value("HOME"))
                .andExpect(jsonPath("$.primary").value(false));
    }

    @Test
    @DisplayName("POST /{cid}/emails duplicate → 409")
    void addEmailDuplicate() throws Exception {
        var req = new ContactEmailRequest("dup@x.cz", null);
        when(contactService.addEmail(eq(ACCOUNT_ID), eq(CONTACT_ID), any()))
                .thenThrow(new DuplicateContactException(ACCOUNT_ID, "dup@x.cz"));

        mockMvc.perform(post("/api/v1/accounts/{aid}/contacts/{cid}/emails", ACCOUNT_ID, CONTACT_ID)
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.errorCode").value("CONTACT_DUPLICATE"));
    }

    @Test
    @DisplayName("POST /{cid}/emails with an invalid body -> 400")
    void addEmailInvalidBody() throws Exception {
        var bad = new ContactEmailRequest("not-an-email", null);

        mockMvc.perform(post("/api/v1/accounts/{aid}/contacts/{cid}/emails", ACCOUNT_ID, CONTACT_ID)
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(bad)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("DELETE /{cid}/emails/{eid} → 204")
    void deleteEmail() throws Exception {
        mockMvc.perform(delete("/api/v1/accounts/{aid}/contacts/{cid}/emails/{eid}", ACCOUNT_ID, CONTACT_ID, 3L))
                .andExpect(status().isNoContent());

        verify(contactService).deleteEmail(ACCOUNT_ID, CONTACT_ID, 3L);
    }

    @Test
    @DisplayName("DELETE /{cid}/emails/{eid} last -> 400 VALIDATION_ERROR")
    void deleteEmailLast() throws Exception {
        org.mockito.Mockito.doThrow(new ValidationException("Contact must have at least one email address."))
                .when(contactService).deleteEmail(ACCOUNT_ID, CONTACT_ID, 1L);

        mockMvc.perform(delete("/api/v1/accounts/{aid}/contacts/{cid}/emails/{eid}", ACCOUNT_ID, CONTACT_ID, 1L))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"));
    }

    @Test
    @DisplayName("DELETE /{cid}/emails/{eid} not found -> 404 RESOURCE_NOT_FOUND")
    void deleteEmailMissing() throws Exception {
        org.mockito.Mockito.doThrow(new ResourceNotFoundException("Email with ID 999 for contact 42 was not found."))
                .when(contactService).deleteEmail(ACCOUNT_ID, CONTACT_ID, 999L);

        mockMvc.perform(delete("/api/v1/accounts/{aid}/contacts/{cid}/emails/{eid}", ACCOUNT_ID, CONTACT_ID, 999L))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.errorCode").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    @DisplayName("PATCH /{cid}/emails/{eid}/primary -> 200 with the updated contact")
    void setPrimaryEmail() throws Exception {
        ContactResponse resp = new ContactResponse(CONTACT_ID,
                List.of(new ContactEmailResponse(1L, "a@x.cz", EmailLabel.WORK, false),
                        new ContactEmailResponse(2L, "b@x.cz", EmailLabel.HOME, true)),
                "Alice", "Liddell", null, LocalDateTime.now(), LocalDateTime.now());
        when(contactService.setPrimaryEmail(ACCOUNT_ID, CONTACT_ID, 2L)).thenReturn(resp);

        mockMvc.perform(patch("/api/v1/accounts/{aid}/contacts/{cid}/emails/{eid}/primary", ACCOUNT_ID, CONTACT_ID, 2L))
                .andExpect(status().isOk()).andExpect(jsonPath("$.emails[0].primary").value(false))
                .andExpect(jsonPath("$.emails[1].primary").value(true));
    }

    @Test
    @DisplayName("PATCH /{cid}/emails/{eid}/primary non-existing emailId -> 404")
    void setPrimaryEmailMissing() throws Exception {
        when(contactService.setPrimaryEmail(ACCOUNT_ID, CONTACT_ID, 999L))
                .thenThrow(new ResourceNotFoundException("Email with ID 999 for contact 42 was not found."));

        mockMvc.perform(
                patch("/api/v1/accounts/{aid}/contacts/{cid}/emails/{eid}/primary", ACCOUNT_ID, CONTACT_ID, 999L))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.errorCode").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    @DisplayName("DELETE /{cid}/emails/{eid} with emailId=0 -> 400 @Positive")
    void deleteEmailInvalidId() throws Exception {
        mockMvc.perform(delete("/api/v1/accounts/{aid}/contacts/{cid}/emails/0", ACCOUNT_ID, CONTACT_ID))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /bulk -> 200 with created/failed summary and per-item status")
    void bulkCreateMixedResults() throws Exception {
        BulkContactCreateRequest req = new BulkContactCreateRequest(
                List.of(new ContactCreateRequest(List.of(emailReq("a@x.cz")), "A", null, null),
                        new ContactCreateRequest(List.of(emailReq("dup@x.cz")), "B", null, null)));

        BulkContactCreateResponse response = new BulkContactCreateResponse(2, 1, 1,
                List.of(BulkContactCreateResult.success(0, sample(1L, "a@x.cz")), BulkContactCreateResult.failure(1,
                        "CONTACT_DUPLICATE", "A contact with email dup@x.cz already exists for account 5.")));

        when(contactBulkService.bulkCreate(eq(ACCOUNT_ID), any(BulkContactCreateRequest.class))).thenReturn(response);

        mockMvc.perform(post("/api/v1/accounts/{aid}/contacts/bulk", ACCOUNT_ID).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req))).andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2)).andExpect(jsonPath("$.created").value(1))
                .andExpect(jsonPath("$.failed").value(1)).andExpect(jsonPath("$.results[0].status").value("CREATED"))
                .andExpect(jsonPath("$.results[0].contact.id").value(1))
                .andExpect(jsonPath("$.results[1].status").value("FAILED"))
                .andExpect(jsonPath("$.results[1].errorCode").value("CONTACT_DUPLICATE"));
    }

    @Test
    @DisplayName("POST /bulk with an empty contacts array -> 400 @NotEmpty")
    void bulkCreateEmptyListRejected() throws Exception {
        BulkContactCreateRequest req = new BulkContactCreateRequest(List.of());

        mockMvc.perform(post("/api/v1/accounts/{aid}/contacts/bulk", ACCOUNT_ID).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req))).andExpect(status().isBadRequest());

        verify(contactBulkService, never()).bulkCreate(any(), any());
    }

    @Test
    @DisplayName("POST /bulk with 101 items -> 400 (cap @Size(max=100))")
    void bulkCreateOverLimitRejected() throws Exception {
        List<ContactCreateRequest> many = new java.util.ArrayList<>();
        for (int i = 0; i < 101; i++) {
            many.add(new ContactCreateRequest(List.of(emailReq("c" + i + "@x.cz")), "C" + i, null, null));
        }
        BulkContactCreateRequest req = new BulkContactCreateRequest(many);

        mockMvc.perform(post("/api/v1/accounts/{aid}/contacts/bulk", ACCOUNT_ID).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req))).andExpect(status().isBadRequest());

        verify(contactBulkService, never()).bulkCreate(any(), any());
    }

    @Test
    @DisplayName("DELETE /bulk -> 200 with deleted/failed summary and per-item status")
    void bulkDeleteMixedResults() throws Exception {
        BulkContactDeleteRequest req = new BulkContactDeleteRequest(List.of(10L, 999L, 12L));

        BulkContactDeleteResponse response = new BulkContactDeleteResponse(3, 2, 1,
                List.of(BulkContactDeleteResult.success(10L),
                        BulkContactDeleteResult.failure(999L, "CONTACT_NOT_FOUND",
                                "Contact with ID 999 for account 5 was not found."),
                        BulkContactDeleteResult.success(12L)));

        when(contactBulkService.bulkDelete(eq(ACCOUNT_ID), any(BulkContactDeleteRequest.class))).thenReturn(response);

        mockMvc.perform(delete("/api/v1/accounts/{aid}/contacts/bulk", ACCOUNT_ID)
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.deleted").value(2)).andExpect(jsonPath("$.failed").value(1))
                .andExpect(jsonPath("$.results[0].status").value("DELETED"))
                .andExpect(jsonPath("$.results[0].id").value(10))
                .andExpect(jsonPath("$.results[1].status").value("FAILED"))
                .andExpect(jsonPath("$.results[1].id").value(999))
                .andExpect(jsonPath("$.results[1].errorCode").value("CONTACT_NOT_FOUND"));
    }

    @Test
    @DisplayName("DELETE /bulk with an empty ids array -> 400 @NotEmpty")
    void bulkDeleteEmptyListRejected() throws Exception {
        BulkContactDeleteRequest req = new BulkContactDeleteRequest(List.of());

        mockMvc.perform(delete("/api/v1/accounts/{aid}/contacts/bulk", ACCOUNT_ID)
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest());

        verify(contactBulkService, never()).bulkDelete(any(), any());
    }

    @Test
    @DisplayName("DELETE /bulk with ID 0 -> 400 (element-level @Positive)")
    void bulkDeleteInvalidIdRejected() throws Exception {
        BulkContactDeleteRequest req = new BulkContactDeleteRequest(List.of(1L, 0L, 3L));

        mockMvc.perform(delete("/api/v1/accounts/{aid}/contacts/bulk", ACCOUNT_ID)
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest());

        verify(contactBulkService, never()).bulkDelete(any(), any());
    }

    @Test
    @DisplayName("POST /{tid}/merge -> 200 with the merged contact")
    void mergeContactsHappyPath() throws Exception {
        ContactMergeRequest req = new ContactMergeRequest(List.of(20L, 21L));
        ContactResponse resp = new ContactResponse(CONTACT_ID,
                List.of(new ContactEmailResponse(1L, "a@x.cz", EmailLabel.WORK, true),
                        new ContactEmailResponse(7L, "b@x.cz", null, false)),
                "Alice", "Liddell", "merged note", LocalDateTime.now(), LocalDateTime.now());
        when(contactService.merge(eq(ACCOUNT_ID), eq(CONTACT_ID), any(ContactMergeRequest.class))).thenReturn(resp);

        mockMvc.perform(post("/api/v1/accounts/{aid}/contacts/{tid}/merge", ACCOUNT_ID, CONTACT_ID)
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(CONTACT_ID))
                .andExpect(jsonPath("$.emails.length()").value(2)).andExpect(jsonPath("$.note").value("merged note"));
    }

    @Test
    @DisplayName("POST /{tid}/merge with empty source -> 400 @NotEmpty")
    void mergeContactsEmptySourceRejected() throws Exception {
        ContactMergeRequest req = new ContactMergeRequest(List.of());

        mockMvc.perform(post("/api/v1/accounts/{aid}/contacts/{tid}/merge", ACCOUNT_ID, CONTACT_ID)
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest());

        verify(contactService, never()).merge(any(), any(), any());
    }

    @Test
    @DisplayName("POST /{tid}/merge with 10 source IDs -> 400 @Size(max=9)")
    void mergeContactsTooManySourcesRejected() throws Exception {
        List<Long> tooMany = new java.util.ArrayList<>();
        for (long i = 1; i <= 10; i++) {
            tooMany.add(i + 100);
        }
        ContactMergeRequest req = new ContactMergeRequest(tooMany);

        mockMvc.perform(post("/api/v1/accounts/{aid}/contacts/{tid}/merge", ACCOUNT_ID, CONTACT_ID)
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest());

        verify(contactService, never()).merge(any(), any(), any());
    }

    @Test
    @DisplayName("POST /{tid}/merge exceeds 10-email limit -> 400 VALIDATION_ERROR from service")
    void mergeContactsExceedsEmailLimit() throws Exception {
        ContactMergeRequest req = new ContactMergeRequest(List.of(20L));
        when(contactService.merge(eq(ACCOUNT_ID), eq(CONTACT_ID), any(ContactMergeRequest.class)))
                .thenThrow(new ValidationException(
                        "After merging the contact would have 11 emails, maximum is 10. Reduce addresses before merging."));

        mockMvc.perform(post("/api/v1/accounts/{aid}/contacts/{tid}/merge", ACCOUNT_ID, CONTACT_ID)
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("11 emails")));
    }

    @Test
    @DisplayName("POST /{tid}/merge se source = target → 400 VALIDATION_ERROR ze service")
    void mergeContactsTargetInSource() throws Exception {
        ContactMergeRequest req = new ContactMergeRequest(List.of(CONTACT_ID));
        when(contactService.merge(eq(ACCOUNT_ID), eq(CONTACT_ID), any(ContactMergeRequest.class)))
                .thenThrow(new ValidationException("Target contact must not also be in the source list."));

        mockMvc.perform(post("/api/v1/accounts/{aid}/contacts/{tid}/merge", ACCOUNT_ID, CONTACT_ID)
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"));
    }

    @Test
    @DisplayName("POST /{tid}/merge with non-existing source -> 404 CONTACT_NOT_FOUND from service")
    void mergeContactsSourceNotFound() throws Exception {
        ContactMergeRequest req = new ContactMergeRequest(List.of(999L));
        when(contactService.merge(eq(ACCOUNT_ID), eq(CONTACT_ID), any(ContactMergeRequest.class)))
                .thenThrow(new ContactNotFoundException(ACCOUNT_ID, 999L));

        mockMvc.perform(post("/api/v1/accounts/{aid}/contacts/{tid}/merge", ACCOUNT_ID, CONTACT_ID)
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.errorCode").value("CONTACT_NOT_FOUND"));
    }

    @Test
    @DisplayName("GET /autocomplete?q=ali -> 200 with a flat list (contact x email)")
    void autocompleteHappyPath() throws Exception {
        when(contactService.autocomplete(eq(ACCOUNT_ID), eq("ali"), eq(10))).thenReturn(List.of(
                new ContactAutocompleteResponse(1L, 11L, "alice@x.cz", EmailLabel.WORK, true, "Alice", "Liddell"),
                new ContactAutocompleteResponse(1L, 12L, "alice.home@x.cz", EmailLabel.HOME, false, "Alice",
                        "Liddell")));

        mockMvc.perform(get("/api/v1/accounts/{aid}/contacts/autocomplete", ACCOUNT_ID).param("q", "ali"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].contactId").value(1)).andExpect(jsonPath("$[0].emailId").value(11))
                .andExpect(jsonPath("$[0].email").value("alice@x.cz")).andExpect(jsonPath("$[0].label").value("WORK"))
                .andExpect(jsonPath("$[0].primary").value(true)).andExpect(jsonPath("$[0].name").value("Alice"))
                .andExpect(jsonPath("$[0].surname").value("Liddell")).andExpect(jsonPath("$[1].primary").value(false));
    }

    @Test
    @DisplayName("GET /autocomplete?q=ali&limit=5 -> limit propagated to service")
    void autocompleteCustomLimit() throws Exception {
        when(contactService.autocomplete(eq(ACCOUNT_ID), eq("ali"), eq(5))).thenReturn(List.of());

        mockMvc.perform(
                get("/api/v1/accounts/{aid}/contacts/autocomplete", ACCOUNT_ID).param("q", "ali").param("limit", "5"))
                .andExpect(status().isOk());

        verify(contactService).autocomplete(ACCOUNT_ID, "ali", 5);
    }

    @Test
    @DisplayName("GET /autocomplete?q=ali&limit=999 → limit se zastropuje podle client-config")
    void autocompleteLimitCappedByClientConfig() throws Exception {
        when(contactService.autocomplete(eq(ACCOUNT_ID), eq("ali"), eq(20))).thenReturn(List.of());

        mockMvc.perform(
                get("/api/v1/accounts/{aid}/contacts/autocomplete", ACCOUNT_ID).param("q", "ali").param("limit", "999"))
                .andExpect(status().isOk());

        verify(contactService).autocomplete(ACCOUNT_ID, "ali", 20);
    }

    @Test
    @DisplayName("GET /autocomplete bez q → 400")
    void autocompleteMissingQ() throws Exception {
        mockMvc.perform(get("/api/v1/accounts/{aid}/contacts/autocomplete", ACCOUNT_ID))
                .andExpect(status().isBadRequest());

        verify(contactService, never()).autocomplete(any(), any(), anyInt());
    }

    @Test
    @DisplayName("GET /autocomplete?q= (empty) -> 400 (@Size min=1)")
    void autocompleteEmptyQ() throws Exception {
        mockMvc.perform(get("/api/v1/accounts/{aid}/contacts/autocomplete", ACCOUNT_ID).param("q", ""))
                .andExpect(status().isBadRequest());

        verify(contactService, never()).autocomplete(any(), any(), anyInt());
    }

    @Test
    @DisplayName("GET /autocomplete?q=<101 chars> -> 400 (@Size max=100)")
    void autocompleteQTooLong() throws Exception {
        String longQ = "a".repeat(101);

        mockMvc.perform(get("/api/v1/accounts/{aid}/contacts/autocomplete", ACCOUNT_ID).param("q", longQ))
                .andExpect(status().isBadRequest());

        verify(contactService, never()).autocomplete(any(), any(), anyInt());
    }

    @Test
    @DisplayName("GET /autocomplete?limit=0 → 400 (@Min(1))")
    void autocompleteZeroLimit() throws Exception {
        mockMvc.perform(
                get("/api/v1/accounts/{aid}/contacts/autocomplete", ACCOUNT_ID).param("q", "ali").param("limit", "0"))
                .andExpect(status().isBadRequest());

        verify(contactService, never()).autocomplete(any(), any(), anyInt());
    }

    @Test
    @DisplayName("GET /autocomplete s accountId=0 → 400 (@Positive)")
    void autocompleteInvalidAccount() throws Exception {
        mockMvc.perform(get("/api/v1/accounts/0/contacts/autocomplete").param("q", "ali"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("GET /export.vcf → 200, text/vcard, Content-Disposition s filename")
    void exportVCardHappyPath() throws Exception {
        String vcard = "BEGIN:VCARD\r\nVERSION:4.0\r\nFN:Alice\r\nEMAIL:a@x.cz\r\nEND:VCARD\r\n";
        when(contactService.exportToVCard(ACCOUNT_ID)).thenReturn(vcard);

        mockMvc.perform(get("/api/v1/accounts/{aid}/contacts/export.vcf", ACCOUNT_ID)).andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/vcard")).andExpect(header()
                        .string("Content-Disposition", "attachment; filename=\"contacts-" + ACCOUNT_ID + ".vcf\""))
                .andExpect(content().string(vcard));
    }

    @Test
    @DisplayName("GET /export.vcf s accountId=0 → 400 (@Positive)")
    void exportVCardInvalidAccount() throws Exception {
        mockMvc.perform(get("/api/v1/accounts/0/contacts/export.vcf")).andExpect(status().isBadRequest());

        verify(contactService, never()).exportToVCard(any());
    }
}
