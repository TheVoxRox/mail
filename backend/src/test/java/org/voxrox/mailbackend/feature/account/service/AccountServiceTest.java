package org.voxrox.mailbackend.feature.account.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.voxrox.mailbackend.exception.AccountAlreadyExistsException;
import org.voxrox.mailbackend.exception.AccountNotFoundException;
import org.voxrox.mailbackend.exception.ProviderNotFoundException;
import org.voxrox.mailbackend.exception.ValidationException;
import org.voxrox.mailbackend.feature.account.dto.*;
import org.voxrox.mailbackend.feature.account.entity.*;
import org.voxrox.mailbackend.feature.account.mapper.AccountMapper;
import org.voxrox.mailbackend.feature.account.repository.AccountRepository;
import org.voxrox.mailbackend.feature.auth.dto.AuthType;
import org.voxrox.mailbackend.feature.auth.service.GoogleTokenService;
import org.voxrox.mailbackend.feature.auth.service.OAuth2TokenService;
import org.voxrox.mailbackend.feature.auth.service.OAuth2TokenServiceRegistry;
import org.voxrox.mailbackend.feature.mail.service.ImapConnectionManager;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * Unit tests for {@link AccountService}.
 *
 * With server-config denormalization onto the account we test two paths:
 * template (provider != null) — applyServerConfig copies host/port/SSL from
 * {@code mail_providers} into the account's embedded columns; custom (provider
 * == null) — embedded columns are populated directly from the request.
 *
 * Coverage: - listAllAccounts: empty list, multiple accounts - getAccountById:
 * found, missing - createAccount: provider, custom, non-existing providerId,
 * duplicate email - updateAccount: PUT from provider -> custom and back,
 * ProviderNotFound, missing account, duplicate email, password=null/blank guard
 * - deleteAccount, markRequiresReauthIfExists, processExternalProviderLogin
 */
@ExtendWith(MockitoExtension.class)
class AccountServiceTest {

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
    private ImapConnectionManager imapConnectionManager;

    @Mock
    private AccountMapper accountMapper;

    @Mock
    private OAuth2TokenServiceRegistry oauth2TokenServiceRegistry;

    @Mock
    private OAuth2TokenService googleTokenService;

    /*
     * Real TransactionTemplate over a mocked manager: the callback runs inline and
     * no transaction synchronization is active, so the after-commit helpers fall
     * back to their inline path — the same execution shape the plain-invocation
     * tests had before deleteAccount switched to TransactionTemplate.
     */
    @Mock
    private PlatformTransactionManager transactionManager;

    private AccountService service;

    private ListAppender<ILoggingEvent> auditAppender;
    private Logger auditLogger;

    @BeforeEach
    void setUp() {
        service = new AccountService(accountRepository, providerService, credentialService, imapConnectionManager,
                accountMapper, oauth2TokenServiceRegistry, new TransactionTemplate(transactionManager));

        auditLogger = (Logger) LoggerFactory.getLogger("AUDIT");
        auditAppender = new ListAppender<>();
        auditAppender.start();
        auditLogger.addAppender(auditAppender);
    }

    @AfterEach
    void tearDown() {
        auditLogger.detachAppender(auditAppender);
    }

    // ---- helpers ----

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

    private MailServerSettings imapCustom() {
        return new MailServerSettings("imap.custom.cz", 143, false);
    }

    private MailServerSettings smtpCustom() {
        return new MailServerSettings("smtp.custom.cz", 587, false);
    }

    private void stubExistingCredentials(AuthType authType) {
        AccountCredentialEntity creds = new AccountCredentialEntity();
        creds.setAuthType(authType);
        when(credentialService.getCredentials(ACCOUNT_ID)).thenReturn(creds);
    }

    private void assertAuditFailure(String action, String detailFragment) {
        var matches = auditAppender.list.stream().filter(e -> e.getLevel() == Level.WARN)
                .filter(e -> e.getFormattedMessage().contains("action=" + action))
                .filter(e -> e.getFormattedMessage().contains(detailFragment)).toList();
        assertThat(matches)
                .as("Expected exactly one audit FAILURE record for action=%s with '%s'", action, detailFragment)
                .hasSize(1);
    }

    private AccountResponse dummyResponse() {
        return new AccountResponse(ACCOUNT_ID, ACCOUNT_NAME, EMAIL, DISPLAY_NAME, 10L, "Gmail", "imap.gmail.com", 993,
                true, "smtp.gmail.com", 465, true, EMAIL, AuthType.PASSWORD, null, true, false, null, null);
    }

    @Nested
    @DisplayName("listAllAccounts")
    class ListAllAccounts {

        @Test
        void shouldReturnEmptyListWhenNoAccountsExist() {
            when(accountRepository.findAllWithDetails()).thenReturn(List.of());

            List<AccountResponse> result = service.listAllAccounts();

            assertThat(result).isEmpty();
        }

        @Test
        void shouldMapAllAccountsToResponse() {
            AccountEntity acc1 = createAccountEntity();
            AccountEntity acc2 = createAccountEntity();
            acc2.setId(2L);
            acc2.setEmail("other@example.com");

            AccountResponse resp1 = dummyResponse();
            AccountResponse resp2 = new AccountResponse(2L, ACCOUNT_NAME, "other@example.com", DISPLAY_NAME, 10L,
                    "Gmail", "imap.gmail.com", 993, true, "smtp.gmail.com", 465, true, "other@example.com",
                    AuthType.PASSWORD, null, true, false, null, null);

            when(accountRepository.findAllWithDetails()).thenReturn(List.of(acc1, acc2));
            when(accountMapper.toResponse(acc1)).thenReturn(resp1);
            when(accountMapper.toResponse(acc2)).thenReturn(resp2);

            List<AccountResponse> result = service.listAllAccounts();

            assertThat(result).containsExactly(resp1, resp2);
        }
    }

    @Nested
    @DisplayName("getAccountById")
    class GetAccountById {

        @Test
        void shouldReturnMappedResponseForExistingAccount() {
            AccountEntity account = createAccountEntity();
            AccountResponse expected = dummyResponse();

            when(accountRepository.findById(ACCOUNT_ID)).thenReturn(Optional.of(account));
            when(accountMapper.toResponse(account)).thenReturn(expected);

            AccountResponse result = service.getAccountById(ACCOUNT_ID);

            assertThat(result).isEqualTo(expected);
        }

        @Test
        void shouldThrowAccountNotFoundExceptionForMissingId() {
            when(accountRepository.findById(999L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getAccountById(999L)).isInstanceOf(AccountNotFoundException.class);
        }
    }

    @Nested
    @DisplayName("createAccount")
    class CreateAccount {

        @Test
        @DisplayName("with providerId — copies server config from the template into the account")
        void shouldCopyServerConfigFromProviderTemplate() {
            AccountCreateRequest request = new AccountCreateRequest(ACCOUNT_NAME, DISPLAY_NAME, EMAIL, 10L, null, null,
                    EMAIL, "secret123");

            MailProviderEntity provider = createProvider();

            when(providerService.loadProviderById(10L)).thenReturn(provider);
            when(accountRepository.save(any(AccountEntity.class))).thenAnswer(inv -> {
                AccountEntity saved = inv.getArgument(0);
                saved.setId(ACCOUNT_ID);
                return saved;
            });
            when(accountMapper.toResponse(any(AccountEntity.class))).thenReturn(dummyResponse());

            service.createAccount(request);

            ArgumentCaptor<AccountEntity> captor = ArgumentCaptor.forClass(AccountEntity.class);
            verify(accountRepository).save(captor.capture());
            AccountEntity saved = captor.getValue();
            assertThat(saved.getProvider()).isEqualTo(provider);
            // Denormalization: embedded config is a copy, not the same identity.
            assertThat(saved.getImapConfig().getHost()).isEqualTo("imap.gmail.com");
            assertThat(saved.getImapConfig().getPort()).isEqualTo(993);
            assertThat(saved.getSmtpConfig().getHost()).isEqualTo("smtp.gmail.com");
            assertThat(saved.getSmtpConfig().getPort()).isEqualTo(465);
            // It is really a copy and not a shared reference: a change on the
            // account must not affect the template.
            saved.getImapConfig().setHost("changed");
            assertThat(provider.getImapConfig().getHost()).isEqualTo("imap.gmail.com");

            verify(credentialService).saveCredentials(any(AccountEntity.class), eq(EMAIL), eq("secret123"),
                    eq(AuthType.PASSWORD));
        }

        @Test
        @DisplayName("with custom imap+smtp (no providerId) — provider stays null, config from the request")
        void shouldUseCustomConfigWhenNoProviderId() {
            AccountCreateRequest request = new AccountCreateRequest(ACCOUNT_NAME, DISPLAY_NAME, EMAIL, null,
                    imapCustom(), smtpCustom(), EMAIL, "secret123");

            when(accountRepository.save(any(AccountEntity.class))).thenAnswer(inv -> {
                AccountEntity saved = inv.getArgument(0);
                saved.setId(ACCOUNT_ID);
                return saved;
            });
            when(accountMapper.toResponse(any(AccountEntity.class))).thenReturn(dummyResponse());

            service.createAccount(request);

            ArgumentCaptor<AccountEntity> captor = ArgumentCaptor.forClass(AccountEntity.class);
            verify(accountRepository).save(captor.capture());
            AccountEntity saved = captor.getValue();
            assertThat(saved.getProvider()).isNull();
            assertThat(saved.getImapConfig().getHost()).isEqualTo("imap.custom.cz");
            assertThat(saved.getImapConfig().getPort()).isEqualTo(143);
            assertThat(saved.getImapConfig().isUseSsl()).isFalse();
            assertThat(saved.getSmtpConfig().getHost()).isEqualTo("smtp.custom.cz");
            assertThat(saved.getSmtpConfig().getPort()).isEqualTo(587);

            verify(providerService, never()).loadProviderById(anyLong());
        }

        @Test
        @DisplayName("non-existing providerId -> ProviderNotFoundException, no save")
        void shouldThrowWhenProviderIdMissing() {
            AccountCreateRequest request = new AccountCreateRequest(ACCOUNT_NAME, DISPLAY_NAME, EMAIL, 999L, null, null,
                    EMAIL, "secret");

            when(providerService.loadProviderById(999L)).thenThrow(ProviderNotFoundException.byId(999L));

            assertThatThrownBy(() -> service.createAccount(request)).isInstanceOf(ProviderNotFoundException.class);

            verify(accountRepository, never()).save(any());
            verify(credentialService, never()).saveCredentials(any(), any(), any(), any());
        }

        @Test
        void shouldThrowAccountAlreadyExistsWhenEmailDuplicate() {
            AccountCreateRequest request = new AccountCreateRequest(ACCOUNT_NAME, DISPLAY_NAME, EMAIL, 10L, null, null,
                    EMAIL, "secret");

            when(accountRepository.existsByEmail(EMAIL)).thenReturn(true);

            assertThatThrownBy(() -> service.createAccount(request)).isInstanceOf(AccountAlreadyExistsException.class);

            verify(accountRepository, never()).save(any(AccountEntity.class));
            verify(credentialService, never()).saveCredentials(any(), any(), any(), any());
        }

        @Test
        void shouldSetActiveToTrueByDefault() {
            AccountCreateRequest request = new AccountCreateRequest(ACCOUNT_NAME, null, EMAIL, 10L, null, null, EMAIL,
                    "pass");

            when(providerService.loadProviderById(10L)).thenReturn(createProvider());
            when(accountRepository.save(any(AccountEntity.class))).thenAnswer(inv -> {
                AccountEntity a = inv.getArgument(0);
                a.setId(ACCOUNT_ID);
                return a;
            });
            when(accountMapper.toResponse(any(AccountEntity.class))).thenReturn(dummyResponse());

            service.createAccount(request);

            ArgumentCaptor<AccountEntity> captor = ArgumentCaptor.forClass(AccountEntity.class);
            verify(accountRepository).save(captor.capture());
            assertThat(captor.getValue().isActive()).isTrue();
        }
    }

    @Nested
    @DisplayName("updateAccount")
    class UpdateAccount {

        @Test
        @DisplayName("PUT custom -> providerId: provider is set, config overwritten by a template copy")
        void putSwitchesFromCustomToProvider() {
            AccountEntity existing = createAccountEntity();
            existing.setProvider(null);
            existing.setImapConfig(new MailServerConfig("imap.old.cz", 143, false));
            existing.setSmtpConfig(new MailServerConfig("smtp.old.cz", 587, false));

            MailProviderEntity gmail = createProvider();

            AccountUpdateRequest request = new AccountUpdateRequest("Updated Name", "new@example.com", "New Display",
                    10L, null, null, "newuser", "newpass", false);

            when(accountRepository.findById(ACCOUNT_ID)).thenReturn(Optional.of(existing));
            stubExistingCredentials(AuthType.PASSWORD);
            when(providerService.loadProviderById(10L)).thenReturn(gmail);
            when(accountRepository.save(existing)).thenReturn(existing);
            when(accountMapper.toResponse(existing)).thenReturn(dummyResponse());

            service.updateAccount(ACCOUNT_ID, request);

            assertThat(existing.getAccountName()).isEqualTo("Updated Name");
            assertThat(existing.getEmail()).isEqualTo("new@example.com");
            assertThat(existing.isActive()).isFalse();
            assertThat(existing.getProvider()).isEqualTo(gmail);
            assertThat(existing.getImapConfig().getHost()).isEqualTo("imap.gmail.com");
            assertThat(existing.getSmtpConfig().getHost()).isEqualTo("smtp.gmail.com");

            verify(credentialService).saveCredentials(existing, "newuser", "newpass", AuthType.PASSWORD);
        }

        @Test
        @DisplayName("PUT providerId -> custom: provider is cleared, config overwritten from the request")
        void putSwitchesFromProviderToCustom() {
            AccountEntity existing = createAccountEntity();
            existing.setProvider(createProvider());

            AccountUpdateRequest request = new AccountUpdateRequest(ACCOUNT_NAME, EMAIL, DISPLAY_NAME, null,
                    imapCustom(), smtpCustom(), "user", "pass", true);

            when(accountRepository.findById(ACCOUNT_ID)).thenReturn(Optional.of(existing));
            stubExistingCredentials(AuthType.PASSWORD);
            when(accountRepository.save(existing)).thenReturn(existing);
            when(accountMapper.toResponse(existing)).thenReturn(dummyResponse());

            service.updateAccount(ACCOUNT_ID, request);

            assertThat(existing.getProvider()).isNull();
            assertThat(existing.getImapConfig().getHost()).isEqualTo("imap.custom.cz");
            assertThat(existing.getSmtpConfig().getHost()).isEqualTo("smtp.custom.cz");

            verify(providerService, never()).loadProviderById(anyLong());
        }

        @Test
        void shouldThrowProviderNotFoundExceptionForInvalidProviderId() {
            AccountEntity existing = createAccountEntity();

            AccountUpdateRequest request = new AccountUpdateRequest(ACCOUNT_NAME, EMAIL, DISPLAY_NAME, 999L, null, null,
                    "user", "pass", true);

            when(accountRepository.findById(ACCOUNT_ID)).thenReturn(Optional.of(existing));
            stubExistingCredentials(AuthType.PASSWORD);
            when(providerService.loadProviderById(999L)).thenThrow(ProviderNotFoundException.byId(999L));

            assertThatThrownBy(() -> service.updateAccount(ACCOUNT_ID, request))
                    .isInstanceOf(ProviderNotFoundException.class);
        }

        @Test
        void shouldThrowAccountNotFoundExceptionForMissingAccount() {
            AccountUpdateRequest request = new AccountUpdateRequest(ACCOUNT_NAME, EMAIL, DISPLAY_NAME, 10L, null, null,
                    "user", "pass", true);

            when(accountRepository.findById(999L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.updateAccount(999L, request)).isInstanceOf(AccountNotFoundException.class);
        }

        @Test
        void shouldThrowAccountAlreadyExistsWhenEmailChangedToDuplicate() {
            AccountEntity existing = createAccountEntity();

            AccountUpdateRequest request = new AccountUpdateRequest(ACCOUNT_NAME, "other@example.com", DISPLAY_NAME,
                    10L, null, null, "user", "pass", true);

            when(accountRepository.findById(ACCOUNT_ID)).thenReturn(Optional.of(existing));
            when(accountRepository.existsByEmailAndIdNot("other@example.com", ACCOUNT_ID)).thenReturn(true);

            assertThatThrownBy(() -> service.updateAccount(ACCOUNT_ID, request))
                    .isInstanceOf(AccountAlreadyExistsException.class);

            verify(accountRepository, never()).save(any(AccountEntity.class));
        }

        @Test
        void shouldNotCheckDuplicateWhenEmailUnchanged() {
            AccountEntity existing = createAccountEntity();

            AccountUpdateRequest request = new AccountUpdateRequest(ACCOUNT_NAME,
                    EMAIL.toUpperCase(java.util.Locale.ROOT), DISPLAY_NAME, 10L, null, null, "user", "pass", true);

            when(accountRepository.findById(ACCOUNT_ID)).thenReturn(Optional.of(existing));
            stubExistingCredentials(AuthType.PASSWORD);
            when(providerService.loadProviderById(10L)).thenReturn(createProvider());
            when(accountRepository.save(existing)).thenReturn(existing);
            when(accountMapper.toResponse(existing)).thenReturn(dummyResponse());

            service.updateAccount(ACCOUNT_ID, request);

            verify(accountRepository, never()).existsByEmailAndIdNot(any(), anyLong());
        }

        @Test
        @DisplayName("PUT with null password -> metadata updated, credentials untouched")
        void shouldNotSaveCredentialsWhenPasswordIsNull() {
            AccountEntity existing = createAccountEntity();

            AccountUpdateRequest request = new AccountUpdateRequest("Updated Name", "new@example.com", "New Display",
                    10L, null, null, "newuser", null, false);

            when(accountRepository.findById(ACCOUNT_ID)).thenReturn(Optional.of(existing));
            when(providerService.loadProviderById(10L)).thenReturn(createProvider());
            when(accountRepository.save(existing)).thenReturn(existing);
            when(accountMapper.toResponse(existing)).thenReturn(dummyResponse());

            service.updateAccount(ACCOUNT_ID, request);

            assertThat(existing.getAccountName()).isEqualTo("Updated Name");
            assertThat(existing.getEmail()).isEqualTo("new@example.com");
            verify(credentialService, never()).saveCredentials(any(), any(), any(), any());
        }

        @Test
        void shouldNotSaveCredentialsWhenPasswordIsBlank() {
            AccountEntity existing = createAccountEntity();

            AccountUpdateRequest request = new AccountUpdateRequest(ACCOUNT_NAME, EMAIL, DISPLAY_NAME, 10L, null, null,
                    "user", "   ", true);

            when(accountRepository.findById(ACCOUNT_ID)).thenReturn(Optional.of(existing));
            when(providerService.loadProviderById(10L)).thenReturn(createProvider());
            when(accountRepository.save(existing)).thenReturn(existing);
            when(accountMapper.toResponse(existing)).thenReturn(dummyResponse());

            service.updateAccount(ACCOUNT_ID, request);

            verify(credentialService, never()).saveCredentials(any(), any(), any(), any());
        }
    }

    @Nested
    @DisplayName("OAuth2CredentialsGuard")
    class OAuth2CredentialsGuard {

        @Test
        @DisplayName("PUT on an OAuth2 account with password -> ValidationException, no mutation")
        void putWithPasswordOnOAuth2RejectedWithValidationException() {
            AccountEntity existing = createAccountEntity();
            AccountUpdateRequest request = new AccountUpdateRequest(ACCOUNT_NAME, EMAIL, DISPLAY_NAME, 10L, null, null,
                    EMAIL, "stolenpassword", true);

            when(accountRepository.findById(ACCOUNT_ID)).thenReturn(Optional.of(existing));
            stubExistingCredentials(AuthType.OAUTH2);

            assertThatThrownBy(() -> service.updateAccount(ACCOUNT_ID, request)).isInstanceOf(ValidationException.class)
                    .hasMessageContaining("OAuth2");

            verify(accountRepository, never()).save(any(AccountEntity.class));
            verify(credentialService, never()).saveCredentials(any(), any(), any(), any());

            assertAuditFailure("oauth2_credentials_mutation_blocked", "via=PUT");
        }

        @Test
        @DisplayName("PUT on an OAuth2 account without password -> passes (metadata only)")
        void putWithoutPasswordOnOAuth2Allowed() {
            AccountEntity existing = createAccountEntity();
            existing.setProvider(createProvider());
            AccountUpdateRequest request = new AccountUpdateRequest("Renamed", EMAIL, "New Display", 10L, null, null,
                    EMAIL, null, true);

            when(accountRepository.findById(ACCOUNT_ID)).thenReturn(Optional.of(existing));
            when(providerService.loadProviderById(10L)).thenReturn(createProvider());
            when(accountRepository.save(existing)).thenReturn(existing);
            when(accountMapper.toResponse(existing)).thenReturn(dummyResponse());

            service.updateAccount(ACCOUNT_ID, request);

            assertThat(existing.getAccountName()).isEqualTo("Renamed");
            assertThat(existing.getDisplayName()).isEqualTo("New Display");
            verify(credentialService, never()).getCredentials(any());
            verify(credentialService, never()).saveCredentials(any(), any(), any(), any());
        }

    }

    @Nested
    @DisplayName("deleteAccount")
    class DeleteAccount {

        @Test
        @DisplayName("Deletes credentials and the account, then purges the connection (post-commit cleanup)")
        void shouldDeleteCredentialsAndAccountThenPurgeConnection() {
            AccountEntity account = createAccountEntity();
            when(accountRepository.findById(ACCOUNT_ID)).thenReturn(Optional.of(account));
            AccountCredentialEntity creds = new AccountCredentialEntity();
            creds.setAuthType(AuthType.PASSWORD);
            when(credentialService.getCredentials(ACCOUNT_ID)).thenReturn(creds);

            service.deleteAccount(ACCOUNT_ID);

            // The connection purge runs AFTER the DB delete (after the commit in
            // production) — purgeAccount can wait on the per-account IMAP lock
            // for a long time and must not hold the SQLite write transaction.
            var order = org.mockito.Mockito.inOrder(credentialService, accountRepository, imapConnectionManager);
            order.verify(credentialService).deleteCredentials(ACCOUNT_ID);
            order.verify(accountRepository).deleteById(ACCOUNT_ID);
            order.verify(imapConnectionManager).purgeAccount(ACCOUNT_ID);
            verify(oauth2TokenServiceRegistry, never()).resolve(org.mockito.ArgumentMatchers.anyString());
            verify(googleTokenService, never()).revokeToken(any());
        }

        @Test
        @DisplayName("OAuth2 account: revokeToken is resolved via the registry and called BEFORE deleteCredentials")
        void shouldRevokeOauthTokenBeforeDeletingCredentialsForOAuth2() {
            AccountEntity account = createAccountEntity();
            account.setOauth2Provider(GoogleTokenService.PROVIDER_NAME);
            when(accountRepository.findById(ACCOUNT_ID)).thenReturn(Optional.of(account));
            AccountCredentialEntity creds = new AccountCredentialEntity();
            creds.setAuthType(AuthType.OAUTH2);
            when(credentialService.getCredentials(ACCOUNT_ID)).thenReturn(creds);
            when(oauth2TokenServiceRegistry.resolve(GoogleTokenService.PROVIDER_NAME)).thenReturn(googleTokenService);

            service.deleteAccount(ACCOUNT_ID);

            var order = org.mockito.Mockito.inOrder(googleTokenService, credentialService, accountRepository,
                    imapConnectionManager);
            order.verify(googleTokenService).revokeToken(account);
            order.verify(credentialService).deleteCredentials(ACCOUNT_ID);
            order.verify(accountRepository).deleteById(ACCOUNT_ID);
            order.verify(imapConnectionManager).purgeAccount(ACCOUNT_ID);
        }

        @Test
        @DisplayName("Purge failure after the delete is swallowed — the account is already gone")
        void shouldSwallowPurgeFailureAfterDelete() {
            AccountEntity account = createAccountEntity();
            when(accountRepository.findById(ACCOUNT_ID)).thenReturn(Optional.of(account));
            AccountCredentialEntity creds = new AccountCredentialEntity();
            creds.setAuthType(AuthType.PASSWORD);
            when(credentialService.getCredentials(ACCOUNT_ID)).thenReturn(creds);
            doThrow(new RuntimeException("connection timeout")).when(imapConnectionManager).purgeAccount(ACCOUNT_ID);

            service.deleteAccount(ACCOUNT_ID);

            verify(credentialService).deleteCredentials(ACCOUNT_ID);
            verify(accountRepository).deleteById(ACCOUNT_ID);
        }

        @Test
        void shouldRethrowRuntimeExceptionAfterAuditFailure() {
            AccountEntity account = createAccountEntity();
            when(accountRepository.findById(ACCOUNT_ID)).thenReturn(Optional.of(account));
            AccountCredentialEntity creds = new AccountCredentialEntity();
            creds.setAuthType(AuthType.PASSWORD);
            when(credentialService.getCredentials(ACCOUNT_ID)).thenReturn(creds);
            doThrow(new RuntimeException("db locked")).when(credentialService).deleteCredentials(ACCOUNT_ID);

            assertThatThrownBy(() -> service.deleteAccount(ACCOUNT_ID)).isInstanceOf(RuntimeException.class)
                    .hasMessage("db locked");

            verify(accountRepository, never()).deleteById(anyLong());
            verify(imapConnectionManager, never()).purgeAccount(anyLong());
            verify(googleTokenService, never()).revokeToken(any());
        }

        @Test
        void shouldThrowAccountNotFoundExceptionForMissingAccount() {
            when(accountRepository.findById(999L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.deleteAccount(999L)).isInstanceOf(AccountNotFoundException.class);
        }
    }

    @Nested
    @DisplayName("getAccountOrThrow")
    class GetAccountOrThrow {

        @Test
        void shouldReturnEntityWhenFound() {
            AccountEntity account = createAccountEntity();
            when(accountRepository.findById(ACCOUNT_ID)).thenReturn(Optional.of(account));

            AccountEntity result = service.getAccountOrThrow(ACCOUNT_ID);

            assertThat(result).isSameAs(account);
        }

        @Test
        void shouldThrowAccountNotFoundExceptionWhenMissing() {
            when(accountRepository.findById(42L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getAccountOrThrow(42L)).isInstanceOf(AccountNotFoundException.class);
        }
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
