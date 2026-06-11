package org.voxrox.mailbackend.feature.account.service;

import org.jspecify.annotations.Nullable;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.voxrox.mailbackend.exception.AccountNotFoundException;
import org.voxrox.mailbackend.exception.ValidationException;
import org.voxrox.mailbackend.feature.account.dto.AccountConnectionDetails;
import org.voxrox.mailbackend.feature.account.dto.AccountConnectionTestRequest;
import org.voxrox.mailbackend.feature.account.dto.AccountConnectionTestResponse;
import org.voxrox.mailbackend.feature.account.dto.MailServerSettings;
import org.voxrox.mailbackend.feature.account.entity.AccountCredentialEntity;
import org.voxrox.mailbackend.feature.account.entity.AccountEntity;
import org.voxrox.mailbackend.feature.account.entity.MailProviderEntity;
import org.voxrox.mailbackend.feature.account.entity.MailServerConfig;
import org.voxrox.mailbackend.feature.account.repository.AccountRepository;
import org.voxrox.mailbackend.feature.auth.dto.AuthType;
import org.voxrox.mailbackend.feature.mail.service.MailConnectionProbe;

import module java.base;

@Service
public class AccountConnectionTestService {

    private final AccountRepository accountRepository;
    private final AccountProviderService providerService;
    private final AccountCredentialService credentialService;
    private final MailConnectionProbe mailConnectionProbe;
    private final MessageSource messageSource;

    public AccountConnectionTestService(AccountRepository accountRepository, AccountProviderService providerService,
            AccountCredentialService credentialService, MailConnectionProbe mailConnectionProbe,
            MessageSource messageSource) {
        this.accountRepository = accountRepository;
        this.providerService = providerService;
        this.credentialService = credentialService;
        this.mailConnectionProbe = mailConnectionProbe;
        this.messageSource = messageSource;
    }

    @Transactional(readOnly = true)
    public AccountConnectionTestResponse testConnection(AccountConnectionTestRequest request) {
        ServerConfig serverConfig = resolveServerConfig(request);
        CredentialSnapshot credentials = resolveCredentials(request);

        AccountConnectionDetails imapDetails = new AccountConnectionDetails(request.email(), serverConfig.imap().host(),
                serverConfig.imap().port(), serverConfig.imap().useSsl(), request.username(), credentials.secret(),
                credentials.authType(), credentials.oauth2Provider());
        AccountConnectionDetails smtpDetails = new AccountConnectionDetails(request.email(), serverConfig.smtp().host(),
                serverConfig.smtp().port(), serverConfig.smtp().useSsl(), request.username(), credentials.secret(),
                credentials.authType(), credentials.oauth2Provider());

        mailConnectionProbe.testImap(request.accountId(), imapDetails);
        mailConnectionProbe.testSmtp(request.accountId(), smtpDetails);

        return new AccountConnectionTestResponse(true, true, localized("account.connectionTest.success"));
    }

    private String localized(String key, Object... args) {
        Locale locale = LocaleContextHolder.getLocale();
        return messageSource.getMessage(key, args, locale);
    }

    private ServerConfig resolveServerConfig(AccountConnectionTestRequest request) {
        if (request.providerId() != null) {
            MailProviderEntity provider = providerService.loadProviderById(request.providerId());
            return new ServerConfig(toDto(provider.getImapConfig()), toDto(provider.getSmtpConfig()));
        }
        return new ServerConfig(request.imap(), request.smtp());
    }

    private CredentialSnapshot resolveCredentials(AccountConnectionTestRequest request) {
        if (request.password() != null && !request.password().isBlank()) {
            return new CredentialSnapshot(request.password(), AuthType.PASSWORD, null);
        }
        if (request.accountId() == null) {
            throw new ValidationException("Testing a new account requires a password.",
                    "validation.account.testPasswordRequired");
        }

        AccountEntity account = accountRepository.findById(request.accountId())
                .orElseThrow(() -> new AccountNotFoundException(request.accountId()));
        AccountCredentialEntity credentials = credentialService.getCredentials(request.accountId());
        return new CredentialSnapshot(credentialService.getDecryptedSecret(request.accountId()),
                credentials.getAuthType(), account.getOauth2Provider());
    }

    private static MailServerSettings toDto(MailServerConfig config) {
        return new MailServerSettings(config.getHost(), config.getPort(), config.isUseSsl());
    }

    private record ServerConfig(MailServerSettings imap, MailServerSettings smtp) {
    }

    private record CredentialSnapshot(@Nullable String secret, AuthType authType, @Nullable String oauth2Provider) {
    }
}
