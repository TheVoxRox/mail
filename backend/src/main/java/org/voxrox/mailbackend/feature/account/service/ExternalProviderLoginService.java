package org.voxrox.mailbackend.feature.account.service;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.voxrox.mailbackend.feature.account.entity.AccountEntity;
import org.voxrox.mailbackend.feature.account.entity.MailProviderEntity;
import org.voxrox.mailbackend.feature.account.entity.MailServerConfig;
import org.voxrox.mailbackend.feature.account.repository.AccountRepository;
import org.voxrox.mailbackend.feature.auth.dto.AuthType;
import org.voxrox.mailbackend.feature.auth.service.OAuth2TokenServiceRegistry;
import org.voxrox.mailbackend.feature.mail.service.ImapConnectionManager;
import org.voxrox.mailbackend.util.AuditLog;
import org.voxrox.mailbackend.util.LogCategory;
import org.voxrox.mailbackend.util.LogMasker;
import org.voxrox.mailbackend.util.TransactionCallbacks;

/**
 * Account provisioning and reauth bookkeeping for the external OAuth2 provider
 * login flow. Split out of {@link AccountService} (which owns account CRUD):
 * the two share only the account table, not behaviour — this class is driven
 * exclusively by
 * {@link org.voxrox.mailbackend.feature.auth.service.OAuth2LoginService} after
 * a successful provider callback, while CRUD is driven by the REST API.
 */
@Service
public class ExternalProviderLoginService {

    private static final Logger log = LoggerFactory.getLogger(ExternalProviderLoginService.class);

    private final AccountRepository accountRepository;
    private final AccountProviderService providerService;
    private final AccountCredentialService credentialService;
    private final OAuth2TokenServiceRegistry oauth2TokenServiceRegistry;
    private final ImapConnectionManager imapConnectionManager;

    public ExternalProviderLoginService(AccountRepository accountRepository, AccountProviderService providerService,
            AccountCredentialService credentialService, OAuth2TokenServiceRegistry oauth2TokenServiceRegistry,
            ImapConnectionManager imapConnectionManager) {
        this.accountRepository = accountRepository;
        this.providerService = providerService;
        this.credentialService = credentialService;
        this.oauth2TokenServiceRegistry = oauth2TokenServiceRegistry;
        this.imapConnectionManager = imapConnectionManager;
    }

    /**
     * If an account for the given e-mail exists, marks it as requires_reauth.
     * Called from the OAuth flow when the provider did not return a refresh token —
     * existing credentials stay untouched (may still work), but the scheduler will
     * skip the account until the user goes through a clean re-login.
     */
    @Transactional
    public void markRequiresReauthIfExists(String email) {
        accountRepository.findByEmail(email).ifPresent(account -> {
            if (!account.isRequiresReauth()) {
                account.setRequiresReauth(true);
                accountRepository.save(account);
                AuditLog.failure("account_requires_reauth", LogMasker.maskEmail(email),
                        "id=" + account.getId() + " reason=missing_refresh_token");
            }
        });
    }

    /**
     * Creates or updates an account after a successful OAuth2 login flow.
     *
     * <p>
     * Provider routing — all inputs ({@code providerName}, {@code email},
     * {@code externalId}) make up the account identity. Lookup priority:
     * <ol>
     * <li>composite ({@code oauth2_provider}, {@code external_id}) — stable user
     * identifier at the provider, preferred even when the primary e-mail has
     * changed in the meantime;</li>
     * <li>e-mail — upgrade path from a PASSWORD account to OAUTH2 (the user
     * previously had a password and is now setting up OAuth);</li>
     * <li>new account.</li>
     * </ol>
     */
    @Transactional
    public void processExternalProviderLogin(String providerName, String email, @Nullable String name,
            String externalId, String refreshToken) {
        if (providerName == null || providerName.isBlank()) {
            throw new IllegalStateException("providerName must not be null for external provider login");
        }
        if (refreshToken == null || refreshToken.isBlank()) {
            /*
             * Invariant: the caller (OAuth2LoginService) already handles this case. If null
             * reaches this point it is a contract violation, not a user-input error —
             * saveCredentials would otherwise overwrite the encrypted token with null.
             */
            throw new IllegalStateException("refreshToken must not be null for external provider login");
        }
        log.info("{} External login for: {} (provider={})", LogCategory.AUTH, LogMasker.maskEmail(email), providerName);

        MailProviderEntity provider = providerService.resolveProvider(email, null);
        String providerLabel = (provider != null) ? provider.getName() : "External";

        AccountEntity account = findOrCreateExternalAccount(providerName, email, externalId, provider, providerLabel);
        syncExternalAccountMetadata(account, name, providerName, externalId);

        credentialService.saveCredentials(account, email, refreshToken, AuthType.OAUTH2);

        /*
         * After re-login invalidate the cached access token and the IMAP store: the
         * token cache would otherwise keep returning an access_token derived from the
         * old refresh token (different scope/identity) for a while, and a live IMAP
         * store would hold a session authenticated with the old token. Without this the
         * first IMAP call right after re-login would fail and create noise in the logs
         * until the auth-retry path in ImapConnectionManager self-healed the problem.
         *
         * Deferred until AFTER the commit, for the same reasons as
         * purgeConnectionsAfterCommit in deleteAccount: (1) removeConnection blocks on
         * the per-account IMAP lock, which a running sync cycle can hold for minutes —
         * waiting here would keep the SQLite write transaction (credentials row is
         * already written) open and stall every other writer in the app; (2)
         * invalidating the token cache before the new refresh token is committed lets a
         * concurrent auth-retry re-fill the cache from the OLD token still visible in
         * the DB snapshot — and a refresh rejection on that stale token could mark
         * requires_reauth AFTER this transaction cleared it, locking the account out
         * despite a fresh login.
         */
        invalidateRuntimeAuthAfterCommit(providerName, account.getId());

        /*
         * Re-login = explicit user consent with a new refresh token. If the account was
         * locked because of a previously rejected token, unlock it here — the scheduler
         * will start syncing it again.
         */
        if (account.isRequiresReauth()) {
            account.setRequiresReauth(false);
            account.setLastError(null);
            account.setLastErrorCode(null);
            account.setLastErrorArgs(null);
            accountRepository.save(account);
            AuditLog.success("account_reauth_cleared", LogMasker.maskEmail(email), "id=" + account.getId());
        }
    }

    /**
     * Defers the token-cache invalidation and the IMAP store removal until after
     * the commit — the full rationale sits at the call site in
     * processExternalProviderLogin.
     */
    private void invalidateRuntimeAuthAfterCommit(String providerName, Long accountId) {
        TransactionCallbacks.runAfterCommit(() -> invalidateRuntimeAuthQuietly(providerName, accountId));
    }

    /**
     * A failure after the commit must not surface as an error of the already
     * completed re-login — a stale cached token or IMAP store self-heals through
     * the auth-retry path in ImapConnectionManager.
     */
    private void invalidateRuntimeAuthQuietly(String providerName, Long accountId) {
        try {
            oauth2TokenServiceRegistry.resolve(providerName).invalidate(accountId);
            imapConnectionManager.removeConnection(accountId);
        } catch (RuntimeException e) {
            log.warn("{} Post-re-login auth invalidation for account {} failed: {}", LogCategory.ACCOUNT, accountId,
                    e.getMessage());
        }
    }

    private AccountEntity findOrCreateExternalAccount(String providerName, String email, String externalId,
            @Nullable MailProviderEntity provider, String providerLabel) {
        return accountRepository.findByOauth2ProviderAndExternalId(providerName, externalId)
                .or(() -> accountRepository.findByEmail(email)).orElseGet(() -> {
                    String maskedEmail = LogMasker.maskEmail(email);
                    String accountName = accountNameFromEmail(email, providerLabel);
                    log.info("{} Creating new account '{}' for {} ({})", LogCategory.AUTH, accountName, providerLabel,
                            maskedEmail);
                    AccountEntity newAcc = new AccountEntity();
                    newAcc.setAccountName(accountName);
                    newAcc.setEmail(email);
                    newAcc.setActive(true);
                    newAcc.setProvider(provider);
                    /*
                     * Server config is NOT NULL in the DB — denormalizing from the template is
                     * required for a successful insert. The OAuth flow so far falls under
                     * domain-based auto-detection (Gmail), so the provider is usually non-null.
                     * Defensive guard: if auto-detection returned null (an unknown OAuth provider
                     * outside the catalog) we have nothing to fill in — that is an invariant
                     * violation, not a user-input error.
                     */
                    if (provider == null) {
                        throw new IllegalStateException(
                                "External provider login without a template in mail_providers — "
                                        + "add it to the V1 seed or extend auto-detection.");
                    }
                    newAcc.setImapConfig(copyOf(provider.getImapConfig()));
                    newAcc.setSmtpConfig(copyOf(provider.getSmtpConfig()));
                    AccountEntity saved = accountRepository.save(newAcc);
                    AuditLog.success("account_create", maskedEmail,
                            "id=" + saved.getId() + " auth=" + providerLabel + " external_id=" + externalId);
                    return saved;
                });
    }

    private void syncExternalAccountMetadata(AccountEntity account, @Nullable String name, String providerName,
            String externalId) {
        boolean changed = false;
        if (account.getOauth2Provider() == null || !account.getOauth2Provider().equals(providerName)) {
            account.setOauth2Provider(providerName);
            changed = true;
        }
        if (account.getExternalId() == null || !account.getExternalId().equals(externalId)) {
            account.setExternalId(externalId);
            changed = true;
        }
        if (name != null && !name.equals(account.getDisplayName())) {
            account.setDisplayName(name);
            changed = true;
        }
        if (changed) {
            accountRepository.save(account);
        }
    }

    /**
     * Default account name derived from the e-mail domain: the label between "@"
     * and the first dot, with the first letter upper-cased (e.g. info@post.cz ->
     * "Post", user@outlook.com -> "Outlook"). Mirrors the wizard's client-side
     * naming (AccountForm.svelte#defaultAccountName) so that OAuth- and
     * IMAP-created accounts read the same. Falls back to {@code fallback} when the
     * address carries no usable domain label.
     */
    private static String accountNameFromEmail(String email, String fallback) {
        if (email != null) {
            int at = email.indexOf('@');
            if (at >= 0) {
                String domain = email.substring(at + 1);
                int dot = domain.indexOf('.');
                String label = (dot >= 0 ? domain.substring(0, dot) : domain).trim();
                if (!label.isEmpty()) {
                    return Character.toUpperCase(label.charAt(0)) + label.substring(1);
                }
            }
        }
        return fallback;
    }

    /**
     * Copies a provider template's server config into a standalone value for
     * denormalization onto the account (runtime then reads exclusively from the
     * embedded columns, so a later template change does not disrupt the account).
     */
    private static MailServerConfig copyOf(MailServerConfig src) {
        return new MailServerConfig(src.getHost(), src.getPort(), src.isUseSsl());
    }
}
