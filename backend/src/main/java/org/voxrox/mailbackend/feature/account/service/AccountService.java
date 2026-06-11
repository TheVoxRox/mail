package org.voxrox.mailbackend.feature.account.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.voxrox.mailbackend.exception.AccountAlreadyExistsException;
import org.voxrox.mailbackend.exception.AccountNotFoundException;
import org.voxrox.mailbackend.exception.ValidationException;
import org.voxrox.mailbackend.feature.account.dto.*;
import org.voxrox.mailbackend.feature.account.entity.*;
import org.voxrox.mailbackend.feature.account.mapper.AccountMapper;
import org.voxrox.mailbackend.feature.account.repository.AccountRepository;
import org.voxrox.mailbackend.feature.auth.dto.AuthType;
import org.voxrox.mailbackend.feature.auth.service.OAuth2TokenServiceRegistry;
import org.voxrox.mailbackend.feature.mail.service.ImapConnectionManager;
import org.voxrox.mailbackend.util.AuditLog;
import org.voxrox.mailbackend.util.LogCategory;
import org.voxrox.mailbackend.util.LogMasker;

import module java.base;

@Service
public class AccountService {

    private static final Logger log = LoggerFactory.getLogger(AccountService.class);

    private final AccountRepository accountRepository;
    private final AccountProviderService providerService;
    private final AccountCredentialService credentialService;
    private final ImapConnectionManager imapConnectionManager;
    private final AccountMapper accountMapper;
    private final OAuth2TokenServiceRegistry oauth2TokenServiceRegistry;

    public AccountService(AccountRepository accountRepository, AccountProviderService providerService,
            AccountCredentialService credentialService, ImapConnectionManager imapConnectionManager,
            AccountMapper accountMapper, OAuth2TokenServiceRegistry oauth2TokenServiceRegistry) {
        this.accountRepository = accountRepository;
        this.providerService = providerService;
        this.credentialService = credentialService;
        this.imapConnectionManager = imapConnectionManager;
        this.accountMapper = accountMapper;
        this.oauth2TokenServiceRegistry = oauth2TokenServiceRegistry;
    }

    @Transactional(readOnly = true)
    public List<AccountResponse> listAllAccounts() {
        log.debug("{} Listing all accounts.", LogCategory.ACCOUNT);
        return accountRepository.findAllWithDetails().stream().map(accountMapper::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public AccountResponse getAccountById(Long id) {
        log.debug("{} Loading account detail id={}", LogCategory.ACCOUNT, id);
        return accountMapper.toResponse(getAccountOrThrow(id));
    }

    @Transactional
    public AccountResponse createAccount(AccountCreateRequest request) {
        log.info("{} Creating new account: {}", LogCategory.ACCOUNT, request.accountName());

        if (accountRepository.existsByEmail(request.email())) {
            throw new AccountAlreadyExistsException(request.email());
        }

        AccountEntity account = new AccountEntity();
        account.setAccountName(request.accountName());
        account.setDisplayName(request.displayName());
        account.setEmail(request.email());
        account.setActive(true);

        applyServerConfig(account, request.providerId(), request.imap(), request.smtp());

        account = accountRepository.save(account);
        credentialService.saveCredentials(account, request.username(), request.password(), AuthType.PASSWORD);

        AuditLog.success("account_create", LogMasker.maskEmail(account.getEmail()),
                "id=" + account.getId() + " auth=PASSWORD");
        return accountMapper.toResponse(account);
    }

    @Transactional
    public AccountResponse updateAccount(Long accountId, AccountUpdateRequest request) {
        log.info("{} Updating account id={}", LogCategory.ACCOUNT, accountId);
        AccountEntity account = getAccountOrThrow(accountId);

        ensureEmailNotTaken(account, request.email(), accountId);

        boolean wouldRotateCredentials = request.password() != null && !request.password().isBlank();
        ensureCredentialsNotMutatedForOAuth2(accountId, wouldRotateCredentials, LogMasker.maskEmail(account.getEmail()),
                "PUT");

        account.setAccountName(request.accountName());
        account.setDisplayName(request.displayName());
        account.setEmail(request.email());
        account.setActive(request.active());

        /*
         * PUT = full replacement: the cross-field validator (@AssertTrue on the DTO)
         * already ensures that exactly one branch is populated — either providerId, or
         * a complete imap+smtp pair. Server config is overwritten either way.
         */
        applyServerConfig(account, request.providerId(), request.imap(), request.smtp());

        AccountEntity saved = accountRepository.save(account);

        /*
         * Password is optional in PUT semantics (see AccountUpdateRequest.password
         * javadoc): empty/null leaves existing credentials untouched. Without this
         * guard saveCredentials would overwrite encryptedPassword with null and the
         * user would silently lose access to the PASSWORD account.
         */
        if (wouldRotateCredentials) {
            credentialService.saveCredentials(saved, request.username(), request.password(), AuthType.PASSWORD);
        }

        AuditLog.success("account_update", LogMasker.maskEmail(saved.getEmail()),
                "id=" + saved.getId() + " credentials_rotated=" + wouldRotateCredentials);
        return accountMapper.toResponse(saved);
    }

    /**
     * Applies server config to the account based on the request. Called from create
     * / PUT.
     * <p>
     * If {@code providerId != null}, loads the template and <b>copies</b> its
     * IMAP/SMTP config into the account (denormalization) — runtime then reads
     * exclusively from the embedded columns and changing the template does not
     * disrupt existing accounts. If on the other hand {@code imap}/{@code smtp} is
     * supplied explicitly, the provider is cleared and the account keeps a custom
     * config. The cross-field validator in the DTO ensures XOR (exactly one
     * branch), so the {@code else} branch always has both settings non-null.
     */
    private void applyServerConfig(AccountEntity account, Long providerId, MailServerSettings imap,
            MailServerSettings smtp) {
        if (providerId != null) {
            MailProviderEntity provider = providerService.loadProviderById(providerId);
            account.setProvider(provider);
            account.setImapConfig(copyOf(provider.getImapConfig()));
            account.setSmtpConfig(copyOf(provider.getSmtpConfig()));
        } else {
            account.setProvider(null);
            account.setImapConfig(toEntity(imap));
            account.setSmtpConfig(toEntity(smtp));
        }
    }

    private static MailServerConfig copyOf(MailServerConfig src) {
        return new MailServerConfig(src.getHost(), src.getPort(), src.isUseSsl());
    }

    private static MailServerConfig toEntity(MailServerSettings dto) {
        return new MailServerConfig(dto.host(), dto.port(), dto.useSsl());
    }

    /**
     * Ensures {@code newEmail} is not taken by another account. Null-safe — if
     * {@code newEmail == null} (PATCH without email change) the helper is a no-op.
     * The unnecessary DB roundtrip is skipped when the email is unchanged (see
     * {@link #isEmailChanged}).
     */
    private void ensureEmailNotTaken(AccountEntity current, String newEmail, Long accountId) {
        if (newEmail == null) {
            return;
        }
        if (isEmailChanged(current.getEmail(), newEmail)
                && accountRepository.existsByEmailAndIdNot(newEmail, accountId)) {
            throw new AccountAlreadyExistsException(newEmail);
        }
    }

    /**
     * Prevents PUT/PATCH on an OAuth2 account from overwriting the encrypted
     * refresh token with a password (and switching {@code auth_type} to PASSWORD).
     * Without this guard, sending a PATCH with {@code username="x"} would be enough
     * for {@code saveCredentials} to set {@code encryptedPassword=null} (no
     * password in the request) and the type to PASSWORD — the OAuth2 integration
     * would be silently destroyed and the user would have to sign in again through
     * the provider login flow.
     * <p>
     * OAuth2 accounts are managed exclusively via
     * {@code /oauth2/authorization/{provider}} and the callback into
     * {@code processExternalProviderLogin}. CRUD on {@code /api/v1/accounts/{id}}
     * may only change metadata (account name, displayName, provider, active flag) —
     * credentials stay read-only.
     */
    private void ensureCredentialsNotMutatedForOAuth2(Long accountId, boolean wouldMutate, String maskedEmail,
            String via) {
        if (!wouldMutate) {
            return;
        }
        AccountCredentialEntity existing = credentialService.getCredentials(accountId);
        if (existing.getAuthType() == AuthType.OAUTH2) {
            /*
             * Subtle security event: someone (FE bug or malicious client) is attempting to
             * overwrite OAuth2 credentials via /api/v1/accounts CRUD. The client gets 400,
             * but we also want an append-only trail for post-mortems.
             */
            AuditLog.failure("oauth2_credentials_mutation_blocked", maskedEmail, "id=" + accountId + " via=" + via);
            throw new ValidationException(
                    "OAuth2 accounts cannot be updated by changing the password or username. "
                            + "Sign in with OAuth2 again to change credentials.",
                    "validation.account.oauth2CredentialsReadOnly");
        }
    }

    /**
     * Deletes an account. The logic lives in a single @Transactional method so the
     * transactional boundary is not lost through self-invocation.
     */
    @Transactional
    public void deleteAccount(Long accountId) {
        AccountEntity account = getAccountOrThrow(accountId);
        String maskedEmail = LogMasker.maskEmail(account.getEmail());

        log.info("{} START: Deleting account id={}. Cleaning up connections.", LogCategory.ACCOUNT, accountId);

        try {
            /*
             * Best-effort revoke at the OAuth2 provider before deleting credentials —
             * otherwise we would lose the encrypted refresh token. revokeToken has its own
             * try/catch (log.warn + AuditLog.failure), so a provider outage does not break
             * the transaction and the delete still goes through.
             */
            AccountCredentialEntity creds = credentialService.getCredentials(accountId);
            if (creds.getAuthType() == AuthType.OAUTH2 && account.getOauth2Provider() != null) {
                oauth2TokenServiceRegistry.resolve(account.getOauth2Provider()).revokeToken(account);
            }

            credentialService.deleteCredentials(accountId);
            accountRepository.deleteById(accountId);

            purgeConnectionsAfterCommit(accountId);

            log.info("{} DONE: Account id={} data removed from DB.", LogCategory.ACCOUNT, accountId);
            AuditLog.success("account_delete", maskedEmail, "id=" + accountId);
        } catch (RuntimeException e) {
            AuditLog.failure("account_delete", maskedEmail, "id=" + accountId + " " + e.getClass().getSimpleName());
            throw e;
        }
    }

    /**
     * Defers the IMAP connection cleanup until after the commit. purgeAccount waits
     * on the per-account connection lock, which a running sync cycle can hold for
     * minutes (a large folder fetch). Running that wait inside the transaction kept
     * the SQLite write transaction open the whole time — the single-writer DB then
     * blocks every other write in the application. A sync racing the delete simply
     * fails on the missing account row and its last_error UPDATE matches no row
     * (no-op).
     */
    private void purgeConnectionsAfterCommit(Long accountId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            // No active transaction (plain unit-test invocation) — purge inline.
            purgeConnectionsQuietly(accountId);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                purgeConnectionsQuietly(accountId);
            }
        });
    }

    /**
     * A purge failure after the commit must not surface as an error of the
     * already-completed delete — the orphaned connection dies with the process at
     * the latest ({@code @PreDestroy} shutdown in ImapConnectionManager).
     */
    private void purgeConnectionsQuietly(Long accountId) {
        try {
            imapConnectionManager.purgeAccount(accountId);
        } catch (RuntimeException e) {
            log.warn("{} Post-delete connection cleanup for account {} failed: {}", LogCategory.ACCOUNT, accountId,
                    e.getMessage());
        }
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
    public void processExternalProviderLogin(String providerName, String email, String name, String externalId,
            String refreshToken) {
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
        syncExternalAccountMetadata(account, name, providerName, externalId, providerLabel);

        credentialService.saveCredentials(account, email, refreshToken, AuthType.OAUTH2);

        /*
         * After re-login invalidate the cached access token and the IMAP store: the
         * token cache would otherwise keep returning an access_token derived from the
         * old refresh token (different scope/identity) for a while, and a live IMAP
         * store would hold a session authenticated with the old token. Without this the
         * first IMAP call right after re-login would fail and create noise in the logs
         * until the auth-retry path in ImapConnectionManager self-healed the problem.
         */
        oauth2TokenServiceRegistry.resolve(providerName).invalidate(account.getId());
        imapConnectionManager.removeConnection(account.getId());

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

    private AccountEntity findOrCreateExternalAccount(String providerName, String email, String externalId,
            MailProviderEntity provider, String providerLabel) {
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

    private void syncExternalAccountMetadata(AccountEntity account, String name, String providerName, String externalId,
            String providerLabel) {
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

    public AccountEntity getAccountOrThrow(Long accountId) {
        return accountRepository.findById(accountId).orElseThrow(() -> new AccountNotFoundException(accountId));
    }

    private static boolean isEmailChanged(String current, String next) {
        return current == null || !current.equalsIgnoreCase(next);
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
}
