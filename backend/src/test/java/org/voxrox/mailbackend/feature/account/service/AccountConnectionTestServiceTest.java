package org.voxrox.mailbackend.feature.account.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Locale;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.support.StaticMessageSource;
import org.voxrox.mailbackend.exception.MailConnectionException;
import org.voxrox.mailbackend.exception.ValidationException;
import org.voxrox.mailbackend.feature.account.dto.AccountConnectionDetails;
import org.voxrox.mailbackend.feature.account.dto.AccountConnectionTestRequest;
import org.voxrox.mailbackend.feature.account.dto.MailServerSettings;
import org.voxrox.mailbackend.feature.account.entity.AccountCredentialEntity;
import org.voxrox.mailbackend.feature.account.entity.AccountEntity;
import org.voxrox.mailbackend.feature.account.entity.MailProviderEntity;
import org.voxrox.mailbackend.feature.account.entity.MailServerConfig;
import org.voxrox.mailbackend.feature.account.repository.AccountRepository;
import org.voxrox.mailbackend.feature.auth.dto.AuthType;
import org.voxrox.mailbackend.feature.mail.service.MailConnectionProbe;

@ExtendWith(MockitoExtension.class)
class AccountConnectionTestServiceTest {

    private static final Long ACCOUNT_ID = 7L;

    @Mock
    private AccountRepository accountRepository;
    @Mock
    private AccountProviderService providerService;
    @Mock
    private AccountCredentialService credentialService;
    @Mock
    private MailConnectionProbe mailConnectionProbe;

    private AccountConnectionTestService service;

    @BeforeEach
    void setUp() {
        StaticMessageSource messageSource = new StaticMessageSource();
        messageSource.addMessage("account.connectionTest.success", Locale.ENGLISH,
                "IMAP and SMTP connection were verified successfully.");
        service = new AccountConnectionTestService(accountRepository, providerService, credentialService,
                mailConnectionProbe, messageSource);
    }

    @Test
    @DisplayName("provider request builds IMAP/SMTP details and uses the submitted password without DB credentials")
    void providerRequestUsesSubmittedPassword() {
        MailProviderEntity provider = provider();
        when(providerService.loadProviderById(10L)).thenReturn(provider);
        AccountConnectionTestRequest request = new AccountConnectionTestRequest(null, "user@example.com", 10L, null,
                null, "user@example.com", "secret");

        var response = service.testConnection(request);

        assertThat(response.imapOk()).isTrue();
        assertThat(response.smtpOk()).isTrue();
        assertThat(response.message()).isEqualTo("IMAP and SMTP connection were verified successfully.");
        ArgumentCaptor<AccountConnectionDetails> details = ArgumentCaptor.forClass(AccountConnectionDetails.class);
        verify(mailConnectionProbe).testImap(org.mockito.Mockito.isNull(), details.capture());
        assertThat(details.getValue().host()).isEqualTo("imap.example.com");
        assertThat(details.getValue().passwordOrSecret()).isEqualTo("secret");
        assertThat(details.getValue().authType()).isEqualTo(AuthType.PASSWORD);
        verifyNoInteractions(accountRepository, credentialService);
    }

    @Test
    @DisplayName("custom request sends custom host/port for both protocols")
    void customRequestUsesCustomServers() {
        AccountConnectionTestRequest request = new AccountConnectionTestRequest(null, "user@example.com", null,
                new MailServerSettings("imap.custom.cz", 143, false),
                new MailServerSettings("smtp.custom.cz", 587, false), "user", "secret");

        service.testConnection(request);

        ArgumentCaptor<AccountConnectionDetails> imap = ArgumentCaptor.forClass(AccountConnectionDetails.class);
        ArgumentCaptor<AccountConnectionDetails> smtp = ArgumentCaptor.forClass(AccountConnectionDetails.class);
        verify(mailConnectionProbe).testImap(org.mockito.Mockito.isNull(), imap.capture());
        verify(mailConnectionProbe).testSmtp(org.mockito.Mockito.isNull(), smtp.capture());
        assertThat(imap.getValue().host()).isEqualTo("imap.custom.cz");
        assertThat(imap.getValue().useSsl()).isFalse();
        assertThat(smtp.getValue().host()).isEqualTo("smtp.custom.cz");
        assertThat(smtp.getValue().port()).isEqualTo(587);
    }

    @Test
    @DisplayName("edit without a new password uses the stored secret and auth type")
    void editWithoutPasswordUsesStoredSecret() {
        AccountEntity account = new AccountEntity();
        account.setOauth2Provider("google");
        AccountCredentialEntity credentials = new AccountCredentialEntity();
        credentials.setAuthType(AuthType.OAUTH2);
        when(accountRepository.findById(ACCOUNT_ID)).thenReturn(Optional.of(account));
        when(credentialService.getCredentials(ACCOUNT_ID)).thenReturn(credentials);
        when(credentialService.getDecryptedSecret(ACCOUNT_ID)).thenReturn("refresh-token");
        AccountConnectionTestRequest request = new AccountConnectionTestRequest(ACCOUNT_ID, "user@gmail.com", null,
                new MailServerSettings("imap.gmail.com", 993, true),
                new MailServerSettings("smtp.gmail.com", 465, true), "user@gmail.com", null);

        service.testConnection(request);

        ArgumentCaptor<AccountConnectionDetails> details = ArgumentCaptor.forClass(AccountConnectionDetails.class);
        verify(mailConnectionProbe).testImap(org.mockito.Mockito.eq(ACCOUNT_ID), details.capture());
        assertThat(details.getValue().authType()).isEqualTo(AuthType.OAUTH2);
        assertThat(details.getValue().passwordOrSecret()).isEqualTo("refresh-token");
        assertThat(details.getValue().oauth2Provider()).isEqualTo("google");
    }

    @Test
    @DisplayName("new account without password fails service-layer validation")
    void newAccountWithoutPasswordThrows() {
        AccountConnectionTestRequest request = new AccountConnectionTestRequest(null, "user@example.com", null,
                new MailServerSettings("imap.custom.cz", 143, false),
                new MailServerSettings("smtp.custom.cz", 587, false), "user", null);

        assertThatThrownBy(() -> service.testConnection(request)).isInstanceOf(ValidationException.class)
                .hasMessageContaining("password");
    }

    @Test
    @DisplayName("IMAP probe failure propagates and SMTP is not attempted")
    void imapFailureStopsBeforeSmtp() {
        MailProviderEntity provider = provider();
        when(providerService.loadProviderById(10L)).thenReturn(provider);
        AccountConnectionTestRequest request = new AccountConnectionTestRequest(null, "user@example.com", 10L, null,
                null, "user@example.com", "secret");
        org.mockito.Mockito.doThrow(new MailConnectionException("IMAP fail")).when(mailConnectionProbe)
                .testImap(org.mockito.Mockito.isNull(), org.mockito.Mockito.any());

        assertThatThrownBy(() -> service.testConnection(request)).isInstanceOf(MailConnectionException.class)
                .hasMessageContaining("IMAP fail");

        org.mockito.Mockito.verify(mailConnectionProbe, org.mockito.Mockito.never()).testSmtp(org.mockito.Mockito.any(),
                org.mockito.Mockito.any());
    }

    private MailProviderEntity provider() {
        MailProviderEntity provider = new MailProviderEntity();
        provider.setId(10L);
        provider.setName("Example");
        provider.setImapConfig(new MailServerConfig("imap.example.com", 993, true));
        provider.setSmtpConfig(new MailServerConfig("smtp.example.com", 465, true));
        return provider;
    }
}
