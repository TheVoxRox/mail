package org.voxrox.mailbackend.feature.account.event;

/**
 * Published when an account's {@code requires_reauth} flag is raised: the OAuth
 * refresh token was rejected, or IMAP authentication failed persistently even
 * after a fresh token.
 *
 * <p>
 * It exists so the account's pooled IMAP connections can be closed. From this
 * moment nothing will use them: {@code MailSyncScheduler} selects on
 * {@code findByActiveTrueAndRequiresReauthFalse}, and
 * {@code ImapConnectionManager.requireUsableAccount} rejects every other entry
 * point. That is the same leak deactivation had before #430 — one idle
 * {@code Store} per lane until the process ends — on the other half of the same
 * predicate.
 *
 * <p>
 * An event rather than a direct call, because both writers sit on the wrong
 * side of something. {@code ImapConnectionManager} already depends on
 * {@code OAuth2TokenServiceRegistry}, so a call back from the token service
 * would close a dependency cycle; and the manager's own write happens while it
 * holds one lane's connection lock, which the purge must not be taken under
 * (lock-order rule 2 in {@code backend/docs/CONCURRENCY.md} — never hold both
 * lanes at once). A listener on {@code mailEventExecutor} holds neither.
 *
 * <p>
 * Carries the account id alone. There is no notification, no timestamp and no
 * ordering to preserve — the user-visible half of this state change is
 * {@code last_error}, which travels its own way.
 */
public record AccountRequiresReauthEvent(Long accountId) {
}
