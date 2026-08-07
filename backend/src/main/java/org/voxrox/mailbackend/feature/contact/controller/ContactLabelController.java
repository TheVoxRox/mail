package org.voxrox.mailbackend.feature.contact.controller;

import java.net.URI;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.voxrox.mailbackend.feature.contact.dto.ContactLabelAssignmentRequest;
import org.voxrox.mailbackend.feature.contact.dto.ContactLabelAssignmentResponse;
import org.voxrox.mailbackend.feature.contact.dto.ContactLabelCreateRequest;
import org.voxrox.mailbackend.feature.contact.dto.ContactLabelResponse;
import org.voxrox.mailbackend.feature.contact.dto.ContactLabelUpdateRequest;
import org.voxrox.mailbackend.feature.contact.service.ContactLabelService;
import org.voxrox.mailbackend.util.LogCategory;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * REST API for the account's user-defined contact labels ("Family", "Clients")
 * and their bulk assignment to contacts.
 * <p>
 * A sibling resource of {@code /contacts} rather than a sub-resource: a label
 * exists on its own, independent of whether any contact carries it. The
 * per-address WORK/HOME/OTHER type is a different concept entirely and lives
 * inside the contact payload.
 */
@Tag(name = "Contact labels", description = "Per-account user-defined contact labels (CRUD + bulk assignment).")
@RestController
@RequestMapping("/api/v1/accounts/{accountId}/contact-labels")
@Validated
public class ContactLabelController {

    private static final Logger log = LoggerFactory.getLogger(ContactLabelController.class);
    private final ContactLabelService contactLabelService;

    public ContactLabelController(ContactLabelService contactLabelService) {
        this.contactLabelService = contactLabelService;
    }

    @Operation(summary = "List contact labels", description = "Returns all labels of the account ordered by name (case-insensitive). "
            + "Contact counts are not included here — use `GET /contacts/counts` for the sidebar badges.")
    @GetMapping
    public List<ContactLabelResponse> listLabels(
            @PathVariable @Positive(message = "{validation.positive}") Long accountId) {
        return contactLabelService.listLabels(accountId);
    }

    @Operation(summary = "Create a contact label", description = "Creates a label. The name is trimmed and must be unique within the account, compared case-insensitively "
            + "(so \"Rodina\" and \"rodina\" collide). Maximum 200 labels per account.")
    @ApiResponses({
            @ApiResponse(responseCode = "409", description = "A label with this name already exists for the account (CONTACT_LABEL_DUPLICATE)."),
            @ApiResponse(responseCode = "400", description = "Blank name or the per-account label limit was reached (VALIDATION_ERROR).")})
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ResponseEntity<ContactLabelResponse> createLabel(
            @PathVariable @Positive(message = "{validation.positive}") Long accountId,
            @Valid @RequestBody ContactLabelCreateRequest request) {
        log.info("{} Creating contact label for account {}", LogCategory.API, accountId);
        ContactLabelResponse created = contactLabelService.createLabel(accountId, request);
        URI location = URI.create("/api/v1/accounts/" + accountId + "/contact-labels/" + created.id());
        return ResponseEntity.created(location).body(created);
    }

    @Operation(summary = "Rename a contact label", description = "Changes the label name. Assignments to contacts are unaffected.")
    @ApiResponses({
            @ApiResponse(responseCode = "404", description = "The label does not exist for the account (CONTACT_LABEL_NOT_FOUND)."),
            @ApiResponse(responseCode = "409", description = "Another label of the account already uses this name (CONTACT_LABEL_DUPLICATE).")})
    @PatchMapping("/{labelId}")
    public ContactLabelResponse renameLabel(@PathVariable @Positive(message = "{validation.positive}") Long accountId,
            @PathVariable @Positive(message = "{validation.positive}") Long labelId,
            @Valid @RequestBody ContactLabelUpdateRequest request) {
        return contactLabelService.renameLabel(accountId, labelId, request);
    }

    @Operation(summary = "Delete a contact label", description = "Removes the label and unassigns it from every contact. The contacts themselves are kept.")
    @ApiResponses({
            @ApiResponse(responseCode = "404", description = "The label does not exist for the account (CONTACT_LABEL_NOT_FOUND).")})
    @DeleteMapping("/{labelId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteLabel(@PathVariable @Positive(message = "{validation.positive}") Long accountId,
            @PathVariable @Positive(message = "{validation.positive}") Long labelId) {
        log.info("{} Deleting contact label {} of account {}", LogCategory.API, labelId, accountId);
        contactLabelService.deleteLabel(accountId, labelId);
    }

    @Operation(summary = "Bulk assign labels to contacts", description = "Adds and/or removes labels across up to 100 contacts in one transaction. Idempotent: a label a contact already "
            + "carries is left alone and does not count toward `changed`. At least one of addLabelIds / removeLabelIds must be non-empty.")
    @ApiResponses({
            @ApiResponse(responseCode = "404", description = "One of the contacts or labels does not exist for the account (CONTACT_NOT_FOUND / CONTACT_LABEL_NOT_FOUND)."),
            @ApiResponse(responseCode = "400", description = "Neither list was provided, or a contact would exceed 50 labels (VALIDATION_ERROR).")})
    @PostMapping("/assignments")
    public ContactLabelAssignmentResponse assignLabels(
            @PathVariable @Positive(message = "{validation.positive}") Long accountId,
            @Valid @RequestBody ContactLabelAssignmentRequest request) {
        log.info("{} Bulk label assignment account={} contacts={}", LogCategory.API, accountId,
                request.contactIds().size());
        return contactLabelService.assignLabels(accountId, request);
    }
}
