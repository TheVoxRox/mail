package org.voxrox.mailbackend.feature.account.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.EntityManager;

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
import org.voxrox.mailbackend.feature.account.AccountLastError;
import org.voxrox.mailbackend.feature.account.AccountLastErrorCode;
import org.voxrox.mailbackend.feature.account.entity.AccountEntity;
import org.voxrox.mailbackend.feature.account.entity.MailProviderEntity;
import org.voxrox.mailbackend.feature.account.entity.MailServerConfig;

/**
 * Regression IT for server-config denormalization onto accounts.
 * <p>
 * Goal: verify that the FK {@code accounts.provider_id -> mail_providers(id)
 * ON DELETE SET NULL} works at the database level. Deleting a row from
 * {@code mail_providers} may only affect the label (UI label) — the embedded
 * server config (host/port/SSL) on the account stays valid and the NOT NULL
 * constraint must not fail.
 * <p>
 * Acceptance criterion from the "Custom provider" plan (section "Implementation
 * plan: Custom provider" in `todo.md`).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("it")
@Sql(statements = {"DELETE FROM account_credentials", "DELETE FROM accounts",
        "DELETE FROM mail_providers"}, executionPhase = ExecutionPhase.BEFORE_TEST_METHOD)
class AccountRepositoryIT {

    private static final Path DB_DIR = Path
            .of("target", "test-tmp", "AccountRepositoryIT", UUID.randomUUID().toString()).toAbsolutePath().normalize();

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
    private AccountRepository accountRepository;
    @Autowired
    private MailProviderRepository providerRepository;
    @Autowired
    private EntityManager em;

    @Test
    @DisplayName("Deleting a row from mail_providers nulls accounts.provider_id; runtime config stays valid")
    void deleteProviderSetsAccountProviderIdToNullAndKeepsServerConfig() {
        // Arrange — provider template + account with a denormalized copy of its config.
        MailProviderEntity provider = new MailProviderEntity();
        provider.setName("TestProvider");
        provider.setDomains(",example.com,");
        provider.setImapConfig(new MailServerConfig("imap.example.com", 993, true));
        provider.setSmtpConfig(new MailServerConfig("smtp.example.com", 465, true));
        provider = providerRepository.saveAndFlush(provider);
        Long providerId = provider.getId();

        AccountEntity account = new AccountEntity();
        account.setAccountName("Test Account");
        account.setEmail("user@example.com");
        account.setDisplayName("User");
        account.setProvider(provider);
        account.setActive(true);
        account.setImapConfig(new MailServerConfig("imap.example.com", 993, true));
        account.setSmtpConfig(new MailServerConfig("smtp.example.com", 465, true));
        AccountEntity savedAccount = accountRepository.saveAndFlush(account);
        Long accountId = savedAccount.getId();

        em.clear();

        // Act — delete the provider via native SQL so the DB-level FK trigger is
        // verified (JPA cascade would mask actual behavior). Hibernate would
        // otherwise run its own orphan-handling before delete; we hit the SQL
        // rule directly.
        em.createNativeQuery("DELETE FROM mail_providers WHERE id = :id").setParameter("id", providerId)
                .executeUpdate();
        em.clear();

        // Assert — the account still exists, only the provider reference is
        // null. Server config (NOT NULL embedded columns) remains untouched.
        Optional<AccountEntity> reloaded = accountRepository.findById(accountId);
        assertThat(reloaded).isPresent();
        AccountEntity a = reloaded.get();
        assertThat(a.getProvider()).isNull();
        assertThat(a.getImapConfig()).isNotNull();
        assertThat(a.getImapConfig().getHost()).isEqualTo("imap.example.com");
        assertThat(a.getImapConfig().getPort()).isEqualTo(993);
        assertThat(a.getImapConfig().isUseSsl()).isTrue();
        assertThat(a.getSmtpConfig()).isNotNull();
        assertThat(a.getSmtpConfig().getHost()).isEqualTo("smtp.example.com");
        assertThat(a.getSmtpConfig().getPort()).isEqualTo(465);

        // Template is gone.
        assertThat(providerRepository.findById(providerId)).isEmpty();
    }

    @Test
    @DisplayName("updateLastError persists fallback, code and JSON args; clear removes them")
    void updateLastErrorPersistsStructuredFieldsAndClearRemovesThem() {
        AccountEntity account = new AccountEntity();
        account.setAccountName("Test Account");
        account.setEmail("structured@example.com");
        account.setDisplayName("User");
        account.setActive(true);
        account.setImapConfig(new MailServerConfig("imap.example.com", 993, true));
        account.setSmtpConfig(new MailServerConfig("smtp.example.com", 465, true));
        Long accountId = accountRepository.saveAndFlush(account).getId();

        accountRepository.updateLastError(accountId,
                AccountLastError.of(AccountLastErrorCode.MAIL_SYNC_FOLDER_FAILED,
                        java.util.Map.of("folder", "INBOX", "errorClass", "RuntimeException", "detail", "boom"),
                        "Folder INBOX synchronization failed: RuntimeException: boom"),
                java.time.LocalDateTime.of(2026, 5, 12, 10, 0));
        em.clear();

        AccountEntity withError = accountRepository.findById(accountId).orElseThrow();
        assertThat(withError.getLastError()).contains("INBOX");
        assertThat(withError.getLastErrorCode()).isEqualTo("MAIL_SYNC_FOLDER_FAILED");
        assertThat(withError.getLastErrorArgs()).contains("\"folder\":\"INBOX\"");

        accountRepository.clearLastError(accountId, java.time.LocalDateTime.of(2026, 5, 12, 10, 1));
        em.clear();

        AccountEntity cleared = accountRepository.findById(accountId).orElseThrow();
        assertThat(cleared.getLastError()).isNull();
        assertThat(cleared.getLastErrorCode()).isNull();
        assertThat(cleared.getLastErrorArgs()).isNull();
    }
}
