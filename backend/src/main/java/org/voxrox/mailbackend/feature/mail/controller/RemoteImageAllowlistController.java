package org.voxrox.mailbackend.feature.mail.controller;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.voxrox.mailbackend.feature.mail.dto.RemoteImageAllowlistRequest;
import org.voxrox.mailbackend.feature.mail.service.RemoteImageAllowlistService;
import org.voxrox.mailbackend.util.LogCategory;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * REST API for the per-sender remote-image allow-list. Remote images in HTML
 * mail bodies are blocked by default (tracking-pixel defense); trusting a
 * sender here makes that sender's messages auto-load their remote https images.
 * See docs/CONTENT_RENDERING_AUDIT.md finding F2.
 */
@Tag(name = "Remote images", description = "Per-sender allow-list for loading remote images in mail bodies.")
@RestController
@RequestMapping("/api/v1/remote-images/allowlist")
@Validated
public class RemoteImageAllowlistController {

    private static final Logger log = LoggerFactory.getLogger(RemoteImageAllowlistController.class);

    private final RemoteImageAllowlistService service;

    public RemoteImageAllowlistController(RemoteImageAllowlistService service) {
        this.service = service;
    }

    @Operation(summary = "Trust a sender for remote images", description = "Adds the sender to the account's allow-list; the sender's messages then auto-load remote https images. Idempotent.")
    @ApiResponse(responseCode = "204", description = "Sender is allowed (added or already present).")
    @ApiResponse(responseCode = "404", description = "Account not found.")
    @PutMapping
    public ResponseEntity<Void> allow(@Valid @RequestBody RemoteImageAllowlistRequest request) {
        log.info("{} Allowing remote images for a sender on account {}.", LogCategory.API, request.accountId());
        service.allow(request.accountId(), request.senderEmail());
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Stop trusting a sender", description = "Removes the sender from the account's allow-list. Idempotent.")
    @ApiResponse(responseCode = "204", description = "Sender is no longer allowed (removed or was not present).")
    @DeleteMapping
    public ResponseEntity<Void> disallow(@RequestParam @Positive(message = "{validation.positive}") Long accountId,
            @RequestParam @NotBlank(message = "{validation.email.required}") @Email(message = "{validation.email.invalid}") @Size(max = 255, message = "{validation.size.max}") String senderEmail) {
        log.info("{} Disallowing remote images for a sender on account {}.", LogCategory.API, accountId);
        service.disallow(accountId, senderEmail);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "List trusted senders", description = "Returns the sender emails allowed to load remote images for the account.")
    @ApiResponse(responseCode = "200", description = "Allowed sender emails (may be empty).")
    @GetMapping
    public List<String> list(@RequestParam @Positive(message = "{validation.positive}") Long accountId) {
        return service.listAllowedSenders(accountId);
    }
}
