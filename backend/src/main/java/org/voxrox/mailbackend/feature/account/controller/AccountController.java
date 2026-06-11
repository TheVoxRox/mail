package org.voxrox.mailbackend.feature.account.controller;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.voxrox.mailbackend.feature.account.dto.AccountConnectionTestRequest;
import org.voxrox.mailbackend.feature.account.dto.AccountConnectionTestResponse;
import org.voxrox.mailbackend.feature.account.dto.AccountCreateRequest;
import org.voxrox.mailbackend.feature.account.dto.AccountResponse;
import org.voxrox.mailbackend.feature.account.dto.AccountUpdateRequest;
import org.voxrox.mailbackend.feature.account.service.AccountConnectionTestService;
import org.voxrox.mailbackend.feature.account.service.AccountService;
import org.voxrox.mailbackend.util.LogCategory;
import org.voxrox.mailbackend.util.LogMasker;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * REST API for managing e-mail accounts (CRUD). All business logic including
 * validation, password encryption and server communication is delegated to
 * {@link AccountService}.
 */
@Tag(name = "Accounts", description = "CRUD over e-mail accounts — validation, password encryption, connection test.")
@RestController
@RequestMapping("/api/v1/accounts")
@Validated
public class AccountController {

    private static final Logger log = LoggerFactory.getLogger(AccountController.class);
    private final AccountService accountService;
    private final AccountConnectionTestService accountConnectionTestService;

    public AccountController(AccountService accountService, AccountConnectionTestService accountConnectionTestService) {
        this.accountService = accountService;
        this.accountConnectionTestService = accountConnectionTestService;
    }

    @Operation(summary = "Account detail", description = "Returns one specific e-mail account by ID.")
    @GetMapping("/{id}")
    public AccountResponse getAccountById(@PathVariable @Positive(message = "{validation.positive}") Long id) {
        log.info("{} Account detail request, ID: {}", LogCategory.API, id);
        return accountService.getAccountById(id);
    }

    @Operation(summary = "List all accounts", description = "Returns a list of all registered e-mail accounts (including inactive ones).")
    @GetMapping
    public List<AccountResponse> getAllAccounts() {
        log.debug("{} Listing all accounts", LogCategory.API);
        return accountService.listAllAccounts();
    }

    @Operation(summary = "Account connection test", description = "Verifies IMAP and SMTP connections for the given provider/custom config without saving the account.")
    @PostMapping("/test-connection")
    public AccountConnectionTestResponse testConnection(@Valid @RequestBody AccountConnectionTestRequest request) {
        log.info("{} Account connection test for e-mail: {}", LogCategory.API, LogMasker.maskEmail(request.email()));
        return accountConnectionTestService.testConnection(request);
    }

    @Operation(summary = "Create account", description = "Creates a new account, encrypts the password or OAuth secret and returns 201 with the complete DTO.")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AccountResponse createAccount(@Valid @RequestBody AccountCreateRequest request) {
        log.info("{} Creating new account for e-mail: {}", LogCategory.API, LogMasker.maskEmail(request.email()));

        AccountResponse created = accountService.createAccount(request);

        log.info("{} Account created successfully with ID: {}", LogCategory.API, created.id());
        return created;
    }

    @Operation(summary = "Full account update", description = "Replaces all account fields. Password/secret are re-encrypted if sent.")
    @PutMapping("/{id}")
    public AccountResponse updateAccount(@PathVariable @Positive(message = "{validation.positive}") Long id,
            @Valid @RequestBody AccountUpdateRequest request) {
        log.info("{} Full update (PUT) of account ID: {}", LogCategory.API, id);
        return accountService.updateAccount(id, request);
    }

    @Operation(summary = "Delete account", description = "Deletes the account and closes its IMAP connection (cleanup of the ImapConnectionManager pool).")
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteAccount(@PathVariable @Positive(message = "{validation.positive}") Long id) {
        log.warn("{} START: Deleting account ID: {}", LogCategory.API, id);
        accountService.deleteAccount(id);
        log.info("{} Account ID: {} deleted successfully", LogCategory.API, id);
    }
}
