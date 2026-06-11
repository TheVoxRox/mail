package org.voxrox.mailbackend.feature.account.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.voxrox.mailbackend.exception.AccountNotFoundException;
import org.voxrox.mailbackend.feature.account.dto.AccountConnectionDetails;
import org.voxrox.mailbackend.feature.account.entity.AccountCredentialEntity;
import org.voxrox.mailbackend.feature.account.entity.AccountEntity;
import org.voxrox.mailbackend.feature.account.entity.MailServerConfig;
import org.voxrox.mailbackend.feature.account.repository.AccountRepository;

/**
 * Builds the {@link AccountConnectionDetails} bundle for the mail layer: takes
 * host/port/SSL directly from the embedded IMAP or SMTP config on the account
 * (denormalization — no resolver, no fallback), decrypts the password / OAuth2
 * refresh token and returns a complete snapshot for opening a connection.
 * <p>
 * IMAP and SMTP have separate methods on purpose: the protocols use different
 * host/port/SSL, so a single generic accessor would silently hand IMAP
 * coordinates to the SMTP transport (or vice versa). Callers must pick the one
 * matching the connection they are about to open.
 * <p>
 * The class exists to break the circular dependency between
 * {@link AccountService} and {@code ImapConnectionManager}: consumers in
 * {@code feature/mail} (IMAP, SMTP) need only this single method, not the
 * entire {@link AccountService} CRUD interface. That removes the need for
 * {@code @Lazy} proxies on both sides.
 * <p>
 * The method is {@code readOnly} — no state mutation, no audit log; the audit
 * trail of credential writes is kept by {@link AccountCredentialService}.
 */
@Service
public class AccountConnectionDetailsService {

    private final AccountRepository accountRepository;
    private final AccountCredentialService credentialService;

    public AccountConnectionDetailsService(AccountRepository accountRepository,
            AccountCredentialService credentialService) {
        this.accountRepository = accountRepository;
        this.credentialService = credentialService;
    }

    @Transactional(readOnly = true)
    public AccountConnectionDetails getImapConnectionDetails(Long accountId) {
        return buildDetails(accountId, true);
    }

    @Transactional(readOnly = true)
    public AccountConnectionDetails getSmtpConnectionDetails(Long accountId) {
        return buildDetails(accountId, false);
    }

    private AccountConnectionDetails buildDetails(Long accountId, boolean imap) {
        AccountEntity account = accountRepository.findById(accountId)
                .orElseThrow(() -> new AccountNotFoundException(accountId));
        AccountCredentialEntity credentials = credentialService.getCredentials(accountId);
        MailServerConfig server = imap ? account.getImapConfig() : account.getSmtpConfig();

        return new AccountConnectionDetails(account.getEmail(), server.getHost(), server.getPort(), server.isUseSsl(),
                credentials.getUsername(), credentialService.getDecryptedSecret(accountId), credentials.getAuthType(),
                account.getOauth2Provider());
    }
}
