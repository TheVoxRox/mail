package org.voxrox.mailbackend.feature.mail.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
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
import org.voxrox.mailbackend.feature.account.entity.AccountEntity;
import org.voxrox.mailbackend.feature.account.entity.MailServerConfig;
import org.voxrox.mailbackend.feature.account.repository.AccountRepository;
import org.voxrox.mailbackend.feature.mail.dto.FolderRole;
import org.voxrox.mailbackend.feature.mail.entity.FolderSyncStateEntity;

/**
 * {@link FolderSyncStateRepository#updateRole} against a real SQLite database:
 * it corrects the role and leaves every other column as the database has it. A
 * folder cycle can advance {@code last_known_uid} between the moment a caller
 * loaded the row and the moment it corrects the role, through a targeted UPDATE
 * that does not bump {@code @Version}; saving the loaded entity would write the
 * old value back with nothing to stop it, which is why the role has its own
 * UPDATE.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("it")
@Sql(statements = {"DELETE FROM folder_sync_state", "DELETE FROM account_credentials",
        "DELETE FROM accounts"}, executionPhase = ExecutionPhase.BEFORE_TEST_METHOD)
class FolderSyncStateRepositoryIT {

    private static final Path DB_DIR = Path
            .of("target", "test-tmp", "FolderSyncStateRepositoryIT", UUID.randomUUID().toString()).toAbsolutePath()
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
    private FolderSyncStateRepository repository;
    @Autowired
    private AccountRepository accountRepository;
    @Autowired
    private EntityManager em;

    @Test
    @DisplayName("updateRole corrects the role and keeps a last_known_uid advanced after the row was loaded")
    void updateRoleTouchesTheRoleOnly() {
        AccountEntity account = new AccountEntity();
        account.setAccountName("Test Account");
        account.setEmail("user@example.com");
        account.setDisplayName("User");
        account.setActive(true);
        account.setImapConfig(new MailServerConfig("imap.example.com", 993, true));
        account.setSmtpConfig(new MailServerConfig("smtp.example.com", 465, true));
        account = accountRepository.saveAndFlush(account);
        FolderSyncStateEntity robinson = repository
                .saveAndFlush(new FolderSyncStateEntity(account, "Robinson", FolderRole.TRASH));
        Long id = robinson.getId();

        // A folder cycle advances the UID after this caller loaded the row.
        repository.updateLastKnownUid(id, 42L);

        repository.updateRole(id, FolderRole.USER);
        em.clear();

        FolderSyncStateEntity reloaded = repository.findById(id).orElseThrow();
        assertThat(reloaded.getRole()).isEqualTo(FolderRole.USER);
        assertThat(reloaded.getLastKnownUid()).isEqualTo(42L);
        assertThat(repository.findFolderNamesByRole(account.getId(), FolderRole.TRASH)).isEmpty();
    }
}
