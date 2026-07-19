package org.voxrox.mailbackend.feature.contact.controller;

import java.net.URI;
import java.util.List;
import java.util.Objects;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.voxrox.mailbackend.core.config.ClientConfigProperties;
import org.voxrox.mailbackend.core.config.MailClientProperties;
import org.voxrox.mailbackend.core.dto.PagedResponse;
import org.voxrox.mailbackend.exception.ValidationException;
import org.voxrox.mailbackend.feature.contact.EmailLabel;
import org.voxrox.mailbackend.feature.contact.dto.BulkContactCreateRequest;
import org.voxrox.mailbackend.feature.contact.dto.BulkContactCreateResponse;
import org.voxrox.mailbackend.feature.contact.dto.BulkContactDeleteRequest;
import org.voxrox.mailbackend.feature.contact.dto.BulkContactDeleteResponse;
import org.voxrox.mailbackend.feature.contact.dto.ContactAutocompleteResponse;
import org.voxrox.mailbackend.feature.contact.dto.ContactCountsResponse;
import org.voxrox.mailbackend.feature.contact.dto.ContactCreateRequest;
import org.voxrox.mailbackend.feature.contact.dto.ContactEmailRequest;
import org.voxrox.mailbackend.feature.contact.dto.ContactEmailResponse;
import org.voxrox.mailbackend.feature.contact.dto.ContactMergeRequest;
import org.voxrox.mailbackend.feature.contact.dto.ContactPatchRequest;
import org.voxrox.mailbackend.feature.contact.dto.ContactResponse;
import org.voxrox.mailbackend.feature.contact.dto.ContactUpdateRequest;
import org.voxrox.mailbackend.feature.contact.service.ContactBulkService;
import org.voxrox.mailbackend.feature.contact.service.ContactService;
import org.voxrox.mailbackend.util.LogCategory;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * REST API for the per-account contact address book. All endpoints are rooted
 * under a concrete account — cross-account access returns 404 (we do not leak
 * the existence of another account's contact).
 */
@Tag(name = "Contacts", description = "Per-account contact address book (CRUD + search).")
@RestController
@RequestMapping("/api/v1/accounts/{accountId}/contacts")
@Validated
public class ContactController {

    private static final Logger log = LoggerFactory.getLogger(ContactController.class);
    private final ContactService contactService;
    private final ContactBulkService contactBulkService;
    private final MailClientProperties mailProps;
    private final ClientConfigProperties clientConfigProps;

    public ContactController(ContactService contactService, ContactBulkService contactBulkService,
            MailClientProperties mailProps, ClientConfigProperties clientConfigProps) {
        this.contactService = contactService;
        this.contactBulkService = contactBulkService;
        this.mailProps = mailProps;
        this.clientConfigProps = clientConfigProps;
    }

    @Operation(summary = "List / search contacts", description = "Returns a paginated list of contacts. With the q parameter performs a case-insensitive substring search across email, name and surname. "
            + "Optional `sort` (`name`/`surname`/`recent`) drives the order (default `surname`). Optional `label` (`WORK`/`HOME`/`OTHER`) filters to contacts with at least one e-mail bearing the given label.")
    @GetMapping
    public PagedResponse<ContactResponse> listContacts(
            @PathVariable @Positive(message = "{validation.positive}") Long accountId,
            @RequestParam(required = false) @Size(min = 1, message = "{validation.size.min}") String q,
            @RequestParam(required = false) @Min(value = 0, message = "{validation.min}") Integer page,
            @RequestParam(required = false) @Min(value = 1, message = "{validation.min}") Integer size,
            @RequestParam(required = false) String sort, @RequestParam(required = false) EmailLabel label) {

        int finalPage = Objects.requireNonNullElse(page, 0);
        int finalSize = Objects.requireNonNullElse(size, clientConfigProps.contactDefaultPageSize());
        int maxPageSize = mailProps.sync().apiMaxPageSize();
        if (finalSize > maxPageSize) {
            throw new ValidationException(
                    "Page size exceeds the maximum of " + maxPageSize + " (requested: " + finalSize + ").",
                    "validation.pageSizeTooLarge", maxPageSize, finalSize);
        }
        ensureContactQueryWithinLimit(q);

        log.debug("{} Contacts account={} page={} size={} q={} sort={} label={}", LogCategory.API, accountId, finalPage,
                finalSize, q, sort, label);

        if (q != null && !q.isBlank()) {
            return PagedResponse.from(contactService.searchContacts(accountId, q, finalPage, finalSize, sort, label));
        }
        return PagedResponse.from(contactService.listContacts(accountId, finalPage, finalSize, sort, label));
    }

    @Operation(summary = "Contact counts", description = "Returns the total number of contacts of the account plus per-label counts (WORK/HOME/OTHER). "
            + "A contact is counted for a label when at least one of its e-mail addresses bears it — consistent with the `label` filter of the list endpoint.")
    @GetMapping("/counts")
    public ContactCountsResponse getCounts(@PathVariable @Positive(message = "{validation.positive}") Long accountId) {
        return contactService.getCounts(accountId);
    }

    @Operation(summary = "Compose-window autocomplete", description = "Returns a flat list of addresses (contact x email) for typeahead. Ranking: prefix-email > prefix-surname > prefix-name > substring. Default limit 10, hard cap 20.")
    @GetMapping("/autocomplete")
    public List<ContactAutocompleteResponse> autocomplete(
            @PathVariable @Positive(message = "{validation.positive}") Long accountId,
            @RequestParam @Size(min = 1, message = "{validation.size.min}") String q,
            @RequestParam(required = false) @Min(value = 1, message = "{validation.min}") Integer limit) {
        ensureContactQueryWithinLimit(q);
        int maxLimit = clientConfigProps.contactAutocompleteMaxLimit();
        int finalLimit = Math
                .min(Objects.requireNonNullElse(limit, clientConfigProps.contactAutocompleteDefaultLimit()), maxLimit);
        return contactService.autocomplete(accountId, q, finalLimit);
    }

    private void ensureContactQueryWithinLimit(String query) {
        if (query == null) {
            return;
        }
        int maxLen = clientConfigProps.contactQueryMaxLength();
        if (query.length() > maxLen) {
            throw new ValidationException("Contact query exceeds the maximum of " + maxLen + " characters.",
                    "validation.contactQueryTooLong", maxLen);
        }
    }

    @Operation(summary = "Export address book as vCard 4.0", description = "Returns all contacts of the account in vCard 4.0 format (RFC 6350) — text/vcard. "
            + "Suitable for import into Apple Contacts, Google Contacts, Thunderbird and other clients.")
    @GetMapping(value = "/export.vcf", produces = "text/vcard;charset=UTF-8")
    public ResponseEntity<String> exportVCard(
            @PathVariable @Positive(message = "{validation.positive}") Long accountId) {
        log.info("{} Contact export (vCard) account={}", LogCategory.API, accountId);
        String body = contactService.exportToVCard(accountId);
        String filename = "contacts-" + accountId + ".vcf";
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.parseMediaType("text/vcard;charset=UTF-8")).body(body);
    }

    @Operation(summary = "Contact detail", description = "Returns one contact by ID within the given account.")
    @ApiResponses({
            @ApiResponse(responseCode = "404", description = "A contact with the given ID does not exist for the account (CONTACT_NOT_FOUND).")})
    @GetMapping("/{contactId}")
    public ContactResponse getContact(@PathVariable @Positive(message = "{validation.positive}") Long accountId,
            @PathVariable @Positive(message = "{validation.positive}") Long contactId) {
        return contactService.getContact(accountId, contactId);
    }

    @Operation(summary = "Create contact", description = "Adds a new contact to the account's address book. Per-account e-mail uniqueness is enforced (409 on duplicate).")
    @ApiResponses({
            @ApiResponse(responseCode = "409", description = "A contact with this e-mail already exists for the account (CONTACT_DUPLICATE).")})
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ResponseEntity<ContactResponse> createContact(
            @PathVariable @Positive(message = "{validation.positive}") Long accountId,
            @Valid @RequestBody ContactCreateRequest request) {
        log.info("{} Creating contact for account {}", LogCategory.API, accountId);
        ContactResponse created = contactService.createContact(accountId, request);
        URI location = URI.create("/api/v1/accounts/" + accountId + "/contacts/" + created.id());
        return ResponseEntity.created(location).body(created);
    }

    @Operation(summary = "Full contact update", description = "Replaces all contact fields (PUT).")
    @ApiResponses({
            @ApiResponse(responseCode = "404", description = "A contact with the given ID does not exist for the account (CONTACT_NOT_FOUND)."),
            @ApiResponse(responseCode = "409", description = "The target e-mail is already used by another contact (CONTACT_DUPLICATE).")})
    @PutMapping("/{contactId}")
    public ContactResponse updateContact(@PathVariable @Positive(message = "{validation.positive}") Long accountId,
            @PathVariable @Positive(message = "{validation.positive}") Long contactId,
            @Valid @RequestBody ContactUpdateRequest request) {
        return contactService.updateContact(accountId, contactId, request);
    }

    @Operation(summary = "Partial contact update", description = "Updates only the non-null fields from the request.")
    @ApiResponses({
            @ApiResponse(responseCode = "404", description = "A contact with the given ID does not exist for the account (CONTACT_NOT_FOUND)."),
            @ApiResponse(responseCode = "409", description = "The target e-mail is already used by another contact (CONTACT_DUPLICATE).")})
    @PatchMapping("/{contactId}")
    public ContactResponse patchContact(@PathVariable @Positive(message = "{validation.positive}") Long accountId,
            @PathVariable @Positive(message = "{validation.positive}") Long contactId,
            @Valid @RequestBody ContactPatchRequest request) {
        return contactService.patchContact(accountId, contactId, request);
    }

    @Operation(summary = "Delete contact", description = "Removes the contact from the address book.")
    @ApiResponses({
            @ApiResponse(responseCode = "404", description = "A contact with the given ID does not exist for the account (CONTACT_NOT_FOUND).")})
    @DeleteMapping("/{contactId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteContact(@PathVariable @Positive(message = "{validation.positive}") Long accountId,
            @PathVariable @Positive(message = "{validation.positive}") Long contactId) {
        contactService.deleteContact(accountId, contactId);
    }

    @Operation(summary = "Bulk create contacts (best-effort)", description = "Creates up to 100 contacts in a single request. Each item has its own transaction — duplicates / validation errors do not affect the rest. The response is always 200 with per-item status (CREATED / FAILED).")
    @PostMapping("/bulk")
    public BulkContactCreateResponse bulkCreateContacts(
            @PathVariable @Positive(message = "{validation.positive}") Long accountId,
            @Valid @RequestBody BulkContactCreateRequest request) {
        log.info("{} Bulk create contacts account={} count={}", LogCategory.API, accountId, request.contacts().size());
        return contactBulkService.bulkCreate(accountId, request);
    }

    @Operation(summary = "Bulk delete contacts (best-effort)", description = "Deletes up to 100 contacts by ID. Non-existent IDs come back as FAILED / CONTACT_NOT_FOUND, the rest are deleted.")
    @DeleteMapping("/bulk")
    public BulkContactDeleteResponse bulkDeleteContacts(
            @PathVariable @Positive(message = "{validation.positive}") Long accountId,
            @Valid @RequestBody BulkContactDeleteRequest request) {
        log.info("{} Bulk delete contacts account={} count={}", LogCategory.API, accountId, request.ids().size());
        return contactBulkService.bulkDelete(accountId, request);
    }

    @Operation(summary = "Merge duplicate contacts", description = "Merges one or more source contacts into the target (all in one transaction). The target stays canonical (name/surname/primary are preserved); e-mails from the sources are added deduplicated by lowercase variant (collisions drop the source side); notes are concatenated. The source contacts are deleted. Limit: 9 sources per request, final e-mail count at most 10.")
    @ApiResponses({
            @ApiResponse(responseCode = "400", description = "Invalid request: target in source, duplicate ID in source, empty source, exceeded 10-email cap (VALIDATION_ERROR)."),
            @ApiResponse(responseCode = "404", description = "One of the source contacts or the target does not exist (CONTACT_NOT_FOUND).")})
    @PostMapping("/{targetId}/merge")
    public ContactResponse mergeContacts(@PathVariable @Positive(message = "{validation.positive}") Long accountId,
            @PathVariable @Positive(message = "{validation.positive}") Long targetId,
            @Valid @RequestBody ContactMergeRequest request) {
        log.info("{} Merge contacts account={} target={} sources={}", LogCategory.API, accountId, targetId,
                request.source().size());
        return contactService.merge(accountId, targetId, request);
    }

    @Operation(summary = "Add an e-mail address to a contact", description = "Adds a new address without touching the primary flag of the others. The new e-mail only becomes primary if the contact had no address before.")
    @ApiResponses({@ApiResponse(responseCode = "404", description = "Contact does not exist (CONTACT_NOT_FOUND)."),
            @ApiResponse(responseCode = "409", description = "The e-mail is already used by another contact or by this contact (CONTACT_DUPLICATE).")})
    @PostMapping("/{contactId}/emails")
    @ResponseStatus(HttpStatus.CREATED)
    public ContactEmailResponse addEmail(@PathVariable @Positive(message = "{validation.positive}") Long accountId,
            @PathVariable @Positive(message = "{validation.positive}") Long contactId,
            @Valid @RequestBody ContactEmailRequest request) {
        log.info("{} Adding e-mail to contact {} account {}", LogCategory.API, contactId, accountId);
        return contactService.addEmail(accountId, contactId, request);
    }

    @Operation(summary = "Delete an e-mail address from a contact", description = "If the primary address is being deleted, the first remaining one (by ID) is promoted. The last address cannot be deleted — a contact must have at least one.")
    @ApiResponses({
            @ApiResponse(responseCode = "400", description = "Attempted to delete the last address of a contact (VALIDATION_ERROR)."),
            @ApiResponse(responseCode = "404", description = "Contact or address does not exist (CONTACT_NOT_FOUND / RESOURCE_NOT_FOUND).")})
    @DeleteMapping("/{contactId}/emails/{emailId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteEmail(@PathVariable @Positive(message = "{validation.positive}") Long accountId,
            @PathVariable @Positive(message = "{validation.positive}") Long contactId,
            @PathVariable @Positive(message = "{validation.positive}") Long emailId) {
        contactService.deleteEmail(accountId, contactId, emailId);
    }

    @Operation(summary = "Mark an address as primary", description = "Marks the chosen address as primary; the other addresses of the contact have their primary flag cleared.")
    @ApiResponses({
            @ApiResponse(responseCode = "404", description = "Contact or address does not exist (CONTACT_NOT_FOUND / RESOURCE_NOT_FOUND).")})
    @PatchMapping("/{contactId}/emails/{emailId}/primary")
    public ContactResponse setPrimaryEmail(@PathVariable @Positive(message = "{validation.positive}") Long accountId,
            @PathVariable @Positive(message = "{validation.positive}") Long contactId,
            @PathVariable @Positive(message = "{validation.positive}") Long emailId) {
        return contactService.setPrimaryEmail(accountId, contactId, emailId);
    }
}
