package org.voxrox.mailbackend.feature.mail.controller;

import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.voxrox.mailbackend.feature.mail.dto.MailRequest;
import org.voxrox.mailbackend.feature.mail.dto.SendAcceptedResponse;
import org.voxrox.mailbackend.feature.mail.service.MailFacade;
import org.voxrox.mailbackend.feature.mail.service.SmtpMessageService;
import org.voxrox.mailbackend.util.LogCategory;
import org.voxrox.mailbackend.util.LogMasker;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Write-side REST API for messages: sending a new e-mail and preparing drafts
 * (reply, reply-all, forward) from an existing message. The actual send runs
 * asynchronously; the endpoint returns 202 Accepted immediately.
 */
@Tag(name = "Messages — Write", description = "Send a message and prepare reply / forward drafts.")
@RestController
@RequestMapping("/api/v1/messages")
@Validated
public class MailWriteController {

    private static final Logger log = LoggerFactory.getLogger(MailWriteController.class);

    private final MailFacade mailFacade;
    private final SmtpMessageService smtpService;

    public MailWriteController(MailFacade mailFacade, SmtpMessageService smtpService) {
        this.mailFacade = mailFacade;
        this.smtpService = smtpService;
    }

    @Operation(summary = "Send message (async)", description = "Asynchronously sends a message from the given account. Returns 202 Accepted with a sendId — the client tracks the outcome via the send_completed / send_failed notification stream event.")
    @ApiResponse(responseCode = "202", description = "Send request accepted; outcome is delivered via the notification stream.")
    @PostMapping("/account/{accountId}/send")
    public ResponseEntity<SendAcceptedResponse> sendEmail(
            @PathVariable @Positive(message = "{validation.positive}") Long accountId,
            @Valid @RequestBody MailRequest request) {

        String sendId = UUID.randomUUID().toString();
        log.info("{} Sending from account {} (sendId={}): {}", LogCategory.API, accountId, sendId,
                LogMasker.maskEmail(request.to()));
        smtpService.sendEmailAsync(accountId, request, sendId);
        return ResponseEntity.accepted().body(new SendAcceptedResponse(sendId));
    }

    @Operation(summary = "Prepare reply (reply / reply-all)", description = "Returns a pre-filled MailRequest for replying to the given message. Parameter all=true = reply-all (reply to every recipient).")
    @GetMapping("/{stableId}/reply")
    public MailRequest prepareReply(
            @PathVariable @NotBlank(message = "{validation.notBlank}") @Size(max = 128, message = "{validation.size.max}") String stableId,
            @RequestParam(defaultValue = "false") boolean all) {
        log.info("{} Preparing reply (all={}) for: {}", LogCategory.API, all, stableId);
        return mailFacade.prepareReply(stableId, all);
    }

    @Operation(summary = "Prepare forward", description = "Returns a pre-filled MailRequest for forwarding the message, including the quoted body and references to the original attachments.")
    @GetMapping("/{stableId}/forward")
    public MailRequest prepareForward(
            @PathVariable @NotBlank(message = "{validation.notBlank}") @Size(max = 128, message = "{validation.size.max}") String stableId) {
        log.info("{} Preparing forward for: {}", LogCategory.API, stableId);
        return mailFacade.prepareForward(stableId);
    }
}
