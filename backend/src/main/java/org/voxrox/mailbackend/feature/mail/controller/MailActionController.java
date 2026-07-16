package org.voxrox.mailbackend.feature.mail.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.voxrox.mailbackend.feature.account.service.AccountService;
import org.voxrox.mailbackend.feature.mail.dto.MessageFlag;
import org.voxrox.mailbackend.feature.mail.dto.MoveRequest;
import org.voxrox.mailbackend.feature.mail.service.MailFacade;
import org.voxrox.mailbackend.feature.mail.service.MailSyncService;
import org.voxrox.mailbackend.util.LogCategory;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * REST API for actions on existing messages: changing flags
 * (seen/flagged/answered), moving to trash and manually triggering a sync.
 */
@Tag(name = "Messages — Actions", description = "Flag changes, move-to-trash, manual sync trigger.")
@RestController
@RequestMapping("/api/v1/messages")
@Validated
public class MailActionController {

    private static final Logger log = LoggerFactory.getLogger(MailActionController.class);

    private final MailFacade mailFacade;
    private final MailSyncService mailSyncService;
    private final AccountService accountService;

    public MailActionController(MailFacade mailFacade, MailSyncService mailSyncService, AccountService accountService) {
        this.mailFacade = mailFacade;
        this.mailSyncService = mailSyncService;
        this.accountService = accountService;
    }

    /**
     * Sets or clears a flag on the message.
     *
     * @param type
     *            supported values: {@code seen}, {@code flagged}, {@code answered}
     *            (case-insensitive); any other value yields 400 Bad Request
     * @param value
     *            {@code true} = set the flag, {@code false} = clear it
     */
    @Operation(summary = "Set / clear a flag", description = "Sets or clears a flag on the message. Supported types: seen, flagged, answered (case-insensitive).")
    @ApiResponse(responseCode = "204", description = "Flag updated.")
    @PatchMapping("/{stableId}/flags")
    public ResponseEntity<Void> updateMessageFlag(
            @PathVariable @NotBlank(message = "{validation.notBlank}") @Size(max = 128, message = "{validation.size.max}") String stableId,
            @RequestParam @NotBlank(message = "{validation.messageFlag.required}") @Size(max = 32, message = "{validation.size.max}") String type,
            @RequestParam boolean value) {

        log.info("{} Flag update on {}: {}={}", LogCategory.API, stableId, type, value);

        MessageFlag flag = MessageFlag.from(type);
        mailFacade.updateMessageFlag(stableId, flag, value);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Delete message", description = "Moves the message to the trash (soft delete). A message already in the trash is deleted permanently (server-side expunge).")
    @ApiResponse(responseCode = "204", description = "Message moved to trash, or permanently deleted when it already was in the trash.")
    @DeleteMapping("/{stableId}")
    public ResponseEntity<Void> deleteMessage(
            @PathVariable @NotBlank(message = "{validation.notBlank}") @Size(max = 128, message = "{validation.size.max}") String stableId) {
        log.info("{} Deleting message {}", LogCategory.API, stableId);
        mailFacade.moveToTrash(stableId);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Move message to another folder", description = "Moves the message to a user-selected folder identified by folderRef from the folder list. "
            + "The backend operation runs asynchronously; the local record is deleted immediately — after the "
            + "target folder sync completes the message reappears with the same stableId contract.")
    @ApiResponse(responseCode = "204", description = "Local record removed; the remote move runs asynchronously.")
    @ApiResponse(responseCode = "404", description = "Message or target folder not found.")
    @PostMapping("/{stableId}/move")
    public ResponseEntity<Void> moveMessage(
            @PathVariable @NotBlank(message = "{validation.notBlank}") @Size(max = 128, message = "{validation.size.max}") String stableId,
            @Valid @RequestBody MoveRequest request) {

        log.info("{} Moving message {} to folder {}", LogCategory.API, stableId, request.folderRef());
        mailFacade.moveToFolder(stableId, request.folderRef());
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Manual account sync trigger", description = "Starts an incremental sync of all folders of the given account. Returns 202 Accepted; the sync runs asynchronously.")
    @ApiResponse(responseCode = "202", description = "Sync started; progress is delivered via the notification stream.")
    @PostMapping("/account/{accountId}/sync")
    public ResponseEntity<Void> triggerSync(@PathVariable @Positive(message = "{validation.positive}") Long accountId) {
        log.info("{} Sync trigger for account {}", LogCategory.API, accountId);
        // Load the real entity from the DB — not an empty proxy with just an ID.
        mailSyncService.syncAllFolders(accountService.getAccountOrThrow(accountId));
        return ResponseEntity.accepted().build();
    }
}
