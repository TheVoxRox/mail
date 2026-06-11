package org.voxrox.mailbackend.feature.mail.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.voxrox.mailbackend.core.config.MailClientProperties;
import org.voxrox.mailbackend.core.dto.PagedResponse;
import org.voxrox.mailbackend.exception.ValidationException;
import org.voxrox.mailbackend.feature.mail.dto.DraftRequest;
import org.voxrox.mailbackend.feature.mail.dto.MailSummaryResponse;
import org.voxrox.mailbackend.feature.mail.dto.SendAcceptedResponse;
import org.voxrox.mailbackend.feature.mail.service.MailFacade;
import org.voxrox.mailbackend.feature.mail.service.SmtpMessageService;
import org.voxrox.mailbackend.util.LogCategory;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import module java.base;

/**
 * REST API for drafts. Saving runs asynchronously via
 * {@link SmtpMessageService#saveDraftAsync}; the endpoint returns 202 Accepted
 * immediately and the draft's stableId appears after the next folder sync.
 *
 * Updating an existing draft = {@code POST ?replaces={stableId}}: after the new
 * revision is saved, the old one is removed. PUT is deliberately not exposed
 * because the provider may return a new stable resource after the save.
 */
@Tag(name = "Drafts", description = "Saving drafts into the Drafts folder (async, replaces semantics).")
@RestController
@RequestMapping("/api/v1/accounts/{accountId}/drafts")
@Validated
public class DraftController {

    private static final Logger log = LoggerFactory.getLogger(DraftController.class);

    private final SmtpMessageService smtpService;
    private final MailFacade mailFacade;
    private final MailClientProperties mailProps;

    public DraftController(SmtpMessageService smtpService, MailFacade mailFacade, MailClientProperties mailProps) {
        this.smtpService = smtpService;
        this.mailFacade = mailFacade;
        this.mailProps = mailProps;
    }

    @Operation(summary = "Save draft (async)", description = "Asynchronously saves the draft into the Drafts folder. "
            + "With the replaces={stableId} parameter, the old revision is deleted after a successful save. "
            + "Returns 202 Accepted immediately — the draft's stableId becomes available after the next sync.")
    @ApiResponse(responseCode = "202", description = "Draft save request accepted; processing continues asynchronously.")
    @PostMapping
    public ResponseEntity<Void> saveDraft(@PathVariable @Positive(message = "{validation.positive}") Long accountId,
            @RequestParam(required = false) @Size(max = 128, message = "{validation.size.max}") String replaces,
            @Valid @RequestBody DraftRequest request) {
        log.info("{} Saving draft for account {} (replaces={})", LogCategory.API, accountId, replaces);
        smtpService.saveDraftAsync(accountId, request, replaces);
        return ResponseEntity.accepted().build();
    }

    @Operation(summary = "Send draft (async)", description = "Asynchronously sends an existing draft over SMTP: the original "
            + "MIME is fetched from the Drafts folder (preserving Message-ID, threading headers and attachments), sent, "
            + "appended to Sent and then hard-deleted from Drafts. Returns 202 Accepted with a sendId — the client tracks "
            + "the outcome via the send_completed / send_failed notification stream event.")
    @ApiResponse(responseCode = "202", description = "Send request accepted; outcome is delivered via the notification stream.")
    @PostMapping("/{stableId}/send")
    public ResponseEntity<SendAcceptedResponse> sendDraft(
            @PathVariable @Positive(message = "{validation.positive}") Long accountId,
            @PathVariable @NotBlank(message = "{validation.notBlank}") @Size(max = 128, message = "{validation.size.max}") String stableId) {
        String sendId = UUID.randomUUID().toString();
        log.info("{} Sending draft {} for account {} (sendId={})", LogCategory.API, stableId, accountId, sendId);
        smtpService.sendDraftAsync(accountId, stableId, sendId);
        return ResponseEntity.accepted().body(new SendAcceptedResponse(sendId));
    }

    @Operation(summary = "List drafts", description = "Returns a paginated list of drafts saved in the Drafts folder for the given account.")
    @GetMapping
    public PagedResponse<MailSummaryResponse> listDrafts(
            @PathVariable @Positive(message = "{validation.positive}") Long accountId,
            @RequestParam(required = false) @Min(value = 0, message = "{validation.min}") Integer page,
            @RequestParam(required = false) @Min(value = 1, message = "{validation.min}") Integer size) {

        int finalPage = Objects.requireNonNullElse(page, 0);
        int finalSize = Objects.requireNonNullElse(size, mailProps.sync().defaultPageSize());
        /*
         * Page size cap — without it the client could exhaust memory via size=1000000.
         * Validate after applying the default so that `size=null` (= let the server
         * pick the default) passes through.
         */
        int maxPageSize = mailProps.sync().apiMaxPageSize();
        if (finalSize > maxPageSize) {
            throw new ValidationException(
                    "Page size exceeds the maximum of " + maxPageSize + " (requested: " + finalSize + ").");
        }

        log.debug("{} Listing drafts for account {} (page {}, size {})", LogCategory.API, accountId, finalPage,
                finalSize);

        return PagedResponse.from(mailFacade.listDrafts(accountId, finalPage, finalSize));
    }
}
