package org.voxrox.mailbackend.feature.account.controller;

import java.util.List;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.voxrox.mailbackend.feature.account.dto.MailProviderResponse;
import org.voxrox.mailbackend.feature.account.service.AccountProviderService;
import org.voxrox.mailbackend.util.LogCategory;
import org.voxrox.mailbackend.util.LogMasker;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "Mail Providers", description = "Predefined provider templates and e-mail-based auto-detection.")
@RestController
@RequestMapping("/api/v1/accounts/providers")
@Validated
public class MailProviderController {

    private static final Logger log = LoggerFactory.getLogger(MailProviderController.class);
    private final AccountProviderService providerService;

    public MailProviderController(AccountProviderService providerService) {
        this.providerService = providerService;
    }

    @Operation(summary = "List all providers", description = "Returns the list of all predefined mail provider templates (IMAP/SMTP settings).")
    @GetMapping
    public List<MailProviderResponse> getAllProviders() {
        log.debug("{} Loading all available provider templates", LogCategory.API);
        return providerService.getAllProviders();
    }

    @Operation(summary = "Auto-detect provider by e-mail", description = "Finds the matching provider template for the given e-mail address (by domain). "
            + "Returns 404 if no template matches.")
    @GetMapping("/resolve")
    public ResponseEntity<MailProviderResponse> resolveProvider(
            @RequestParam @NotBlank(message = "{validation.email.required}") @Email(message = "{validation.email.invalid}") @Size(max = 255, message = "{validation.size.max}") String email) {
        log.info("{} Auto-detection request for e-mail: {}", LogCategory.API, LogMasker.maskEmail(email));
        return providerService.findProviderByEmail(email).map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @Operation(summary = "Provider detail", description = "Returns one specific provider template by ID, or 404 if it does not exist.")
    @GetMapping("/{id}")
    public ResponseEntity<MailProviderResponse> getProviderById(
            @PathVariable @Positive(message = "{validation.positive}") Long id) {
        return providerService.getProviderById(id).map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
    }
}
