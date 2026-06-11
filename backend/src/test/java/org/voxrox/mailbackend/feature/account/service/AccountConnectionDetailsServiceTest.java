package org.voxrox.mailbackend.feature.account.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.voxrox.mailbackend.exception.AccountNotFoundException;
import org.voxrox.mailbackend.feature.account.dto.AccountConnectionDetails;
import org.voxrox.mailbackend.feature.account.entity.AccountCredentialEntity;
import org.voxrox.mailbackend.feature.account.entity.AccountEntity;
import org.voxrox.mailbackend.feature.account.entity.MailServerConfig;
import org.voxrox.mailbackend.feature.account.repository.AccountRepository;
import org.voxrox.mailbackend.feature.auth.dto.AuthType;

/**
 * Unit tests for {@link AccountConnectionDetailsService}. After server-config
 * denormalization onto the account, the service does not read through the
 * mapper — it takes imap config directly from the embedded columns on the
 * entity.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AccountConnectionDetailsService")
class AccountConnectionDetailsServiceTest {

    private static final Long ACCOUNT_ID = 1L;
    private static final String EMAIL = "test@example.com";

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private AccountCredentialService credentialService;

    private AccountConnectionDetailsService service;

    @BeforeEach
    void setUp() {
        service = new AccountConnectionDetailsService(accountRepository, credentialService);
    }

    @Test
    @DisplayName("IMAP: returns the full bundle from account, credentials, and embedded imap config")
    void shouldReturnImapConnectionDetailsWithAllFields() {
        AccountEntity account = new AccountEntity();
        account.setId(ACCOUNT_ID);
        account.setEmail(EMAIL);
        account.setImapConfig(new MailServerConfig("imap.example.com", 993, true));
        account.setSmtpConfig(new MailServerConfig("smtp.example.com", 465, true));

        AccountCredentialEntity credentials = new AccountCredentialEntity();
        credentials.setUsername(EMAIL);
        credentials.setAuthType(AuthType.PASSWORD);

        when(accountRepository.findById(ACCOUNT_ID)).thenReturn(Optional.of(account));
        when(credentialService.getCredentials(ACCOUNT_ID)).thenReturn(credentials);
        when(credentialService.getDecryptedSecret(ACCOUNT_ID)).thenReturn("decrypted-secret");

        AccountConnectionDetails result = service.getImapConnectionDetails(ACCOUNT_ID);

        assertThat(result.email()).isEqualTo(EMAIL);
        assertThat(result.host()).isEqualTo("imap.example.com");
        assertThat(result.port()).isEqualTo(993);
        assertThat(result.useSsl()).isTrue();
        assertThat(result.username()).isEqualTo(EMAIL);
        assertThat(result.passwordOrSecret()).isEqualTo("decrypted-secret");
        assertThat(result.authType()).isEqualTo(AuthType.PASSWORD);
    }

    @Test
    @DisplayName("SMTP: takes host/port/SSL from the embedded smtp config, not imap")
    void shouldReturnSmtpConnectionDetailsFromSmtpConfig() {
        AccountEntity account = new AccountEntity();
        account.setId(ACCOUNT_ID);
        account.setEmail(EMAIL);
        account.setImapConfig(new MailServerConfig("imap.example.com", 993, true));
        account.setSmtpConfig(new MailServerConfig("smtp.example.com", 465, true));

        AccountCredentialEntity credentials = new AccountCredentialEntity();
        credentials.setUsername(EMAIL);
        credentials.setAuthType(AuthType.PASSWORD);

        when(accountRepository.findById(ACCOUNT_ID)).thenReturn(Optional.of(account));
        when(credentialService.getCredentials(ACCOUNT_ID)).thenReturn(credentials);
        when(credentialService.getDecryptedSecret(ACCOUNT_ID)).thenReturn("decrypted-secret");

        AccountConnectionDetails result = service.getSmtpConnectionDetails(ACCOUNT_ID);

        assertThat(result.host()).isEqualTo("smtp.example.com");
        assertThat(result.port()).isEqualTo(465);
        assertThat(result.useSsl()).isTrue();
    }

    @Test
    @DisplayName("Unknown ID -> AccountNotFoundException (no further calls)")
    void shouldThrowAccountNotFoundExceptionForMissingAccount() {
        when(accountRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getImapConnectionDetails(999L)).isInstanceOf(AccountNotFoundException.class);
    }
}
