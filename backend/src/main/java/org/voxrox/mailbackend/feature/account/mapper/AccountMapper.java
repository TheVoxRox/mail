package org.voxrox.mailbackend.feature.account.mapper;

import java.util.Locale;
import java.util.Map;

import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;
import org.voxrox.mailbackend.feature.account.AccountLastErrorCode;
import org.voxrox.mailbackend.feature.account.AccountLastErrorJson;
import org.voxrox.mailbackend.feature.account.dto.AccountResponse;
import org.voxrox.mailbackend.feature.account.entity.AccountCredentialEntity;
import org.voxrox.mailbackend.feature.account.entity.AccountEntity;
import org.voxrox.mailbackend.feature.account.entity.MailProviderEntity;
import org.voxrox.mailbackend.feature.account.entity.MailServerConfig;
import org.voxrox.mailbackend.feature.auth.dto.AuthType;

/**
 * After denormalizing the server config onto the account, the mapper has no
 * 3-level fallback — the runtime IMAP/SMTP configuration lives directly on the
 * entity (NOT NULL embedded columns). The provider remains only as an optional
 * reference for the UI label (Gmail / Seznam / Outlook), or "Custom" when no
 * provider is associated (provider == null).
 */
@Component
public class AccountMapper {

    private final MessageSource messageSource;

    public AccountMapper(MessageSource messageSource) {
        this.messageSource = messageSource;
    }

    public AccountResponse toResponse(AccountEntity entity) {
        MailProviderEntity p = entity.getProvider();
        AccountCredentialEntity creds = entity.getCredentials();

        String username = (creds != null) ? creds.getUsername() : "unknown";
        AuthType authType = (creds != null) ? creds.getAuthType() : null;

        MailServerConfig imap = entity.getImapConfig();
        MailServerConfig smtp = entity.getSmtpConfig();

        Map<String, String> lastErrorArgs = AccountLastErrorJson.read(entity.getLastErrorArgs());
        String localizedLastError = localizedLastError(entity.getLastErrorCode(), lastErrorArgs, entity.getLastError());

        return new AccountResponse(entity.getId(), entity.getAccountName(), entity.getEmail(), entity.getDisplayName(),
                (p != null) ? p.getId() : null, providerName(p), imap.getHost(), imap.getPort(), imap.isUseSsl(),
                smtp.getHost(), smtp.getPort(), smtp.isUseSsl(), username, authType, entity.getOauth2Provider(),
                entity.isActive(), entity.isRequiresReauth(), entity.getLastSyncAt(), localizedLastError,
                entity.getLastErrorCode(), lastErrorArgs);
    }

    private String providerName(MailProviderEntity provider) {
        if (provider != null) {
            return provider.getName();
        }
        Locale locale = LocaleContextHolder.getLocale();
        return messageSource.getMessage("account.provider.custom", new Object[0], "Custom", locale);
    }

    private String localizedLastError(String code, Map<String, String> args, String fallback) {
        return AccountLastErrorCode.fromCode(code).map(errorCode -> {
            Locale locale = LocaleContextHolder.getLocale();
            return messageSource.getMessage(errorCode.messageKey(), errorCode.messageArgs(args), fallback, locale);
        }).orElse(fallback);
    }
}
