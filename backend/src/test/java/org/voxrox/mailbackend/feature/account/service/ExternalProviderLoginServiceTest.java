package org.voxrox.mailbackend.feature.account.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.voxrox.mailbackend.feature.account.entity.AccountEntity;
import org.voxrox.mailbackend.feature.account.entity.MailProviderEntity;
import org.voxrox.mailbackend.feature.account.entity.MailServerConfig;
import org.voxrox.mailbackend.feature.account.repository.AccountRepository;
import org.voxrox.mailbackend.feature.auth.dto.AuthType;
import org.voxrox.mailbackend.feature.auth.service.GoogleTokenService;
import org.voxrox.mailbackend.feature.auth.service.OAuth2TokenService;
import org.voxrox.mailbackend.feature.auth.service.OAuth2TokenServiceRegistry;
import org.voxrox.mailbackend.feature.mail.service.ImapConnectionManager;

/**
 * Unit tests for {@link ExternalProviderLoginService} — the OAuth2 provider
 * login provisioning split out of {@link AccountService}. Covers the two public
 * entry points driven by {@code OAuth2LoginService}:
 * {@code markRequiresReauthIfExists} (missing refresh token) and
 * {@code processExternalProviderLogin} (account routing by external id / e-mail
 * / new account, credential rotation, reauth clear).
 *
 * <p>
 * The after-commit runtime-auth invalidation runs inline here: no transaction
 * synchronization is active in a plain unit invocation, so
 * {@code TransactionCallbacks.runAfterCommit} falls back to its inline path.
 */
@ExtendWith(MockitoExtension.class)
class ExternalProviderLoginServiceTest {

    private static final Long ACCOUNT_ID = 1L;
    private static final String EMAIL = "test@example.com";
    private static final String ACCOUNT_NAME = "Test Account";
    private static final String DISPLAY_NAME = "Test User";

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private AccountProviderService providerService;

    @Mock
    private AccountCredentialService credentialService;

    @Mock
    private OAuth2TokenServiceRegistry oauth2TokenServiceRegistry;

    @Mock
    private OAuth2TokenService googleTokenService;

    @Mock
    private ImapConnectionManager imapConnectionManager;

    private ExternalProviderLoginService service;

    @BeforeEach
    void setUp() {
        service = new ExternalProviderLoginService(accountRepository, providerService, credentialService,
                oauth2TokenServiceRegistry, imapConnectionManager);
    }

    private AccountEntity createAccountEntity() {
        AccountEntity account = new AccountEntity();
        account.setId(ACCOUNT_ID);
        account.setAccountName(ACCOUNT_NAME);
        account.setEmail(EMAIL);
        account.setDisplayName(DISPLAY_NAME);
        account.setActive(true);
        account.setImapConfig(new MailServerConfig("imap.original.cz", 993, true));
        account.setSmtpConfig(new MailServerConfig("smtp.original.cz", 465, true));
        return account;
    }

    private MailProviderEntity createProvider() {
        MailProviderEntity provider = new MailProviderEntity();
        provider.setId(10L);
        provider.setName("Gmail");
        provider.setImapConfig(new MailServerConfig("imap.gmail.com", 993, true));
        provider.setSmtpConfig(new MailServerConfig("smtp.gmail.com", 465, true));
        return provider;
    }

    @Nested
    @DisplayName("markRequiresReauthIfExists")
    class MarkRequiresReauthIfExists {

        @Test
        void shouldSetRequiresReauthWhenAccountExistsAndNotAlreadyMarked() {
            AccountEntity account = createAccountEntity();
            account.setRequiresReauth(false);
            when(accountRepository.findByEmail(EMAIL)).thenReturn(Optional.of(account));

            service.markRequiresReauthIfExists(EMAIL);

            assertThat(account.isRequiresReauth()).isTrue();
            verify(accountRepository).save(account);
        }

        @Test
        void shouldNotUpdateWhenAlreadyMarked() {
            AccountEntity account = createAccountEntity();
            account.setRequiresReauth(true);
            when(accountRepository.findByEmail(EMAIL)).thenReturn(Optional.of(account));

            service.markRequiresReauthIfExists(EMAIL);

            verify(accountRepository, never()).save(any());
        }

        @Test
        void shouldDoNothingWhenAccountDoesNotExist() {
            when(accountRepository.findByEmail("unknown@example.com")).thenReturn(Optional.empty());

            service.markRequiresReauthIfExists("unknown@example.com");

            verify(accountRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("processExternalProviderLogin")
    class ProcessExternalProviderLogin {

        @BeforeEach
        void stubRegistryForReauthCleanup() {
            // After saveCredentials, processExternalProviderLogin calls
            // oauth2TokenServiceRegistry.resolve(...).invalidate(id) to invalidate
            // the cached access token after re-login. lenient() — tests with
            // null/blank refresh tokens or a missing provider fail earlier than
            // this point.
            org.mockito.Mockito.lenient().when(oauth2TokenServiceRegistry.resolve(GoogleTokenService.PROVIDER_NAME))
                    .thenReturn(googleTokenService);
        }

        @Test
        void shouldThrowWhenRefreshTokenIsNull() {
            assertThatThrownBy(() -> service.processExternalProviderLogin(GoogleTokenService.PROVIDER_NAME, EMAIL,
                    "Name", "ext-1", null)).isInstanceOf(IllegalStateException.class);
        }

        @Test
        void shouldThrowWhenRefreshTokenIsBlank() {
            assertThatThrownBy(() -> service.processExternalProviderLogin(GoogleTokenService.PROVIDER_NAME, EMAIL,
                    "Name", "ext-1", "  ")).isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("New account: copies server config from the template, otherwise NOT NULL constraint fails")
        void shouldCreateNewAccountWithCopiedServerConfig() {
            MailProviderEntity provider = createProvider();
            when(providerService.resolveProvider(EMAIL, null)).thenReturn(provider);
            when(accountRepository.findByOauth2ProviderAndExternalId(GoogleTokenService.PROVIDER_NAME, "ext-1"))
                    .thenReturn(Optional.empty());
            when(accountRepository.findByEmail(EMAIL)).thenReturn(Optional.empty());
            when(accountRepository.save(any(AccountEntity.class))).thenAnswer(inv -> {
                AccountEntity a = inv.getArgument(0);
                a.setId(ACCOUNT_ID);
                return a;
            });

            service.processExternalProviderLogin(GoogleTokenService.PROVIDER_NAME, EMAIL, "Test User", "ext-1",
                    "refresh-token-value");

            ArgumentCaptor<AccountEntity> captor = ArgumentCaptor.forClass(AccountEntity.class);
            verify(accountRepository, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
            AccountEntity created = captor.getAllValues().get(0);
            assertThat(created.getProvider()).isEqualTo(provider);
            // Account name is derived from the e-mail domain label, capitalised
            // (test@example.com -> "Example"), matching the wizard's client-side naming.
            assertThat(created.getAccountName()).isEqualTo("Example");
            assertThat(created.getImapConfig().getHost()).isEqualTo("imap.gmail.com");
            assertThat(created.getSmtpConfig().getHost()).isEqualTo("smtp.gmail.com");

            verify(credentialService).saveCredentials(any(AccountEntity.class), eq(EMAIL), eq("refresh-token-value"),
                    eq(AuthType.OAUTH2));
        }

        @Test
        @DisplayName("New account without a template provider == null -> IllegalStateException (invariant violation)")
        void shouldThrowWhenProviderNullAndAccountMissing() {
            when(providerService.resolveProvider(EMAIL, null)).thenReturn(null);
            when(accountRepository.findByOauth2ProviderAndExternalId(GoogleTokenService.PROVIDER_NAME, "ext-1"))
                    .thenReturn(Optional.empty());
            when(accountRepository.findByEmail(EMAIL)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.processExternalProviderLogin(GoogleTokenService.PROVIDER_NAME, EMAIL,
                    "Test", "ext-1", "tok")).isInstanceOf(IllegalStateException.class);
        }

        @Test
        void shouldReuseExistingAccountFoundByExternalId() {
            AccountEntity existing = createAccountEntity();
            existing.setExternalId("ext-1");
            MailProviderEntity provider = createProvider();

            when(providerService.resolveProvider(EMAIL, null)).thenReturn(provider);
            when(accountRepository.findByOauth2ProviderAndExternalId(GoogleTokenService.PROVIDER_NAME, "ext-1"))
                    .thenReturn(Optional.of(existing));

            service.processExternalProviderLogin(GoogleTokenService.PROVIDER_NAME, EMAIL, "New Name", "ext-1",
                    "new-token");

            verify(credentialService).saveCredentials(eq(existing), eq(EMAIL), eq("new-token"), eq(AuthType.OAUTH2));
        }

        @Test
        void shouldReuseExistingAccountFoundByEmail() {
            AccountEntity existing = createAccountEntity();
            MailProviderEntity provider = createProvider();

            when(providerService.resolveProvider(EMAIL, null)).thenReturn(provider);
            when(accountRepository.findByOauth2ProviderAndExternalId(GoogleTokenService.PROVIDER_NAME, "ext-new"))
                    .thenReturn(Optional.empty());
            when(accountRepository.findByEmail(EMAIL)).thenReturn(Optional.of(existing));

            service.processExternalProviderLogin(GoogleTokenService.PROVIDER_NAME, EMAIL, "User Name", "ext-new",
                    "token");

            verify(credentialService).saveCredentials(eq(existing), eq(EMAIL), eq("token"), eq(AuthType.OAUTH2));
        }

        @Test
        void shouldClearRequiresReauthOnReLogin() {
            AccountEntity existing = createAccountEntity();
            existing.setRequiresReauth(true);
            // Pre-set provider/externalId/displayName so syncExternalAccountMetadata
            // does not mark anything — save then runs only once for the reauth clear.
            existing.setOauth2Provider(GoogleTokenService.PROVIDER_NAME);
            existing.setExternalId("ext-1");
            existing.setDisplayName(DISPLAY_NAME);
            MailProviderEntity provider = createProvider();

            when(providerService.resolveProvider(EMAIL, null)).thenReturn(provider);
            when(accountRepository.findByOauth2ProviderAndExternalId(GoogleTokenService.PROVIDER_NAME, "ext-1"))
                    .thenReturn(Optional.of(existing));

            service.processExternalProviderLogin(GoogleTokenService.PROVIDER_NAME, EMAIL, DISPLAY_NAME, "ext-1",
                    "new-refresh");

            assertThat(existing.isRequiresReauth()).isFalse();
            verify(accountRepository).save(existing);
        }

        @Test
        void shouldNotSaveExtraWhenRequiresReauthAlreadyFalse() {
            AccountEntity existing = createAccountEntity();
            existing.setRequiresReauth(false);
            existing.setOauth2Provider(GoogleTokenService.PROVIDER_NAME);
            existing.setExternalId("ext-1");
            existing.setDisplayName(DISPLAY_NAME);
            MailProviderEntity provider = createProvider();

            when(providerService.resolveProvider(EMAIL, null)).thenReturn(provider);
            when(accountRepository.findByOauth2ProviderAndExternalId(GoogleTokenService.PROVIDER_NAME, "ext-1"))
                    .thenReturn(Optional.of(existing));

            service.processExternalProviderLogin(GoogleTokenService.PROVIDER_NAME, EMAIL, DISPLAY_NAME, "ext-1",
                    "token");

            verify(accountRepository, never()).save(any());
        }

        @Test
        void shouldSyncDisplayNameWhenChanged() {
            AccountEntity existing = createAccountEntity();
            existing.setExternalId("ext-1");
            existing.setDisplayName("Old Name");
            MailProviderEntity provider = createProvider();

            when(providerService.resolveProvider(EMAIL, null)).thenReturn(provider);
            when(accountRepository.findByOauth2ProviderAndExternalId(GoogleTokenService.PROVIDER_NAME, "ext-1"))
                    .thenReturn(Optional.of(existing));

            service.processExternalProviderLogin(GoogleTokenService.PROVIDER_NAME, EMAIL, "New Name", "ext-1", "token");

            assertThat(existing.getDisplayName()).isEqualTo("New Name");
            verify(accountRepository).save(existing);
        }
    }
}
