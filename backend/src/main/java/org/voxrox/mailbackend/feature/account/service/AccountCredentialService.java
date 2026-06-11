package org.voxrox.mailbackend.feature.account.service;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.voxrox.mailbackend.core.security.CryptoService;
import org.voxrox.mailbackend.feature.account.entity.AccountCredentialEntity;
import org.voxrox.mailbackend.feature.account.entity.AccountEntity;
import org.voxrox.mailbackend.feature.account.repository.AccountCredentialRepository;
import org.voxrox.mailbackend.feature.auth.dto.AuthType;
import org.voxrox.mailbackend.util.AuditLog;
import org.voxrox.mailbackend.util.LogCategory;
import org.voxrox.mailbackend.util.LogMasker;

@Service
public class AccountCredentialService {

    private static final Logger log = LoggerFactory.getLogger(AccountCredentialService.class);

    private final AccountCredentialRepository credentialRepository;
    private final CryptoService cryptoService;

    public AccountCredentialService(AccountCredentialRepository credentialRepository, CryptoService cryptoService) {
        this.credentialRepository = credentialRepository;
        this.cryptoService = cryptoService;
    }

    /**
     * Creates or updates credentials for an account. Encapsulates the encryption
     * logic.
     */
    @Transactional
    public void saveCredentials(AccountEntity account, String username, String secret, AuthType authType) {
        log.debug("{} Saving credentials for account id={}, type={}", LogCategory.SECURITY, account.getId(), authType);

        AccountCredentialEntity existing = credentialRepository.findById(account.getId()).orElse(null);
        boolean isUpdate = existing != null;

        AccountCredentialEntity credentials;
        // Change detection for the audit log — compared BEFORE overwriting fields.
        boolean usernameChanged = false;
        boolean authTypeChanged = false;
        boolean secretChanged = false;
        boolean secretCleared = false;

        if (existing != null) {
            credentials = existing;
            usernameChanged = !java.util.Objects.equals(existing.getUsername(), username);
            authTypeChanged = !java.util.Objects.equals(existing.getAuthType(), authType);
            boolean hasNewSecret = secret != null && !secret.isBlank();
            boolean hadOldSecret = existing.getEncryptedPassword() != null
                    && !existing.getEncryptedPassword().isBlank();
            // True: the secret actually changes (a new one stored = new ciphertext due to
            // IV).
            secretChanged = hasNewSecret;
            secretCleared = !hasNewSecret && hadOldSecret;
        } else {
            credentials = new AccountCredentialEntity();
            credentials.setAccount(account);
        }

        credentials.setUsername(username);
        credentials.setAuthType(authType);

        /*
         * Empty secret → empty string in the DB (the column is NOT NULL; blank is
         * treated as "no secret" by all readers). After an auth-type switch we must not
         * leave an old password lingering here (e.g. PASSWORD → OAUTH2 transition).
         */
        if (secret != null && !secret.isBlank()) {
            cryptoService.evictCache(account.getId());
            // encrypt() returns null only for blank input, which is excluded here.
            String encrypted = java.util.Objects.requireNonNull(cryptoService.encrypt(secret, account.getId()));
            credentials.setEncryptedPassword(encrypted);
        } else {
            log.trace("{} Secret is empty, clearing encryptedPassword for account id={}", LogCategory.SECURITY,
                    account.getId());
            credentials.setEncryptedPassword("");
        }

        credentialRepository.save(credentials);

        /*
         * Audit log: only updates on an existing account — creation is audited in
         * AccountService.createAccount / findOrCreateExternalAccount.
         */
        if (isUpdate && (usernameChanged || authTypeChanged || secretChanged || secretCleared)) {
            String maskedEmail = LogMasker.maskEmail(account.getEmail());
            StringBuilder detail = new StringBuilder("id=").append(account.getId());
            if (authTypeChanged) {
                detail.append(" auth_type=").append(authType);
            }
            if (usernameChanged) {
                detail.append(" username_changed");
            }
            if (secretChanged) {
                detail.append(" secret_rotated");
            }
            if (secretCleared) {
                detail.append(" secret_cleared");
            }
            AuditLog.success("credentials_update", maskedEmail, detail.toString());
        }
    }

    /**
     * Returns the decrypted secret (password or refresh token).
     */
    @Transactional(readOnly = true)
    public @Nullable String getDecryptedSecret(Long accountId) {
        return credentialRepository.findById(accountId).map(creds -> creds.getEncryptedPassword())
                .filter(pwd -> pwd != null && !pwd.isBlank()).map(pwd -> cryptoService.decrypt(pwd, accountId))
                .orElse(null);
    }

    @Transactional(readOnly = true)
    public AccountCredentialEntity getCredentials(Long accountId) {
        // A missing credentials row for an existing account is a data-integrity
        // breach (the row is created with the account and deleted with it), not
        // a user-recoverable 404.
        return credentialRepository.findById(accountId)
                .orElseThrow(() -> new IllegalStateException("Credentials not found for account " + accountId));
    }

    /**
     * Deletes credentials and clears the security cache.
     */
    @Transactional
    public void deleteCredentials(Long accountId) {
        log.debug("{} Deleting credentials and cache for account id={}", LogCategory.ACCOUNT, accountId);
        cryptoService.evictCache(accountId);
        credentialRepository.deleteById(accountId);
        /*
         * Note: we do not have the e-mail here, an audit with actor=account=ID is
         * sufficient — the operation typically accompanies account_delete, which has
         * already recorded the actor with the e-mail.
         */
        AuditLog.success("credentials_delete", "account=" + accountId, "cache_evicted");
    }
}
