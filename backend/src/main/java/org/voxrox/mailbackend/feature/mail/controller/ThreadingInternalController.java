package org.voxrox.mailbackend.feature.mail.controller;

import jakarta.validation.constraints.Positive;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.voxrox.mailbackend.exception.ResourceNotFoundException;
import org.voxrox.mailbackend.feature.account.entity.AccountEntity;
import org.voxrox.mailbackend.feature.account.service.AccountService;
import org.voxrox.mailbackend.feature.mail.service.ThreadingBackfillService;
import org.voxrox.mailbackend.util.LogCategory;

import io.swagger.v3.oas.annotations.Hidden;

/**
 * Internal hook for re-running the threading backfill on demand. Used by QA
 * (verify behaviour after corrupting {@code thread_id} manually) and by support
 * (emergency reset after an import of older mailboxes).
 *
 * <p>
 * Hidden from the public OpenAPI spec; protected by the same {@code X-API-KEY}
 * as every other {@code /api/internal/*} endpoint via
 * {@link org.voxrox.mailbackend.core.security.ApiKeyFilter}. The endpoint runs
 * the backfill on the calling thread (synchronously) so the caller sees the
 * completion result, not a 202.
 */
@Hidden
@RestController
@Validated
public class ThreadingInternalController {

    private static final Logger log = LoggerFactory.getLogger(ThreadingInternalController.class);

    private final ThreadingBackfillService backfillService;
    private final AccountService accountService;

    public ThreadingInternalController(ThreadingBackfillService backfillService, AccountService accountService) {
        this.backfillService = backfillService;
        this.accountService = accountService;
    }

    /**
     * Recompute {@code thread_id} for every unthreaded message in the given
     * account. Useful after manually clearing the threading columns (QA) or after
     * importing an old mailbox that bypassed the sync path.
     *
     * @param accountId
     *            account to recompute; must exist
     * @return {@code 200 OK} with the count of messages whose thread membership was
     *         assigned
     */
    @PostMapping("/api/internal/threading/recompute")
    public ResponseEntity<Integer> recomputeThreading(
            @RequestParam @Positive(message = "{validation.positive}") Long accountId) {
        AccountEntity account = accountService.getAccountOrThrow(accountId);
        if (account == null) {
            // accountService throws if missing; defensive null guard for the
            // future where the signature might change.
            throw new ResourceNotFoundException("Account not found: " + accountId);
        }
        log.info("{} Internal threading recompute requested for account {}.", LogCategory.API, accountId);
        int assigned = backfillService.backfillAccount(account);
        log.info("{} Internal threading recompute done — assigned {} message(s) in account {}.", LogCategory.API,
                assigned, accountId);
        return ResponseEntity.ok(assigned);
    }
}
