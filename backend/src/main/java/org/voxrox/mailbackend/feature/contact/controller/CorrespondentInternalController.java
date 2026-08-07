package org.voxrox.mailbackend.feature.contact.controller;

import jakarta.validation.constraints.Positive;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.voxrox.mailbackend.feature.account.entity.AccountEntity;
import org.voxrox.mailbackend.feature.account.service.AccountService;
import org.voxrox.mailbackend.feature.contact.service.CorrespondentBackfillService;
import org.voxrox.mailbackend.util.LogCategory;

import io.swagger.v3.oas.annotations.Hidden;

/**
 * Internal hook for rebuilding the harvested-address cache on demand.
 *
 * <p>
 * {@code correspondent} is a derived cache, and this endpoint is what makes
 * that claim actionable. The startup backfill is guarded on the cache being
 * empty for the account — it has to be, because harvesting leaves no mark on
 * the message row to re-run from — so two states are unreachable for it: a
 * first run interrupted half way, and an account that was in
 * {@code requires_reauth} when it fired and whose sync has since written the
 * first rows. In both the user would keep a partial cache with no way to ask
 * for a complete one.
 *
 * <p>
 * Same shape as {@code /api/internal/threading/recompute}: hidden from the
 * public OpenAPI spec, protected by the {@code X-API-KEY} covering every
 * {@code /api/internal/*} route via
 * {@link org.voxrox.mailbackend.core.security.ApiKeyFilter}, and run on the
 * calling thread so the caller gets the result rather than a 202.
 */
@Hidden
@RestController
@Validated
public class CorrespondentInternalController {

    private static final Logger log = LoggerFactory.getLogger(CorrespondentInternalController.class);

    private final CorrespondentBackfillService backfillService;
    private final AccountService accountService;

    public CorrespondentInternalController(CorrespondentBackfillService backfillService,
            AccountService accountService) {
        this.backfillService = backfillService;
        this.accountService = accountService;
    }

    /**
     * Drops the account's harvested addresses and rebuilds them from
     * {@code messages}. Also the way to apply a changed robot list or ranking to
     * rows harvested under the old rules.
     *
     * @param accountId
     *            account to rebuild; must exist
     * @return {@code 200 OK} with the number of messages harvested
     */
    @PostMapping("/api/internal/correspondent/rebuild")
    public ResponseEntity<Integer> rebuildCorrespondents(
            @RequestParam @Positive(message = "{validation.positive}") Long accountId) {
        AccountEntity account = accountService.getAccountOrThrow(accountId);
        log.info("{} Internal correspondent rebuild requested for account {}.", LogCategory.API, accountId);
        int harvested = backfillService.rebuildAccount(account);
        log.info("{} Internal correspondent rebuild done — harvested {} message(s) in account {}.", LogCategory.API,
                harvested, accountId);
        return ResponseEntity.ok(harvested);
    }
}
