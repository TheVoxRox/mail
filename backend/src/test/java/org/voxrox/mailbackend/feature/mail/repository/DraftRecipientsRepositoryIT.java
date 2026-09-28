package org.voxrox.mailbackend.feature.mail.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
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
import org.voxrox.mailbackend.feature.mail.entity.DraftRecipientsEntity;
import org.voxrox.mailbackend.feature.mail.entity.MessageEntity;

/**
 * {@link DraftRecipientsRepository#deleteOrphansSavedBefore} against a real
 * SQLite database. An entry is what the send of an untouched draft saved here
 * is checked against (IMAP/SMTP audit B1-5), so the cleanup may take only the
 * entries of drafts that are gone: one whose draft still has a row stays
 * however old it is.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("it")
@Sql(statements = {"DELETE FROM draft_recipients", "DELETE FROM messages", "DELETE FROM account_credentials",
        "DELETE FROM accounts"}, executionPhase = ExecutionPhase.BEFORE_TEST_METHOD)
class DraftRecipientsRepositoryIT {

    private static final Path DB_DIR = Path
            .of("target", "test-tmp", "DraftRecipientsRepositoryIT", UUID.randomUUID().toString()).toAbsolutePath()
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
    private DraftRecipientsRepository repository;
    @Autowired
    private MessageRepository messageRepository;
    @Autowired
    private AccountRepository accountRepository;
    @Autowired
    private EntityManager em;

    @Test
    @DisplayName("Only the account's old entries whose draft has no row are dropped")
    void dropsOnlyOldOrphans() {
        AccountEntity account = newAccount("user@example.com");
        AccountEntity other = newAccount("other@example.com");
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime cutoff = now.minusDays(7);

        keep("gone", account, now.minusDays(30));
        keep("unsentforweeks", account, now.minusDays(30));
        draftRow("unsentforweeks", account);
        keep("fresh", account, now.minusHours(1));
        keep("otheraccount", other, now.minusDays(30));
        em.clear();

        int dropped = repository.deleteOrphansSavedBefore(account.getId(), cutoff);

        assertThat(dropped).isEqualTo(1);
        assertThat(repository.findAll()).extracting(DraftRecipientsEntity::getStableId)
                .containsExactlyInAnyOrder("unsentforweeks", "fresh", "otheraccount");
    }

    private void keep(String stableId, AccountEntity account, LocalDateTime savedAt) {
        repository.saveAndFlush(new DraftRecipientsEntity(stableId, account, "to@example.com", null, null, savedAt));
    }

    private void draftRow(String stableId, AccountEntity account) {
        MessageEntity m = new MessageEntity();
        m.setStableId(stableId);
        m.setAccount(account);
        m.setFolderName("Drafts");
        m.setUid(1L);
        m.setUidValidity(1L);
        m.setReceivedAt(LocalDateTime.now());
        messageRepository.saveAndFlush(m);
    }

    private AccountEntity newAccount(String email) {
        AccountEntity account = new AccountEntity();
        account.setAccountName("Acct " + email);
        account.setEmail(email);
        account.setDisplayName("User");
        account.setActive(true);
        account.setImapConfig(new MailServerConfig("imap.example.com", 993, true));
        account.setSmtpConfig(new MailServerConfig("smtp.example.com", 465, true));
        return accountRepository.saveAndFlush(account);
    }
}
