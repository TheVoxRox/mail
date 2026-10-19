package org.voxrox.mailbackend.feature.account.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import org.springframework.dao.DataAccessException;
import org.springframework.mock.env.MockEnvironment;
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
import org.voxrox.mailbackend.feature.mail.service.SyncCrashGuard;

/**
 * Regression IT for server-config denormalization onto accounts.
 * <p>
 * Goal: verify that the FK {@code accounts.provider_id -> mail_providers(id)
 * ON DELETE SET NULL} works at the database level. Deleting a row from
 * {@code mail_providers} may only affect the label (UI label) — the embedded
 * server config (host/port/SSL) on the account stays valid and the NOT NULL
 * constraint must not fail.
 * <p>
 * Acceptance criterion for the custom-provider feature: removing a custom mail
 * provider must never invalidate an account already bound to it — the label
 * link degrades to {@code NULL} while the account's embedded server config
 * keeps it fully functional.
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
    @DisplayName("An address is found and kept unique whatever its case, and stored as typed")
    void addressLookupAndUniquenessIgnoreCase() {
        Long typed = accountRepository.saveAndFlush(account("Jan.Novak@Example.com")).getId();
        em.clear();

        // What the password form and the OAuth fallback ask before writing.
        assertThat(accountRepository.existsByEmail("jan.novak@example.com")).isTrue();
        assertThat(accountRepository.findByEmail("JAN.NOVAK@EXAMPLE.COM")).map(AccountEntity::getId).contains(typed);
        assertThat(accountRepository.existsByEmailAndIdNot("jan.novak@example.com", typed + 1)).isTrue();
        assertThat(accountRepository.findById(typed)).map(AccountEntity::getEmail).contains("Jan.Novak@Example.com");

        // And the index, for a writer that did not ask. Hibernate wraps the SQLite
        // unique violation as JpaSystemException, hence the common base and the
        // constraint pinned by name (as in ContactRepositoryIT).
        assertThatThrownBy(() -> accountRepository.saveAndFlush(account("jan.novak@example.com")))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("accounts.email");
    }

    private static AccountEntity account(String email) {
        AccountEntity account = new AccountEntity();
        account.setAccountName("Test Account");
        account.setEmail(email);
        account.setActive(true);
        account.setImapConfig(new MailServerConfig("imap.example.com", 993, true));
        account.setSmtpConfig(new MailServerConfig("smtp.example.com", 465, true));
        return account;
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
                        java.util.Map.of("folder", "INBOX", "detail", "boom"),
                        "Folder sync INBOX failed: RuntimeException: boom"),
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

    @Test
    @DisplayName("The crash guard counts three restarts out of a sync on the real schema and pauses the account")
    void crashGuardPausesAfterThreeRestartsOnTheRealSchema() {
        /*
         * Runs the guard against the schema's columns and the repository's JPQL rather
         * than mocks: each round is a process that starts with the shell's restart
         * variable set, begins a sync and dies in it.
         */
        Long accountId = accountRepository.saveAndFlush(account("crashing@example.com")).getId();
        for (int round = 1; round <= 3; round++) {
            SyncCrashGuard process = restartedProcess();
            process.afterSingletonsInstantiated();
            em.clear();
            /*
             * The restarted app opens a folder first, and that folder's sync ends inside
             * the process: the message that ends it is in another folder, which only the
             * scheduled pass reaches. A sync that ended used to start the count over here.
             */
            AccountEntity openingAFolder = accountRepository.findById(accountId).orElseThrow();
            assertThat(process.enter(openingAFolder)).isTrue();
            process.exit(accountId);
            em.clear();
            assertThat(process.enter(accountRepository.findById(accountId).orElseThrow())).isTrue();
            em.clear();
            assertThat(accountRepository.findById(accountId).orElseThrow().getSyncStartedAt()).isNotNull();
        }

        // The process that starts after the third crash.
        restartedProcess().afterSingletonsInstantiated();
        em.clear();

        AccountEntity paused = accountRepository.findById(accountId).orElseThrow();
        assertThat(paused.isSyncPaused()).isTrue();
        assertThat(paused.getSyncCrashes()).isEqualTo(3);
        assertThat(paused.getSyncStartedAt()).isNull();
        assertThat(paused.getLastErrorCode()).isEqualTo(AccountLastErrorCode.MAIL_SYNC_PAUSED_AFTER_CRASHES.name());
        assertThat(accountRepository.findBySyncStartedAtIsNotNull()).isEmpty();
    }

    @Test
    @DisplayName("A sync that ends clears the marker, and only a whole pass clears the count")
    void syncThatEndsClearsTheMarkerAndAWholePassTheCount() {
        Long accountId = accountRepository.saveAndFlush(account("ending@example.com")).getId();
        accountRepository.recordSyncCrash(accountId, 2, false);
        em.clear();
        SyncCrashGuard guard = new SyncCrashGuard(accountRepository, new MockEnvironment());
        AccountEntity loaded = accountRepository.findById(accountId).orElseThrow();

        assertThat(guard.enter(loaded)).isTrue();
        guard.exit(accountId);
        em.clear();

        AccountEntity ended = accountRepository.findById(accountId).orElseThrow();
        assertThat(ended.getSyncStartedAt()).isNull();
        assertThat(ended.getSyncCrashes()).isEqualTo(2);

        guard.passCompleted(ended);
        em.clear();

        AccountEntity passed = accountRepository.findById(accountId).orElseThrow();
        assertThat(passed.getSyncCrashes()).isZero();
        assertThat(passed.isSyncPaused()).isFalse();
    }

    @Test
    @DisplayName("Saving an account loaded before the crash guard wrote does not put the old values back")
    void savingAStaleAccountKeepsTheGuardColumns() {
        /*
         * The columns are mapped read-only: a full-row UPDATE from an entity loaded
         * before the guard's write would otherwise rewrite the marker, the count and
         * the pause with what that entity held.
         */
        Long accountId = accountRepository.saveAndFlush(account("stale@example.com")).getId();
        em.clear();
        AccountEntity stale = accountRepository.findById(accountId).orElseThrow();
        em.detach(stale);

        accountRepository.markSyncStarted(accountId, java.time.LocalDateTime.of(2026, 10, 19, 9, 0));
        accountRepository.recordSyncCrash(accountId, 3, true);
        accountRepository.markSyncStarted(accountId, java.time.LocalDateTime.of(2026, 10, 19, 9, 5));
        stale.setDisplayName("Renamed");
        accountRepository.saveAndFlush(stale);
        em.clear();

        AccountEntity saved = accountRepository.findById(accountId).orElseThrow();
        assertThat(saved.getDisplayName()).isEqualTo("Renamed");
        assertThat(saved.getSyncStartedAt()).isEqualTo(java.time.LocalDateTime.of(2026, 10, 19, 9, 5));
        assertThat(saved.getSyncCrashes()).isEqualTo(3);
        assertThat(saved.isSyncPaused()).isTrue();
        assertThat(accountRepository.isSyncPaused(accountId)).contains(true);
    }

    /**
     * A guard as the shell's restart after an out-of-memory exit would start it.
     */
    private SyncCrashGuard restartedProcess() {
        return new SyncCrashGuard(accountRepository,
                new MockEnvironment().withProperty("MAIL_SIDECAR_PREVIOUS_EXIT", "3"));
    }
}
