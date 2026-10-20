package org.voxrox.mailbackend.feature.account.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.voxrox.mailbackend.core.init.StartupTimingService;
import org.voxrox.mailbackend.feature.account.entity.AccountEntity;
import org.voxrox.mailbackend.feature.account.repository.AccountRepository;
import org.voxrox.mailbackend.feature.account.repository.MailProviderRepository;
import org.voxrox.mailbackend.feature.auth.service.MicrosoftTokenService;
import org.voxrox.mailbackend.feature.auth.service.OAuth2TokenServiceRegistry;
import org.voxrox.mailbackend.feature.mail.service.ImapConnectionManager;

/**
 * An OAuth sign-in finds its provider template through the real catalog and a
 * real SQLite, which is where the domain lookup it replaced went wrong: a work
 * or school account signs in under its organization's own domain, which no
 * template lists, and the sign-in was refused after the provider had already
 * accepted it.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("it")
class ExternalProviderLoginServiceIT {

    private static final Path DB_DIR = Path
            .of("target", "test-tmp", "ExternalProviderLoginServiceIT", UUID.randomUUID().toString()).toAbsolutePath()
            .normalize();

    @DynamicPropertySource
    static void configureSqliteDatasource(DynamicPropertyRegistry registry) {
        try {
            Files.createDirectories(DB_DIR);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot create directory for SQLite test DB: " + DB_DIR, e);
        }
        Path dbFile = DB_DIR.resolve("test.db");
        registry.add("spring.datasource.url",
                () -> "jdbc:sqlite:" + dbFile.toAbsolutePath() + "?foreign_keys=ON&busy_timeout=5000");
    }

    @Autowired
    private MailProviderRepository providerRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private ExternalProviderLoginService service;

    @BeforeEach
    void setUp() {
        new MailProviderCatalogReconciler(providerRepository, transactionTemplate, new StartupTimingService())
                .reconcile();
        service = new ExternalProviderLoginService(accountRepository, new AccountProviderService(providerRepository),
                mock(AccountCredentialService.class), mock(OAuth2TokenServiceRegistry.class),
                mock(ImapConnectionManager.class), mock(ApplicationEventPublisher.class));
    }

    @Test
    @DisplayName("A Microsoft 365 work account at its organization's own domain gets the Microsoft servers")
    void workAccountAtOwnDomainGetsMicrosoftServers() {
        service.processExternalProviderLogin(MicrosoftTokenService.PROVIDER_NAME, "jan.novak@firma.example",
                "Jan Novak", "entra-oid-1", "refresh-token");

        AccountEntity account = accountRepository.findByEmail("jan.novak@firma.example").orElseThrow();
        assertThat(account.getProvider()).isNotNull();
        assertThat(account.getProvider().getOauth2RegistrationId()).isEqualTo(MicrosoftTokenService.PROVIDER_NAME);
        assertThat(account.getImapConfig().getHost()).isEqualTo("outlook.office365.com");
        assertThat(account.getSmtpConfig().getHost()).isEqualTo("smtp.office365.com");
        assertThat(account.getOauth2Provider()).isEqualTo(MicrosoftTokenService.PROVIDER_NAME);
    }

    @Test
    @DisplayName("A Microsoft account named by a Gmail address still gets the Microsoft servers")
    void microsoftAccountNamedByGmailAddressGetsMicrosoftServers() {
        service.processExternalProviderLogin(MicrosoftTokenService.PROVIDER_NAME, "someone@gmail.com", "Someone",
                "msa-oid-1", "refresh-token");

        AccountEntity account = accountRepository.findByEmail("someone@gmail.com").orElseThrow();
        assertThat(account.getImapConfig().getHost()).isEqualTo("outlook.office365.com");
    }
}
