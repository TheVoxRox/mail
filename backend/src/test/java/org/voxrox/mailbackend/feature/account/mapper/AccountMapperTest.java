package org.voxrox.mailbackend.feature.account.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.Locale;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.voxrox.mailbackend.feature.account.AccountLastErrorCode;
import org.voxrox.mailbackend.feature.account.AccountLastErrorJson;
import org.voxrox.mailbackend.feature.account.dto.AccountResponse;
import org.voxrox.mailbackend.feature.account.entity.AccountCredentialEntity;
import org.voxrox.mailbackend.feature.account.entity.AccountEntity;
import org.voxrox.mailbackend.feature.account.entity.MailProviderEntity;
import org.voxrox.mailbackend.feature.account.entity.MailServerConfig;
import org.voxrox.mailbackend.feature.auth.dto.AuthType;

/**
 * Unit tests for {@link AccountMapper}. After the server config denormalization
 * onto the account, the mapper only reads the embedded {@code imapConfig} /
 * {@code smtpConfig} from the entity and exposes {@code providerName} as either
 * the template label or "Custom" for custom configs.
 */
@DisplayName("AccountMapper")
class AccountMapperTest {

    private AccountMapper mapper;

    @BeforeEach
    void setUp() {
        ResourceBundleMessageSource messageSource = new ResourceBundleMessageSource();
        messageSource.setBasename("messages");
        messageSource.setDefaultEncoding("UTF-8");
        messageSource.setFallbackToSystemLocale(false);
        mapper = new AccountMapper(messageSource);
        LocaleContextHolder.setLocale(Locale.forLanguageTag("cs"));
    }

    @AfterEach
    void tearDown() {
        // Tests here pin the thread-local locale; reset it so the chosen locale
        // does not leak into later tests sharing the surefire fork (e.g.
        // AccountConnectionTestServiceTest, which resolves messages under the
        // ambient locale).
        LocaleContextHolder.resetLocaleContext();
    }

    private AccountEntity createAccount() {
        var account = new AccountEntity();
        account.setId(1L);
        account.setAccountName("Test account");
        account.setEmail("test@example.com");
        account.setDisplayName("Test User");
        account.setActive(true);
        account.setRequiresReauth(false);
        account.setLastSyncAt(LocalDateTime.of(2025, 1, 15, 10, 30));
        account.setLastError(null);
        // Embedded server config — after denormalization always NOT NULL on the
        // account.
        account.setImapConfig(new MailServerConfig("imap.account.cz", 993, true));
        account.setSmtpConfig(new MailServerConfig("smtp.account.cz", 465, true));
        return account;
    }

    private MailProviderEntity createProvider() {
        var provider = new MailProviderEntity();
        provider.setId(10L);
        provider.setName("Gmail");
        provider.setDomains(",gmail.com,googlemail.com,");
        // The provider has its own template, but the mapper must not read it —
        // it only feeds the UI label (providerName). We deliberately set
        // different hosts than on the account to prove in the assertion that
        // reads come from the entity.
        provider.setImapConfig(new MailServerConfig("imap.template.cz", 993, true));
        provider.setSmtpConfig(new MailServerConfig("smtp.template.cz", 465, true));
        return provider;
    }

    private AccountCredentialEntity createCredentials(AccountEntity account) {
        return new AccountCredentialEntity(account, AuthType.OAUTH2, "test@example.com", "encrypted-pw");
    }

    @Nested
    @DisplayName("toResponse")
    class ToResponse {

        @Test
        @DisplayName("with provider - providerName is the template label, hosts read from the account (denormalization)")
        void withProvider_readsHostFromAccount_notFromTemplate() {
            var account = createAccount();
            var provider = createProvider();
            account.setProvider(provider);
            account.setCredentials(createCredentials(account));

            AccountResponse response = mapper.toResponse(account);

            assertThat(response.id()).isEqualTo(1L);
            assertThat(response.providerId()).isEqualTo(10L);
            assertThat(response.providerName()).isEqualTo("Gmail");

            // Proof of denormalization: response reads hosts from the entity, not from the
            // template.
            assertThat(response.imapHost()).isEqualTo("imap.account.cz");
            assertThat(response.smtpHost()).isEqualTo("smtp.account.cz");
            assertThat(response.imapUseSsl()).isTrue();
            assertThat(response.smtpUseSsl()).isTrue();

            assertThat(response.username()).isEqualTo("test@example.com");
            assertThat(response.authType()).isEqualTo(AuthType.OAUTH2);
            assertThat(response.lastSyncAt()).isEqualTo(LocalDateTime.of(2025, 1, 15, 10, 30));
        }

        @Test
        @DisplayName("structured lastError is localized per request locale and propagates code/args")
        void structuredLastErrorIsLocalized() {
            var account = createAccount();
            account.setLastError("Folder INBOX synchronization failed: RuntimeException: boom");
            account.setLastErrorCode(AccountLastErrorCode.MAIL_SYNC_FOLDER_FAILED.name());
            account.setLastErrorArgs(AccountLastErrorJson
                    .write(java.util.Map.of("folder", "INBOX", "errorClass", "RuntimeException", "detail", "boom")));
            LocaleContextHolder.setLocale(Locale.ENGLISH);

            AccountResponse response = mapper.toResponse(account);

            assertThat(response.lastError()).isEqualTo("Folder INBOX synchronization failed: RuntimeException: boom");
            assertThat(response.lastErrorCode()).isEqualTo("MAIL_SYNC_FOLDER_FAILED");
            assertThat(response.lastErrorArgs()).containsEntry("folder", "INBOX").containsEntry("detail", "boom");
        }

        @Test
        @DisplayName("structured lastError supports Czech localization")
        void structuredLastErrorSupportsCzechLocale() {
            var account = createAccount();
            account.setLastError("Stored fallback");
            account.setLastErrorCode(AccountLastErrorCode.SMTP_SEND_FAILED.name());
            account.setLastErrorArgs(AccountLastErrorJson.write(java.util.Map.of("detail", "timeout")));
            LocaleContextHolder.setLocale(Locale.forLanguageTag("cs"));

            AccountResponse response = mapper.toResponse(account);

            assertThat(response.lastError()).isEqualTo("Odesílání selhalo: timeout");
            assertThat(response.lastErrorCode()).isEqualTo("SMTP_SEND_FAILED");
            assertThat(response.lastErrorArgs()).containsEntry("detail", "timeout");
        }

        @Test
        @DisplayName("oauth2Provider z entity se propisuje do response")
        void oauth2ProviderPropagatesToResponse() {
            var account = createAccount();
            account.setOauth2Provider("google");
            account.setProvider(createProvider());
            account.setCredentials(createCredentials(account));

            AccountResponse response = mapper.toResponse(account);

            assertThat(response.oauth2Provider()).isEqualTo("google");
        }

        @Test
        @DisplayName("PASSWORD account - authType is PASSWORD")
        void passwordAccount_authTypeIsPassword() {
            var account = createAccount();
            account.setProvider(createProvider());
            account.setCredentials(
                    new AccountCredentialEntity(account, AuthType.PASSWORD, "user@example.com", "encrypted-pw"));

            AccountResponse response = mapper.toResponse(account);

            assertThat(response.authType()).isEqualTo(AuthType.PASSWORD);
        }

        @Test
        @DisplayName("bez providera - providerName se lokalizuje podle request locale")
        void withoutProvider_localizesCustomProviderName() {
            var account = createAccount();
            account.setProvider(null);
            account.setImapConfig(new MailServerConfig("imap.custom.cz", 143, false));
            account.setSmtpConfig(new MailServerConfig("smtp.custom.cz", 587, false));
            account.setCredentials(createCredentials(account));
            LocaleContextHolder.setLocale(Locale.ENGLISH);

            AccountResponse response = mapper.toResponse(account);

            assertThat(response.providerId()).isNull();
            assertThat(response.providerName()).isEqualTo("Custom");
            assertThat(response.imapHost()).isEqualTo("imap.custom.cz");
            assertThat(response.imapPort()).isEqualTo(143);
            assertThat(response.imapUseSsl()).isFalse();
            assertThat(response.smtpHost()).isEqualTo("smtp.custom.cz");
            assertThat(response.smtpPort()).isEqualTo(587);
            assertThat(response.smtpUseSsl()).isFalse();
        }

        @Test
        @DisplayName("bez credentials - username je 'unknown' a authType je null")
        void withoutCredentials_showsUnknown() {
            var account = createAccount();
            account.setProvider(createProvider());
            account.setCredentials(null);

            AccountResponse response = mapper.toResponse(account);

            assertThat(response.username()).isEqualTo("unknown");
            assertThat(response.authType()).isNull();
        }
    }
}
