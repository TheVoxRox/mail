package org.voxrox.mailbackend.feature.mail.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
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

    /**
     * B1-5, reopened at 1.46: an entry counted as current from the save's
     * acceptance, so the saves a server kept waiting for the APPEND lane took
     * current places. It becomes current only once its revision is stored.
     */
    @Test
    @DisplayName("An entry is current only once its revision is stored, and only that account's (B1-5)")
    void anEntryIsCurrentOnlyOnceStored() {
        AccountEntity account = newAccount("user@example.com");
        AccountEntity other = newAccount("other@example.com");
        LocalDateTime now = LocalDateTime.now();
        keep("<draft@voxrox.org>", "stable-a", "chain", account, now);
        keep("<other-account@voxrox.org>", "stable-a", "chain", other, now);
        em.clear();

        assertThat(currentEntries()).as("accepted, not yet stored").isEmpty();
        assertThat(repository.markStored(account.getId(), "chain", "<draft@voxrox.org>", now)).isEqualTo(1);
        em.clear();

        assertThat(currentEntries()).containsExactly("<draft@voxrox.org>");
    }

    @Test
    @DisplayName("Only the account's heads older than its newest few are dropped, however recent they are")
    void keepsTheAccountsNewestEntries() {
        AccountEntity account = newAccount("user@example.com");
        AccountEntity other = newAccount("other@example.com");
        LocalDateTime now = LocalDateTime.now();
        stored("<a@voxrox.org>", account, now.minusMinutes(4));
        stored("<b@voxrox.org>", account, now.minusMinutes(3));
        stored("<c@voxrox.org>", account, now.minusMinutes(2));
        stored("<d@voxrox.org>", account, now.minusMinutes(1));
        stored("<other@voxrox.org>", other, now.minusDays(30));
        em.clear();

        int dropped = repository.deleteHeadsButNewest(account.getId(), 2);

        assertThat(dropped).isEqualTo(2);
        assertThat(repository.findAll()).extracting(DraftRecipientsEntity::getMessageId)
                .containsExactlyInAnyOrder("<c@voxrox.org>", "<d@voxrox.org>", "<other@voxrox.org>");
    }

    @Test
    @DisplayName("An account with no more entries than it keeps loses none, however old they are")
    void dropsNothingWithinTheBound() {
        AccountEntity account = newAccount("user@example.com");
        stored("<old@voxrox.org>", account, LocalDateTime.now().minusYears(1));
        stored("<new@voxrox.org>", account, LocalDateTime.now());
        em.clear();

        assertThat(repository.deleteHeadsButNewest(account.getId(), 2)).isZero();
        assertThat(repository.findAll()).hasSize(2);
    }

    /**
     * B1-5, reopened at 1.31: the entries used to share one count, and a save
     * retired the revision it replaced only when the server let it, so a server
     * without APPENDUID had a hidden draft's entry pushed out by the user's
     * autosaves. Each stored save now sets aside the earlier revisions of its
     * draft, and the two kinds are bounded apart.
     * <p>
     * The bound here is three, as the service's is a thousand, and the current
     * entries are pruned to one fewer before each save, as the service prunes them,
     * leaving the place the save's revision takes once stored.
     */
    @Test
    @DisplayName("Autosaves of one draft never push out another draft's entry (B1-5)")
    void autosavesDoNotPushOutAnotherDraft() {
        AccountEntity account = newAccount("user@example.com");

        autosave(account, 10, true);

        assertThat(currentEntries()).containsExactlyInAnyOrder("<hidden@voxrox.org>", "<rev10@voxrox.org>");
        // Pruned to the bound before each save, then one more set aside after it.
        assertThat(repository.findAll()).filteredOn(entry -> entry.getSupersededAt() != null)
                .hasSizeLessThanOrEqualTo(BOUND + 1);
    }

    /**
     * The same autosaves with each revision in a draft of its own — a save that
     * retires nothing, what a save did until 1.36 when the server gave it no row to
     * delete — push the hidden draft's entry out.
     */
    @Test
    @DisplayName("Without the chain, the same autosaves push the hidden draft's entry out")
    void withoutTheChainAutosavesPushItOut() {
        AccountEntity account = newAccount("user@example.com");

        autosave(account, 10, false);

        assertThat(repository.findAll()).extracting(DraftRecipientsEntity::getMessageId)
                .doesNotContain("<hidden@voxrox.org>");
    }

    /**
     * The same push-out by a server rejecting every APPEND, found by the
     * verification pass over 1.37, and by one keeping every save waiting, found by
     * the pass over 1.44: each save keeps its entry before the append, and only
     * keeping a revision the server has not stored out of the current entries keeps
     * them from growing. The rejected draft holds two heads, its stored revision
     * and its newest.
     */
    @Test
    @DisplayName("Saves that store nothing never push out another draft's entry either (B1-5)")
    void rejectedSavesDoNotPushOutAnotherDraft() {
        AccountEntity account = newAccount("user@example.com");
        LocalDateTime start = LocalDateTime.now().minusHours(1);
        stored("<hidden@voxrox.org>", account, start);
        stored("<stored@voxrox.org>", account, start.plusSeconds(1));
        for (int save = 1; save <= 10; save++) {
            repository.deleteHeadsButNewest(account.getId(), BOUND - 1);
            repository.deleteFollowedButNewest(account.getId(), BOUND);
            keep("<rejected" + save + "@voxrox.org>", "stable-rejected" + save, "stable-<stored@voxrox.org>", account,
                    start.plusMinutes(save));
        }
        em.clear();

        assertThat(currentEntries()).containsExactlyInAnyOrder("<hidden@voxrox.org>", "<stored@voxrox.org>");
    }

    /**
     * B1-5, reopened at 1.48. A draft's newest revision is not current while its
     * save waits for the server, when the server answers the APPEND NO but keeps
     * the revision, or when the statement that makes it current fails, and the
     * entries that are not current shared one bound with every other draft's saves:
     * some thousand autosaves of another draft, on a server that withholds
     * APPENDUID so each replaced revision's entry stays, pushed it out, and the
     * draft kept none. A draft's newest is a head whatever its state.
     */
    @Test
    @DisplayName("A draft's newest entry not yet stored outlasts any number of another draft's autosaves (B1-5)")
    void aDraftsNewestEntryOutlastsAnotherDraftsAutosaves() {
        AccountEntity account = newAccount("user@example.com");
        LocalDateTime start = LocalDateTime.now().minusHours(1);
        keep("<x1@voxrox.org>", "stable-x1", "chain-x", account, start);
        repository.markStored(account.getId(), "chain-x", "<x1@voxrox.org>", start);
        keep("<x2@voxrox.org>", "stable-x2", "chain-x", account, start.plusSeconds(1));
        for (int save = 1; save <= 10; save++) {
            repository.deleteHeadsButNewest(account.getId(), BOUND - 1);
            repository.deleteFollowedButNewest(account.getId(), BOUND);
            String messageId = "<y" + save + "@voxrox.org>";
            keep(messageId, "stable-y" + save, "chain-y", account, start.plusMinutes(save));
            repository.markStored(account.getId(), "chain-y", messageId, start.plusMinutes(save));
        }
        em.clear();

        assertThat(repository.findAll()).extracting(DraftRecipientsEntity::getMessageId)
                .as("the draft's newest entry and the one it is current at")
                .contains("<x1@voxrox.org>", "<x2@voxrox.org>");
        // Followed entries pruned to the bound before each save, one more set aside
        // after it, and the draft's head.
        assertThat(repository.findAll()).filteredOn(entry -> "chain-y".equals(entry.getChainId())).hasSize(BOUND + 2);
    }

    /**
     * Four, as the service's is a thousand: a draft whose last save is not stored
     * holds two heads, its current entry and its newest.
     */
    private static final int BOUND = 4;

    /**
     * A hidden draft's entry, then {@code saves} stored saves of another draft in
     * the service's order: prune both kinds, keep the new revision's entry, and
     * store it — in the draft's chain when {@code inChain}, so it sets the earlier
     * revisions aside, and otherwise in a chain of its own.
     */
    private void autosave(AccountEntity account, int saves, boolean inChain) {
        LocalDateTime start = LocalDateTime.now().minusHours(1);
        stored("<hidden@voxrox.org>", account, start);
        for (int save = 1; save <= saves; save++) {
            repository.deleteHeadsButNewest(account.getId(), BOUND - 1);
            repository.deleteFollowedButNewest(account.getId(), BOUND);
            String messageId = "<rev" + save + "@voxrox.org>";
            String chainId = inChain ? "stable-rev1" : "stable-rev" + save;
            keep(messageId, "stable-rev" + save, chainId, account, start.plusMinutes(save));
            repository.markStored(account.getId(), chainId, messageId, start.plusMinutes(save));
        }
        em.clear();
    }

    /**
     * B1-5, found by the verification pass over 1.39: a stored revision sets aside
     * the earlier revisions of its draft by their chain, whichever of them its save
     * named, and leaves the rest alone — a newer revision of the chain, another
     * draft's chain, and another account's entries. Since 1.47 the same statement
     * makes the stored revision current, and an earlier revision still waiting for
     * the server is set aside with the stored ones.
     */
    @Test
    @DisplayName("A stored revision becomes current and sets aside only the account's earlier entries of its chain")
    void setsAsideOnlyTheEarlierEntriesOfTheChain() {
        AccountEntity account = newAccount("user@example.com");
        AccountEntity other = newAccount("other@example.com");
        // SQLite keeps milliseconds, so the time read back is compared at that
        // precision.
        LocalDateTime start = LocalDateTime.now().minusHours(1).truncatedTo(ChronoUnit.MILLIS);
        keep("<set-aside-before@voxrox.org>", "stable-o", "chain", account, start);
        keep("<stored-before@voxrox.org>", "stable-a", "chain", account, start.plusMinutes(1));
        repository.markStored(account.getId(), "chain", "<stored-before@voxrox.org>", start.plusMinutes(1));
        keep("<waiting@voxrox.org>", "stable-b", "chain", account, start.plusMinutes(2));
        keep("<stored@voxrox.org>", "stable-c", "chain", account, start.plusMinutes(3));
        keep("<newer@voxrox.org>", "stable-d", "chain", account, start.plusMinutes(4));
        keep("<another-draft@voxrox.org>", "stable-e", "another-chain", account, start);
        repository.markStored(account.getId(), "another-chain", "<another-draft@voxrox.org>", start);
        keep("<other-account@voxrox.org>", "stable-a", "chain", other, start);
        repository.markStored(other.getId(), "chain", "<other-account@voxrox.org>", start);
        em.clear();

        assertThat(repository.markStored(account.getId(), "chain", "<stored@voxrox.org>", start.plusMinutes(5)))
                .isEqualTo(3);
        em.clear();

        assertThat(currentEntries()).containsExactlyInAnyOrder("<stored@voxrox.org>", "<another-draft@voxrox.org>",
                "<other-account@voxrox.org>");
        assertThat(entry("<set-aside-before@voxrox.org>").getSupersededAt())
                .as("an entry already set aside keeps its time").isEqualTo(start.plusMinutes(1));
        assertThat(entry("<waiting@voxrox.org>").getSupersededAt()).as("an earlier save still waiting is set aside")
                .isEqualTo(start.plusMinutes(5));
        assertThat(entry("<newer@voxrox.org>")).as("a later save is left to its own outcome")
                .extracting(DraftRecipientsEntity::getStoredAt, DraftRecipientsEntity::getSupersededAt)
                .containsExactly(null, null);
    }

    /**
     * A save overtaken by a later one of its draft: its entry was set aside when
     * the later revision was stored, and it does not become current when its own
     * APPEND is stored at last. Until 1.47 the later revision's entry, current from
     * acceptance, stood beside the overtaken one's while it waited.
     */
    @Test
    @DisplayName("A revision a later stored one has set aside stays so when it is stored late")
    void aRevisionSetAsideStaysSoWhenStoredLate() {
        AccountEntity account = newAccount("user@example.com");
        LocalDateTime now = LocalDateTime.now();
        keep("<slow@voxrox.org>", "stable-a", "chain", account, now);
        keep("<fast@voxrox.org>", "stable-b", "chain", account, now.plusSeconds(1));
        em.clear();

        assertThat(repository.markStored(account.getId(), "chain", "<fast@voxrox.org>", now)).isEqualTo(2);
        assertThat(repository.markStored(account.getId(), "chain", "<slow@voxrox.org>", now.plusSeconds(1))).isZero();
        assertThat(repository.markStored(account.getId(), "chain", "<unknown@voxrox.org>", now))
                .as("a revision with no entry here changes nothing").isZero();
        em.clear();

        assertThat(currentEntries()).containsExactly("<fast@voxrox.org>");
        assertThat(entry("<slow@voxrox.org>").getStoredAt()).isNull();
    }

    /**
     * B1-5, from the pass over 1.40: "earlier" went by {@code saved_at}, so after
     * the clock stepped back — a DST fall-back, a time correction — the revision
     * saved before stayed current until a save passed its time. It goes by the
     * order the entries were written in.
     */
    @Test
    @DisplayName("A revision written before the stored one is set aside even if the clock has since stepped back")
    void earlierGoesByTheOrderWrittenNotTheClock() {
        AccountEntity account = newAccount("user@example.com");
        LocalDateTime now = LocalDateTime.now();
        keep("<before-the-step@voxrox.org>", "stable-a", "chain", account, now);
        repository.markStored(account.getId(), "chain", "<before-the-step@voxrox.org>", now);
        keep("<after-the-step@voxrox.org>", "stable-b", "chain", account, now.minusHours(1));
        em.clear();

        assertThat(repository.markStored(account.getId(), "chain", "<after-the-step@voxrox.org>", now)).isEqualTo(2);
        em.clear();

        assertThat(currentEntries()).containsExactly("<after-the-step@voxrox.org>");
    }

    @Test
    @DisplayName("The newest entries an account keeps are the last written, whatever their time")
    void newestGoesByTheOrderWritten() {
        AccountEntity account = newAccount("user@example.com");
        LocalDateTime now = LocalDateTime.now();
        stored("<written-first@voxrox.org>", account, now);
        stored("<written-last@voxrox.org>", account, now.minusHours(1));
        em.clear();

        assertThat(repository.deleteHeadsButNewest(account.getId(), 1)).isEqualTo(1);
        em.clear();

        assertThat(repository.findAll()).extracting(DraftRecipientsEntity::getMessageId)
                .containsExactly("<written-last@voxrox.org>");
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

    /**
     * Heads on one side — each draft's current entry and its newest, here a save
     * still waiting for the server, which until 1.47 counted as current and until
     * 1.50 was bounded with the saves (B1-5, reopened at 1.46 and 1.48); on the
     * other, the revisions a later save of their draft followed, set aside.
     */
    @Test
    @DisplayName("Each kind is bounded on its own: followed entries neither count against nor push out heads")
    void theTwoKindsAreBoundedApart() {
        AccountEntity account = newAccount("user@example.com");
        LocalDateTime now = LocalDateTime.now();
        stored("<current-old@voxrox.org>", account, now.minusMinutes(10));
        keep("<rev1@voxrox.org>", "stable-rev1", "chain", account, now.minusMinutes(4));
        keep("<rev2@voxrox.org>", "stable-rev2", "chain", account, now.minusMinutes(3));
        keep("<rev3@voxrox.org>", "stable-rev3", "chain", account, now.minusMinutes(2));
        repository.markStored(account.getId(), "chain", "<rev3@voxrox.org>", now);
        keep("<waiting@voxrox.org>", "stable-rev4", "chain", account, now.minusMinutes(1));
        em.clear();

        assertThat(repository.deleteHeadsButNewest(account.getId(), 3)).isZero();
        assertThat(repository.deleteFollowedButNewest(account.getId(), 1)).isEqualTo(1);
        em.clear();

        assertThat(repository.findAll()).extracting(DraftRecipientsEntity::getMessageId).containsExactlyInAnyOrder(
                "<current-old@voxrox.org>", "<rev2@voxrox.org>", "<rev3@voxrox.org>", "<waiting@voxrox.org>");
        assertThat(repository.deleteHeadsButNewest(account.getId(), 2)).as("the oldest head goes first").isEqualTo(1);
        em.clear();
        assertThat(repository.findAll()).extracting(DraftRecipientsEntity::getMessageId)
                .containsExactlyInAnyOrder("<rev2@voxrox.org>", "<rev3@voxrox.org>", "<waiting@voxrox.org>");
    }

    private void keep(String messageId, AccountEntity account, LocalDateTime savedAt) {
        keep(messageId, null, null, account, savedAt);
    }

    private void keep(String messageId, String stableId, String chainId, AccountEntity account, LocalDateTime savedAt) {
        // The way the service writes an entry, numbered in the order of these calls.
        repository.insertEntry(account.getId(), messageId, stableId, chainId, "to@example.com", null, null, savedAt);
    }

    /** An entry kept and stored as the first revision of a draft of its own. */
    private void stored(String messageId, AccountEntity account, LocalDateTime savedAt) {
        String stableId = "stable-" + messageId;
        keep(messageId, stableId, stableId, account, savedAt);
        repository.markStored(account.getId(), stableId, messageId, savedAt);
    }

    /**
     * Entries whose revision the server stored and no later stored save set aside.
     */
    private List<String> currentEntries() {
        return repository.findAll().stream()
                .filter(entry -> entry.getStoredAt() != null && entry.getSupersededAt() == null)
                .map(DraftRecipientsEntity::getMessageId).toList();
    }

    private DraftRecipientsEntity entry(String messageId) {
        return repository.findAll().stream().filter(candidate -> messageId.equals(candidate.getMessageId())).findFirst()
                .orElseThrow();
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
