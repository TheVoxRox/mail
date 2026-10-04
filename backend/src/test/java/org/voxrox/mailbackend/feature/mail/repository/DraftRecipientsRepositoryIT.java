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

/**
 * {@link DraftRecipientsRepository} against a real SQLite database. An entry is
 * what the send of an untouched draft saved here is checked against (IMAP/SMTP
 * audit B1-5), so it is found by what a server cannot change about the draft —
 * the account and the Message-ID the save minted — and dropped only by the
 * user's own further saves, never by its age.
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
    private AccountRepository accountRepository;
    @Autowired
    private EntityManager em;

    @Test
    @DisplayName("An entry is found by its account and Message-ID, and by no other account's")
    void findsTheEntryByAccountAndMessageId() {
        AccountEntity account = newAccount("user@example.com");
        AccountEntity other = newAccount("other@example.com");
        keep("<draft@voxrox.org>", account, LocalDateTime.now());
        em.clear();

        assertThat(repository.findById(new DraftRecipientsEntity.Key(account.getId(), "<draft@voxrox.org>")))
                .map(DraftRecipientsEntity::getRecipientsTo).contains("to@example.com");
        assertThat(repository.findById(new DraftRecipientsEntity.Key(other.getId(), "<draft@voxrox.org>"))).isEmpty();
    }

    @Test
    @DisplayName("Only the account's entries older than its newest few are dropped, however recent they are")
    void keepsTheAccountsNewestEntries() {
        AccountEntity account = newAccount("user@example.com");
        AccountEntity other = newAccount("other@example.com");
        LocalDateTime now = LocalDateTime.now();
        keep("<a@voxrox.org>", account, now.minusMinutes(4));
        keep("<b@voxrox.org>", account, now.minusMinutes(3));
        keep("<c@voxrox.org>", account, now.minusMinutes(2));
        keep("<d@voxrox.org>", account, now.minusMinutes(1));
        keep("<other@voxrox.org>", other, now.minusDays(30));
        em.clear();

        int dropped = repository.deleteAllButNewest(account.getId(), 2);

        assertThat(dropped).isEqualTo(2);
        assertThat(repository.findAll()).extracting(DraftRecipientsEntity::getMessageId)
                .containsExactlyInAnyOrder("<c@voxrox.org>", "<d@voxrox.org>", "<other@voxrox.org>");
    }

    @Test
    @DisplayName("An account with no more entries than it keeps loses none, however old they are")
    void dropsNothingWithinTheBound() {
        AccountEntity account = newAccount("user@example.com");
        keep("<old@voxrox.org>", account, LocalDateTime.now().minusYears(1));
        keep("<new@voxrox.org>", account, LocalDateTime.now());
        em.clear();

        assertThat(repository.deleteAllButNewest(account.getId(), 2)).isZero();
        assertThat(repository.findAll()).hasSize(2);
    }

    private void keep(String messageId, AccountEntity account, LocalDateTime savedAt) {
        repository.saveAndFlush(
                new DraftRecipientsEntity(account.getId(), messageId, "to@example.com", null, null, savedAt));
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
