package org.voxrox.mailbackend.feature.mail.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
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

        int dropped = repository.deleteCurrentButNewest(account.getId(), 2);

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

        assertThat(repository.deleteCurrentButNewest(account.getId(), 2)).isZero();
        assertThat(repository.findAll()).hasSize(2);
    }

    /**
     * B1-5, reopened at 1.31: the entries used to share one count, and a save
     * retired the revision it replaced only when the server let it, so a server
     * without APPENDUID had a hidden draft's entry pushed out by the user's
     * autosaves. Each save now marks the revision it replaces superseded, and the
     * two kinds are bounded apart.
     * <p>
     * The bound here is three, as the service's is a thousand: a draft being saved
     * holds two current entries for the moment of a save, since the new one is kept
     * before the append and the old one marked after it, so the hidden draft needs
     * the third place.
     */
    @Test
    @DisplayName("Autosaves of one draft never push out another draft's entry (B1-5)")
    void autosavesDoNotPushOutAnotherDraft() {
        AccountEntity account = newAccount("user@example.com");

        autosave(account, 10, true);

        assertThat(repository.findAll()).filteredOn(entry -> entry.getSupersededAt() == null)
                .extracting(DraftRecipientsEntity::getMessageId)
                .containsExactlyInAnyOrder("<hidden@voxrox.org>", "<rev10@voxrox.org>");
        // Pruned to the bound before each save, then one more marked after it.
        assertThat(repository.findAll()).filteredOn(entry -> entry.getSupersededAt() != null)
                .hasSizeLessThanOrEqualTo(BOUND + 1);
    }

    /**
     * The same autosaves without the mark — what a save did until 1.36 when the
     * server gave it no row to delete — push the hidden draft's entry out.
     */
    @Test
    @DisplayName("Without the mark, the same autosaves push the hidden draft's entry out")
    void withoutTheMarkAutosavesPushItOut() {
        AccountEntity account = newAccount("user@example.com");

        autosave(account, 10, false);

        assertThat(repository.findAll()).extracting(DraftRecipientsEntity::getMessageId)
                .doesNotContain("<hidden@voxrox.org>");
    }

    /**
     * The same push-out by a server rejecting every APPEND, found by the
     * verification pass over 1.37: each save keeps its entry before the append, the
     * previous revision stays current, and only setting the rejected revision's
     * entry aside keeps the current ones from growing.
     */
    @Test
    @DisplayName("Rejected saves never push out another draft's entry either (B1-5)")
    void rejectedSavesDoNotPushOutAnotherDraft() {
        AccountEntity account = newAccount("user@example.com");
        LocalDateTime start = LocalDateTime.now().minusHours(1);
        keep("<hidden@voxrox.org>", "stable-hidden", account, start);
        keep("<stored@voxrox.org>", "stable-stored", account, start.plusSeconds(1));
        for (int save = 1; save <= 10; save++) {
            repository.deleteCurrentButNewest(account.getId(), BOUND - 1);
            repository.deleteSupersededButNewest(account.getId(), BOUND);
            keep("<rejected" + save + "@voxrox.org>", "stable-rejected" + save, account, start.plusMinutes(save));
            repository.markSuperseded(account.getId(), "stable-rejected" + save, start.plusMinutes(save));
        }
        em.clear();

        assertThat(repository.findAll()).filteredOn(entry -> entry.getSupersededAt() == null)
                .extracting(DraftRecipientsEntity::getMessageId)
                .containsExactlyInAnyOrder("<hidden@voxrox.org>", "<stored@voxrox.org>");
    }

    private static final int BOUND = 3;

    /**
     * A hidden draft's entry, then {@code saves} saves of another draft in the
     * service's order: prune both kinds, keep the new revision's entry, and — when
     * {@code mark} — mark the revision it replaced superseded.
     */
    private void autosave(AccountEntity account, int saves, boolean mark) {
        LocalDateTime start = LocalDateTime.now().minusHours(1);
        keep("<hidden@voxrox.org>", "stable-hidden", account, start);
        String previous = null;
        for (int save = 1; save <= saves; save++) {
            repository.deleteCurrentButNewest(account.getId(), BOUND - 1);
            repository.deleteSupersededButNewest(account.getId(), BOUND);
            keep("<rev" + save + "@voxrox.org>", "stable-rev" + save, account, start.plusMinutes(save));
            if (mark && previous != null) {
                repository.markSuperseded(account.getId(), previous, start.plusMinutes(save));
            }
            previous = "stable-rev" + save;
        }
        em.clear();
    }

    /**
     * B1-5, found by the verification pass over 1.39: a stored revision sets aside
     * the earlier revisions of its draft by their chain, whichever of them its save
     * named, and leaves the rest alone — itself, a newer revision of the chain that
     * a slow save stored late must not set aside, another draft's chain, and
     * another account's entries.
     */
    @Test
    @DisplayName("A stored revision sets aside only the account's earlier current entries of its chain")
    void setsAsideOnlyTheEarlierEntriesOfTheChain() {
        AccountEntity account = newAccount("user@example.com");
        AccountEntity other = newAccount("other@example.com");
        // SQLite keeps milliseconds, so the time read back is compared at that
        // precision.
        LocalDateTime start = LocalDateTime.now().minusHours(1).truncatedTo(ChronoUnit.MILLIS);
        keep("<stored-before@voxrox.org>", "stable-a", "chain", account, start);
        keep("<rejected@voxrox.org>", "stable-b", "chain", account, start.plusMinutes(1));
        repository.markSuperseded(account.getId(), "stable-b", start.plusMinutes(1));
        keep("<stored@voxrox.org>", "stable-c", "chain", account, start.plusMinutes(2));
        keep("<newer@voxrox.org>", "stable-d", "chain", account, start.plusMinutes(3));
        keep("<another-draft@voxrox.org>", "stable-e", "another-chain", account, start);
        keep("<other-account@voxrox.org>", "stable-a", "chain", other, start);
        em.clear();

        assertThat(repository.supersedeEarlierInChain(account.getId(), "chain", "<stored@voxrox.org>",
                start.plusMinutes(2), start.plusMinutes(4))).isEqualTo(1);
        em.clear();

        assertThat(repository.findAll()).filteredOn(entry -> entry.getSupersededAt() == null)
                .extracting(DraftRecipientsEntity::getMessageId).containsExactlyInAnyOrder("<stored@voxrox.org>",
                        "<newer@voxrox.org>", "<another-draft@voxrox.org>", "<other-account@voxrox.org>");
        assertThat(repository.findAll()).filteredOn(entry -> "<rejected@voxrox.org>".equals(entry.getMessageId()))
                .extracting(DraftRecipientsEntity::getSupersededAt).as("an entry already set aside keeps its time")
                .containsExactly(start.plusMinutes(1));
    }

    @Test
    @DisplayName("A revision's chain is found by its account and stableId, and by no other account's")
    void findsTheChainByAccountAndStableId() {
        AccountEntity account = newAccount("user@example.com");
        AccountEntity other = newAccount("other@example.com");
        keep("<draft@voxrox.org>", "stable-draft", "chain", account, LocalDateTime.now());
        em.clear();

        assertThat(repository.findChainId(account.getId(), "stable-draft")).contains("chain");
        assertThat(repository.findChainId(other.getId(), "stable-draft")).isEmpty();
        assertThat(repository.findChainId(account.getId(), "stable-unknown")).isEmpty();
    }

    @Test
    @DisplayName("Marking a revision superseded touches only that account's current entry with the stableId")
    void marksOnlyTheNamedCurrentEntry() {
        AccountEntity account = newAccount("user@example.com");
        AccountEntity other = newAccount("other@example.com");
        LocalDateTime now = LocalDateTime.now();
        keep("<a@voxrox.org>", "stable-a", account, now);
        keep("<b@voxrox.org>", "stable-b", account, now);
        keep("<other@voxrox.org>", "stable-a", other, now);
        em.clear();

        assertThat(repository.markSuperseded(account.getId(), "stable-a", now)).isEqualTo(1);
        assertThat(repository.markSuperseded(account.getId(), "stable-a", now.plusMinutes(1)))
                .as("an entry already superseded keeps its time").isZero();
        em.clear();

        assertThat(repository.findAll()).filteredOn(entry -> entry.getSupersededAt() != null)
                .extracting(DraftRecipientsEntity::getMessageId).containsExactly("<a@voxrox.org>");
    }

    @Test
    @DisplayName("Each kind is bounded on its own: superseded entries neither count against nor push out current ones")
    void theTwoKindsAreBoundedApart() {
        AccountEntity account = newAccount("user@example.com");
        LocalDateTime now = LocalDateTime.now();
        keep("<current-old@voxrox.org>", "stable-current-old", account, now.minusMinutes(10));
        for (int i = 1; i <= 3; i++) {
            keep("<sup" + i + "@voxrox.org>", "stable-sup" + i, account, now.minusMinutes(5 - i));
            repository.markSuperseded(account.getId(), "stable-sup" + i, now);
        }
        em.clear();

        assertThat(repository.deleteCurrentButNewest(account.getId(), 1)).isZero();
        assertThat(repository.deleteSupersededButNewest(account.getId(), 1)).isEqualTo(2);
        em.clear();

        assertThat(repository.findAll()).extracting(DraftRecipientsEntity::getMessageId)
                .containsExactlyInAnyOrder("<current-old@voxrox.org>", "<sup3@voxrox.org>");
    }

    private void keep(String messageId, AccountEntity account, LocalDateTime savedAt) {
        keep(messageId, null, account, savedAt);
    }

    private void keep(String messageId, String stableId, AccountEntity account, LocalDateTime savedAt) {
        keep(messageId, stableId, null, account, savedAt);
    }

    private void keep(String messageId, String stableId, String chainId, AccountEntity account, LocalDateTime savedAt) {
        repository.saveAndFlush(new DraftRecipientsEntity(account.getId(), messageId, stableId, chainId,
                "to@example.com", null, null, savedAt));
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
