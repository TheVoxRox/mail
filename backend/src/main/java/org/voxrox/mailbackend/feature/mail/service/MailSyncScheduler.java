package org.voxrox.mailbackend.feature.mail.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.voxrox.mailbackend.feature.account.entity.AccountEntity;
import org.voxrox.mailbackend.feature.account.repository.AccountRepository;
import org.voxrox.mailbackend.util.LogCategory;
import org.voxrox.mailbackend.util.LogMasker;

import module java.base;

@Service
public class MailSyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(MailSyncScheduler.class);
    private final AccountRepository accountRepository;
    private final MailSyncService mailSyncService;

    public MailSyncScheduler(AccountRepository accountRepository, MailSyncService mailSyncService) {
        this.accountRepository = accountRepository;
        this.mailSyncService = mailSyncService;
    }

    /*
     * No inline fallback in either placeholder, deliberately. Both keys already
     * have a default on the SyncProperties component that binds them, and a second
     * one here is a default nothing reconciles with the first: they are written in
     * different syntaxes (`5m` there, `PT5M` here), so they can drift apart while
     * both still read as correct. The shipped application.properties sets both
     * keys, so the only way to reach a fallback is to delete a line from it — and
     * then failing to start beats silently running at a rate no one chose.
     */
    @Transactional(readOnly = true)
    @Scheduled(initialDelayString = "${mail.client.sync.initial-delay}", fixedRateString = "${mail.client.sync.interval}")
    public void syncAccounts() {
        log.info("{} Starting scheduled sync cycle for all active accounts.", LogCategory.SYNC);

        /*
         * Intentionally NOT picking up every active account — we drop those with
         * requires_reauth, whose OAuth refresh token the provider rejected. Without
         * this filter every sync tick would POST to /token, get a 400 back and fill the
         * logs with noise (and eventually earn a rate-limit / abuse flag from Google).
         */
        List<AccountEntity> activeAccounts = accountRepository.findByActiveTrueAndRequiresReauthFalse();

        if (activeAccounts.isEmpty()) {
            log.info("{} No active accounts to sync.", LogCategory.SYNC);
            return;
        }

        for (AccountEntity account : activeAccounts) {
            try {
                log.info("{} Scheduled sync for: {}", LogCategory.SYNC, LogMasker.maskEmail(account.getEmail()));
                mailSyncService.syncAllFolders(account);
            } catch (Exception e) {
                /*
                 * Per-account isolation: one broken account must not bring down the cycle for
                 * the others. Note: the last arg MUST be `e` WITHOUT a corresponding {}
                 * placeholder, otherwise SLF4J calls toString() instead of logging the stack
                 * trace.
                 */
                log.error("{} Critical scheduler error for account {}", LogCategory.SYNC,
                        LogMasker.maskEmail(account.getEmail()), e);
            }
        }

        log.debug("{} Scheduled sync cycle finished.", LogCategory.SYNC);
    }
}
