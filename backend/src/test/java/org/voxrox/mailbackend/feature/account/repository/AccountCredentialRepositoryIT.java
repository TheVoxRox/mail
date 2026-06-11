package org.voxrox.mailbackend.feature.account.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.Sql.ExecutionPhase;
import org.voxrox.mailbackend.feature.account.entity.AccountCredentialEntity;
import org.voxrox.mailbackend.feature.account.entity.AccountEntity;
import org.voxrox.mailbackend.feature.account.entity.MailProviderEntity;
import org.voxrox.mailbackend.feature.account.entity.MailServerConfig;
import org.voxrox.mailbackend.feature.auth.dto.AuthType;

/**
 * Regression IT for AccountCredentialEntity with @MapsId.
 *
 * Background: an older version of the entity set {@code id} manually in
 * {@code setAccount()} and in the constructor. Spring Data {@code save()} then
 * saw a non-null id and called {@code merge()} instead of {@code persist()},
 * which for a transient entity with {@code @MapsId} ended with
 * {@code AssertionFailure: null identifier} (during merge Hibernate cannot
 * derive the id from the association under relaxed OneToOne checks).
 *
 * AccountCredentialService unit tests mock the repository and do not exercise
 * this branch — hence the IT.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("it")
@Sql(statements = {"DELETE FROM account_credentials", "DELETE FROM accounts",
        "DELETE FROM mail_providers"}, executionPhase = ExecutionPhase.BEFORE_TEST_METHOD)
class AccountCredentialRepositoryIT {

    private static final Path DB_DIR = Path
            .of("target", "test-tmp", "AccountCredentialRepositoryIT", UUID.randomUUID().toString()).toAbsolutePath()
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
    private AccountCredentialRepository credentialRepository;
    @Autowired
    private AccountRepository accountRepository;
    @Autowired
    private MailProviderRepository providerRepository;
    @Autowired
    private EntityManager em;

    private AccountEntity account;

    @BeforeEach
    void setUp() {
        MailProviderEntity provider = new MailProviderEntity();
        provider.setName("TestProvider");
        provider.setDomains(",example.com,");
        provider.setImapConfig(new MailServerConfig("imap.example.com", 993, true));
        provider.setSmtpConfig(new MailServerConfig("smtp.example.com", 465, true));
        provider = providerRepository.saveAndFlush(provider);

        AccountEntity a = new AccountEntity();
        a.setAccountName("Test Account");
        a.setEmail("test@example.com");
        a.setDisplayName("Test");
        a.setProvider(provider);
        a.setActive(true);
        // Server config is NOT NULL after denormalization — a copy of the template.
        a.setImapConfig(new MailServerConfig(provider.getImapConfig().getHost(), provider.getImapConfig().getPort(),
                provider.getImapConfig().isUseSsl()));
        a.setSmtpConfig(new MailServerConfig(provider.getSmtpConfig().getHost(), provider.getSmtpConfig().getPort(),
                provider.getSmtpConfig().isUseSsl()));
        account = accountRepository.saveAndFlush(a);
    }

    @Test
    @DisplayName("save(new credential) — @MapsId derives id from the association, no AssertionFailure")
    void persistNewCredentialDerivesIdFromAccount() {
        AccountCredentialEntity creds = new AccountCredentialEntity(account, AuthType.OAUTH2, "test@example.com",
                "encrypted-token");

        AccountCredentialEntity saved = credentialRepository.saveAndFlush(creds);
        em.clear();

        assertThat(saved.getId()).isEqualTo(account.getId());

        Optional<AccountCredentialEntity> reloaded = credentialRepository.findById(account.getId());
        assertThat(reloaded).isPresent();
        assertThat(reloaded.get().getAuthType()).isEqualTo(AuthType.OAUTH2);
        assertThat(reloaded.get().getEncryptedPassword()).isEqualTo("encrypted-token");
    }

    @Test
    @DisplayName("save(new credential) via setter — persist works without manual setId()")
    void persistViaSetterWorks() {
        AccountCredentialEntity creds = new AccountCredentialEntity();
        creds.setAccount(account);
        creds.setAuthType(AuthType.PASSWORD);
        creds.setUsername("test@example.com");
        creds.setEncryptedPassword("encrypted-pwd");

        credentialRepository.saveAndFlush(creds);
        em.clear();

        assertThat(credentialRepository.findById(account.getId())).isPresent();
    }

    @Test
    @DisplayName("save(existing credential) — update keeps the id from @MapsId")
    void updateExistingCredentialKeepsMappedId() {
        AccountCredentialEntity initial = new AccountCredentialEntity(account, AuthType.PASSWORD, "test@example.com",
                "old-pwd");
        credentialRepository.saveAndFlush(initial);
        em.clear();

        AccountCredentialEntity loaded = credentialRepository.findById(account.getId()).orElseThrow();
        loaded.setAuthType(AuthType.OAUTH2);
        loaded.setEncryptedPassword("new-token");
        credentialRepository.saveAndFlush(loaded);
        em.clear();

        AccountCredentialEntity reloaded = credentialRepository.findById(account.getId()).orElseThrow();
        assertThat(reloaded.getAuthType()).isEqualTo(AuthType.OAUTH2);
        assertThat(reloaded.getEncryptedPassword()).isEqualTo("new-token");
        assertThat(reloaded.getId()).isEqualTo(account.getId());
    }
}
