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
 * REST API for the address book. There is one book for the whole application,
 * not one per mail account (see the {@code contacts} table comment in
 * {@code V1__init.sql}), so no endpoint here is scoped to an account. The one
 * exception is {@code /autocomplete}, which takes the composing mailbox as a
 * query parameter to decide whose correspondence history to blend in.
 */
@Tag(name = "Contacts", description = "Application-wide contact address book (CRUD + search).")
@RestController
@RequestMapping("/api/v1/contacts")
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
            + "Optional `sort` (`name`/`surname`/`recent`) drives the order (default `surname`). Optional `labelId` filters to contacts carrying the given contact label; an unknown ID is a 404, not an empty page.")
    @ApiResponses({
            @ApiResponse(responseCode = "404", description = "The labelId does not exist (CONTACT_LABEL_NOT_FOUND).")})
    @GetMapping
    public PagedResponse<ContactResponse> listContacts(
            @RequestParam(required = false) @Size(min = 1, message = "{validation.size.min}") String q,
            @RequestParam(required = false) @Min(value = 0, message = "{validation.min}") Integer page,
            @RequestParam(required = false) @Min(value = 1, message = "{validation.min}") Integer size,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) @Positive(message = "{validation.positive}") Long labelId) {

        int finalPage = Objects.requireNonNullElse(page, 0);
        int finalSize = Objects.requireNonNullElse(size, clientConfigProps.contactDefaultPageSize());
        int maxPageSize = mailProps.sync().apiMaxPageSize();
        if (finalSize > maxPageSize) {
            throw new ValidationException(
                    "Page size exceeds the maximum of " + maxPageSize + " (requested: " + finalSize + ").",
                    "validation.pageSizeTooLarge", maxPageSize, finalSize);
        }
        ensureContactQueryWithinLimit(q);

        log.debug("{} Contacts page={} size={} q={} sort={} labelId={}", LogCategory.API, finalPage, finalSize, q, sort,
                labelId);

        if (q != null && !q.isBlank()) {
            return PagedResponse.from(contactService.searchContacts(q, finalPage, finalSize, sort, labelId));
        }
        return PagedResponse.from(contactService.listContacts(finalPage, finalSize, sort, labelId));
    }

    @Operation(summary = "Contact counts", description = "Returns the total number of contacts plus one row per contact label. "
            + "Each per-label count matches the size of the list filtered by the same `labelId`; labels nobody uses are included with contacts = 0.")
    @GetMapping("/counts")
    public ContactCountsResponse getCounts() {
        return contactService.getCounts();
    }

    @Operation(summary = "Compose-window autocomplete", description = "Returns a flat list of addresses for typeahead, merged from the address book (`source: CONTACT`) and "
            + "the addresses harvested from synced message headers (`source: HISTORY`). Ranking: prefix-email > prefix-surname > prefix-name > substring, "
            + "with contacts winning a tie and history rows ordered by written-to-first then recency. An address present in both appears once, as the contact. "
            + "History rows carry no contact identity, so `contactId`, `emailId`, `label` and `primary` are null and the last seen display name is in `name`. "
            + "The limit applies to the merged list. Default limit 10, hard cap 20. "
            + "`accountId` selects whose correspondence history to blend in — it is the mailbox being composed from. "
            + "The address book half ignores it: the book is application-wide, so composing from any mailbox reaches every saved contact.")
    @GetMapping("/autocomplete")
    public List<ContactAutocompleteResponse> autocomplete(
            @RequestParam @Positive(message = "{validation.positive}") Long accountId,
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

    @Operation(summary = "Export address book as vCard 4.0", description = "Returns every contact in the address book in vCard 4.0 format (RFC 6350) — text/vcard. "
            + "Suitable for import into Apple Contacts, Google Contacts, Thunderbird and other clients.")
    @GetMapping(value = "/export.vcf", produces = "text/vcard;charset=UTF-8")
    public ResponseEntity<String> exportVCard() {
        log.info("{} Contact export (vCard)", LogCategory.API);
        String body = contactService.exportToVCard();
        String filename = "contacts.vcf";
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.parseMediaType("text/vcard;charset=UTF-8")).body(body);
    }

    @Operation(summary = "Contact detail", description = "Returns one contact by ID.")
    @ApiResponses({
            @ApiResponse(responseCode = "404", description = "A contact with the given ID does not exist (CONTACT_NOT_FOUND).")})
    @GetMapping("/{contactId}")
    public ContactResponse getContact(@PathVariable @Positive(message = "{validation.positive}") Long contactId) {
        return contactService.getContact(contactId);
    }

    @Operation(summary = "Create contact", description = "Adds a new contact to the address book. E-mail uniqueness across the whole book is enforced (409 on duplicate).")
    @ApiResponses({
            @ApiResponse(responseCode = "409", description = "A contact with this e-mail already exists (CONTACT_DUPLICATE).")})
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ResponseEntity<ContactResponse> createContact(@Valid @RequestBody ContactCreateRequest request) {
        log.info("{} Creating contact", LogCategory.API);
        ContactResponse created = contactService.createContact(request);
        URI location = URI.create("/api/v1/contacts/" + created.id());
        return ResponseEntity.created(location).body(created);
    }

    @Operation(summary = "Full contact update", description = "Replaces all contact fields (PUT).")
    @ApiResponses({
            @ApiResponse(responseCode = "404", description = "A contact with the given ID does not exist (CONTACT_NOT_FOUND)."),
            @ApiResponse(responseCode = "409", description = "The target e-mail is already used by another contact (CONTACT_DUPLICATE).")})
    @PutMapping("/{contactId}")
    public ContactResponse updateContact(@PathVariable @Positive(message = "{validation.positive}") Long contactId,
            @Valid @RequestBody ContactUpdateRequest request) {
        return contactService.updateContact(contactId, request);
    }

    @Operation(summary = "Partial contact update", description = "Updates only the non-null fields from the request.")
    @ApiResponses({
            @ApiResponse(responseCode = "404", description = "A contact with the given ID does not exist (CONTACT_NOT_FOUND)."),
            @ApiResponse(responseCode = "409", description = "The target e-mail is already used by another contact (CONTACT_DUPLICATE).")})
    @PatchMapping("/{contactId}")
    public ContactResponse patchContact(@PathVariable @Positive(message = "{validation.positive}") Long contactId,
            @Valid @RequestBody ContactPatchRequest request) {
        return contactService.patchContact(contactId, request);
    }

    @Operation(summary = "Delete contact", description = "Removes the contact from the address book.")
    @ApiResponses({
            @ApiResponse(responseCode = "404", description = "A contact with the given ID does not exist (CONTACT_NOT_FOUND).")})
    @DeleteMapping("/{contactId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteContact(@PathVariable @Positive(message = "{validation.positive}") Long contactId) {
        contactService.deleteContact(contactId);
    }

    @Operation(summary = "Bulk create contacts (best-effort)", description = "Creates up to 100 contacts in a single request. Each item has its own transaction — duplicates / validation errors do not affect the rest. The response is always 200 with per-item status (CREATED / FAILED).")
    @PostMapping("/bulk")
    public BulkContactCreateResponse bulkCreateContacts(@Valid @RequestBody BulkContactCreateRequest request) {
        log.info("{} Bulk create contacts count={}", LogCategory.API, request.contacts().size());
        return contactBulkService.bulkCreate(request);
    }

    @Operation(summary = "Bulk delete contacts (best-effort)", description = "Deletes up to 100 contacts by ID. Non-existent IDs come back as FAILED / CONTACT_NOT_FOUND, the rest are deleted.")
    @DeleteMapping("/bulk")
    public BulkContactDeleteResponse bulkDeleteContacts(@Valid @RequestBody BulkContactDeleteRequest request) {
        log.info("{} Bulk delete contacts count={}", LogCategory.API, request.ids().size());
        return contactBulkService.bulkDelete(request);
    }

    @Operation(summary = "Merge duplicate contacts", description = "Merges one or more source contacts into the target (all in one transaction). The target stays canonical (name/surname/primary are preserved); e-mails from the sources are added deduplicated by lowercase variant (collisions drop the source side); notes are concatenated. The source contacts are deleted. Limit: 9 sources per request, final e-mail count at most 10.")
    @ApiResponses({
            @ApiResponse(responseCode = "400", description = "Invalid request: target in source, duplicate ID in source, empty source, exceeded 10-email cap (VALIDATION_ERROR)."),
            @ApiResponse(responseCode = "404", description = "One of the source contacts or the target does not exist (CONTACT_NOT_FOUND).")})
    @PostMapping("/{targetId}/merge")
    public ContactResponse mergeContacts(@PathVariable @Positive(message = "{validation.positive}") Long targetId,
            @Valid @RequestBody ContactMergeRequest request) {
        log.info("{} Merge contacts target={} sources={}", LogCategory.API, targetId, request.source().size());
        return contactService.merge(targetId, request);
    }

    @Operation(summary = "Add an e-mail address to a contact", description = "Adds a new address without touching the primary flag of the others. The new e-mail only becomes primary if the contact had no address before.")
    @ApiResponses({@ApiResponse(responseCode = "404", description = "Contact does not exist (CONTACT_NOT_FOUND)."),
            @ApiResponse(responseCode = "409", description = "The e-mail is already used by another contact or by this contact (CONTACT_DUPLICATE).")})
    @PostMapping("/{contactId}/emails")
    @ResponseStatus(HttpStatus.CREATED)
    public ContactEmailResponse addEmail(@PathVariable @Positive(message = "{validation.positive}") Long contactId,
            @Valid @RequestBody ContactEmailRequest request) {
        log.info("{} Adding e-mail to contact {}", LogCategory.API, contactId);
        return contactService.addEmail(contactId, request);
    }

    @Operation(summary = "Delete an e-mail address from a contact", description = "If the primary address is being deleted, the first remaining one (by ID) is promoted. The last address cannot be deleted — a contact must have at least one.")
    @ApiResponses({
            @ApiResponse(responseCode = "400", description = "Attempted to delete the last address of a contact (VALIDATION_ERROR)."),
            @ApiResponse(responseCode = "404", description = "Contact or address does not exist (CONTACT_NOT_FOUND / RESOURCE_NOT_FOUND).")})
    @DeleteMapping("/{contactId}/emails/{emailId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteEmail(@PathVariable @Positive(message = "{validation.positive}") Long contactId,
            @PathVariable @Positive(message = "{validation.positive}") Long emailId) {
        contactService.deleteEmail(contactId, emailId);
    }

    @Operation(summary = "Mark an address as primary", description = "Marks the chosen address as primary; the other addresses of the contact have their primary flag cleared.")
    @ApiResponses({
            @ApiResponse(responseCode = "404", description = "Contact or address does not exist (CONTACT_NOT_FOUND / RESOURCE_NOT_FOUND).")})
    @PatchMapping("/{contactId}/emails/{emailId}/primary")
    public ContactResponse setPrimaryEmail(@PathVariable @Positive(message = "{validation.positive}") Long contactId,
            @PathVariable @Positive(message = "{validation.positive}") Long emailId) {
        return contactService.setPrimaryEmail(contactId, emailId);
    }
}
