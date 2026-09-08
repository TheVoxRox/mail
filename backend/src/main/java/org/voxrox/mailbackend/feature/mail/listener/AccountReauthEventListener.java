package org.voxrox.mailbackend.feature.mail.listener;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.voxrox.mailbackend.feature.account.event.AccountRequiresReauthEvent;
import org.voxrox.mailbackend.feature.mail.service.ImapConnectionManager;
import org.voxrox.mailbackend.util.LogCategory;

/**
 * Closes the pooled IMAP connections of an account that has just been marked as
 * requiring a new sign-in.
 *
 * <p>
 * Separate from {@link MailSyncEventListener} because it is not a sync event
 * and shares none of its inputs: no SSE broadcast, no folder cache, nothing the
 * user sees. What it reacts to is an account leaving the set of accounts
 * anything will connect for.
 */
@Component
public class AccountReauthEventListener {

    private static final Logger log = LoggerFactory.getLogger(AccountReauthEventListener.class);

    private final ImapConnectionManager imapConnectionManager;

    public AccountReauthEventListener(ImapConnectionManager imapConnectionManager) {
        this.imapConnectionManager = imapConnectionManager;
    }

    /*
     * Asynchronous is the point here, not a nicety. The IMAP-side publisher raises
     * the flag from inside executeLocked, holding that account's lane lock, and
     * removeConnection takes both lanes' locks in turn — running it inline would
     * put two lane locks in one thread's hands, which lock-order rule 2 in
     * CONCURRENCY.md forbids. On mailEventExecutor this thread holds nothing and
     * waits for the in-flight action to finish, exactly like the delete path's
     * post-commit purge.
     *
     * mailEventExecutor also documents its handlers as short; this one can park on
     * a lane lock for as long as an IMAP operation runs. That is affordable because
     * the executor is unbounded virtual threads, and a virtual thread parked on a
     * ReentrantLock unmounts and holds no carrier.
     */
    @Async("mailEventExecutor")
    @EventListener
    public void handleRequiresReauth(AccountRequiresReauthEvent event) {
        /*
         * removeConnection rather than purgeAccount: the account still exists and may
         * be signed in again at any moment. Closing its connections is the whole job —
         * the interactive-lane cooldown purgeAccount also drops belongs to a deleted
         * account, and expires on its own here.
         */
        try {
            imapConnectionManager.removeConnection(event.accountId());
            log.info("{} Closed pooled IMAP connections of account {}: it requires a new sign-in and nothing "
                    + "will connect for it until then.", LogCategory.IMAP, event.accountId());
        } catch (RuntimeException e) {
            /*
             * Best-effort, like every other purge: the account is already unusable, the
             * flag is already written, and an orphaned connection dies with the process at
             * the latest (@PreDestroy in ImapConnectionManager).
             */
            log.warn("{} Could not close the pooled IMAP connections of account {}: {}", LogCategory.IMAP,
                    event.accountId(), e.getMessage());
        }
    }
}
