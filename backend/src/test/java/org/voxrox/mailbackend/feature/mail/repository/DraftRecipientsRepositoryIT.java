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
    @DisplayName("Only the account's drafts older than its newest few are dropped, however recent they are")
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

        int dropped = repository.deleteDraftsButNewest(account.getId(), "chain-being-saved", 2);

        assertThat(dropped).isEqualTo(2);
        assertThat(repository.findAll()).extracting(DraftRecipientsEntity::getMessageId)
                .containsExactlyInAnyOrder("<c@voxrox.org>", "<d@voxrox.org>", "<other@voxrox.org>");
    }

    @Test
    @DisplayName("An account with no more drafts than it keeps loses none, however old they are")
    void dropsNothingWithinTheBound() {
        AccountEntity account = newAccount("user@example.com");
        stored("<old@voxrox.org>", account, LocalDateTime.now().minusYears(1));
        stored("<new@voxrox.org>", account, LocalDateTime.now());
        em.clear();

        assertThat(repository.deleteDraftsButNewest(account.getId(), "chain-being-saved", 2)).isZero();
        assertThat(repository.findAll()).hasSize(2);
    }

    /**
     * A draft goes whole, every revision at once, by the age of its newest entry —
     * not entry by entry, which left its older revisions to other drafts' saves
     * (B1-5, reopened at 1.53). And the draft being saved stays however old it is,
     * so reopening a draft the user left long ago does not lose its revisions.
     */
    @Test
    @DisplayName("A draft goes whole by the age of its newest entry, and the one being saved stays (B1-5)")
    void aDraftGoesWhole() {
        AccountEntity account = newAccount("user@example.com");
        LocalDateTime now = LocalDateTime.now();
        keep("<reopened1@voxrox.org>", "stable-reopened1", "chain-reopened", account, now.minusDays(9));
        repository.markStored(account.getId(), "chain-reopened", "<reopened1@voxrox.org>", now.minusDays(9));
        keep("<old1@voxrox.org>", "stable-old1", "chain-old", account, now.minusDays(8));
        repository.markStored(account.getId(), "chain-old", "<old1@voxrox.org>", now.minusDays(8));
        keep("<old2@voxrox.org>", "stable-old2", "chain-old", account, now.minusDays(7));
        repository.markStored(account.getId(), "chain-old", "<old2@voxrox.org>", now.minusDays(7));
        keep("<old3@voxrox.org>", "stable-old3", "chain-old", account, now.minusDays(6));
        keep("<recent1@voxrox.org>", "stable-recent1", "chain-recent", account, now.minusMinutes(2));
        repository.markStored(account.getId(), "chain-recent", "<recent1@voxrox.org>", now.minusMinutes(2));
        em.clear();

        assertThat(repository.deleteDraftsButNewest(account.getId(), "chain-reopened", 1)).isEqualTo(3);
        em.clear();

        assertThat(repository.findAll()).extracting(DraftRecipientsEntity::getMessageId)
                .containsExactlyInAnyOrder("<reopened1@voxrox.org>", "<recent1@voxrox.org>");
    }

    /**
     * Of one draft, only the revisions a later save of it followed are bounded, by
     * that draft's own saves: its current entry and its newest stay, and so does
     * every revision of another draft.
     */
    @Test
    @DisplayName("A draft's followed revisions are bounded by its own saves alone (B1-5)")
    void aDraftsRevisionsAreBoundedByItsOwnSaves() {
        AccountEntity account = newAccount("user@example.com");
        LocalDateTime now = LocalDateTime.now();
        keep("<other1@voxrox.org>", "stable-other1", "chain-other", account, now.minusMinutes(20));
        keep("<other2@voxrox.org>", "stable-other2", "chain-other", account, now.minusMinutes(19));
        keep("<x1@voxrox.org>", "stable-x1", "chain-x", account, now.minusMinutes(10));
        keep("<x2@voxrox.org>", "stable-x2", "chain-x", account, now.minusMinutes(9));
        keep("<x3@voxrox.org>", "stable-x3", "chain-x", account, now.minusMinutes(8));
        repository.markStored(account.getId(), "chain-x", "<x3@voxrox.org>", now.minusMinutes(8));
        keep("<x4@voxrox.org>", "stable-x4", "chain-x", account, now.minusMinutes(7));
        keep("<x5@voxrox.org>", "stable-x5", "chain-x", account, now.minusMinutes(6));
        em.clear();

        // x1, x2 and x4 are followed; x3 is current and x5 the newest.
        assertThat(repository.deleteRevisionsButNewest(account.getId(), "chain-x", 1)).isEqualTo(2);
        em.clear();

        assertThat(repository.findAll()).extracting(DraftRecipientsEntity::getMessageId).containsExactlyInAnyOrder(
                "<other1@voxrox.org>", "<other2@voxrox.org>", "<x3@voxrox.org>", "<x4@voxrox.org>", "<x5@voxrox.org>");
    }

    /**
     * B1-5, reopened at 1.31: the entries used to share one count, and a save
     * retired the revision it replaced only when the server let it, so a server
     * without APPENDUID had a hidden draft's entry pushed out by the user's
     * autosaves. Each stored save now sets aside the earlier revisions of its
     * draft, and an account keeps its entries by draft.
     * <p>
     * The bounds here are three drafts and three followed revisions a draft, as the
     * service's are a thousand and a hundred, each pruned to one fewer before a
     * save, as the service prunes them.
     */
    @Test
    @DisplayName("Autosaves of one draft never push out another draft's entry (B1-5)")
    void autosavesDoNotPushOutAnotherDraft() {
        AccountEntity account = newAccount("user@example.com");

        autosave(account, 10, true);

        assertThat(currentEntries()).containsExactlyInAnyOrder("<hidden@voxrox.org>", "<rev10@voxrox.org>");
        // Pruned to one fewer than the bound before each save, then one more set aside
        // once it is stored.
        assertThat(repository.findAll()).filteredOn(entry -> entry.getSupersededAt() != null)
                .hasSizeLessThanOrEqualTo(REVISIONS);
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
     * them from growing.
     */
    @Test
    @DisplayName("Saves that store nothing never push out another draft's entry either (B1-5)")
    void rejectedSavesDoNotPushOutAnotherDraft() {
        AccountEntity account = newAccount("user@example.com");
        LocalDateTime start = LocalDateTime.now().minusHours(1);
        stored("<hidden@voxrox.org>", account, start);
        stored("<stored@voxrox.org>", account, start.plusSeconds(1));
        for (int save = 1; save <= 10; save++) {
            pruneForSave(account, "stable-<stored@voxrox.org>");
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
     * draft kept none.
     */
    @Test
    @DisplayName("A draft's newest entry not yet stored outlasts any number of another draft's autosaves (B1-5)")
    void aDraftsNewestEntryOutlastsAnotherDraftsAutosaves() {
        AccountEntity account = newAccount("user@example.com");
        LocalDateTime start = LocalDateTime.now().minusHours(1);
        keep("<x1@voxrox.org>", "stable-x1", "chain-x", account, start);
        repository.markStored(account.getId(), "chain-x", "<x1@voxrox.org>", start);
        keep("<x2@voxrox.org>", "stable-x2", "chain-x", account, start.plusSeconds(1));
        autosaveAnotherDraft(account, start);

        assertThat(repository.findAll()).extracting(DraftRecipientsEntity::getMessageId)
                .as("the draft's newest entry and the one it is current at")
                .contains("<x1@voxrox.org>", "<x2@voxrox.org>");
        // The other draft's followed revisions pruned to one fewer than the bound
        // before each save, one more set aside once it is stored, and its current one.
        assertThat(repository.findAll()).filteredOn(entry -> "chain-y".equals(entry.getChainId()))
                .hasSize(REVISIONS + 1);
    }

    /**
     * B1-5, reopened at 1.53. The revisions a later save of their draft had
     * followed shared one bound per account with every other draft's saves, so a
     * revision a server keeps by withholding APPENDUID — the one before the newest
     * among them — was pushed out by some thousand autosaves of another draft, and
     * the server could present it in place of the newest. They are now bounded by
     * their own draft's saves alone.
     */
    @Test
    @DisplayName("A draft's replaced revisions outlast any number of another draft's autosaves (B1-5)")
    void aDraftsReplacedRevisionsOutlastAnotherDraftsAutosaves() {
        AccountEntity account = newAccount("user@example.com");
        LocalDateTime start = LocalDateTime.now().minusHours(1);
        for (int revision = 1; revision <= 3; revision++) {
            String messageId = "<x" + revision + "@voxrox.org>";
            keep(messageId, "stable-x" + revision, "chain-x", account, start.plusSeconds(revision));
            repository.markStored(account.getId(), "chain-x", messageId, start.plusSeconds(revision));
        }
        autosaveAnotherDraft(account, start);

        assertThat(repository.findAll()).extracting(DraftRecipientsEntity::getMessageId)
                .as("every revision of the draft, the two it replaced among them")
                .contains("<x1@voxrox.org>", "<x2@voxrox.org>", "<x3@voxrox.org>");
    }

    /** Three, as the service's {@code KEPT_DRAFTS} is a thousand. */
    private static final int DRAFTS = 3;

    /** Three, as the service's {@code KEPT_REVISIONS_PER_DRAFT} is a hundred. */
    private static final int REVISIONS = 3;

    /**
     * What the service prunes before it keeps a save's entry in {@code chainId}.
     */
    private void pruneForSave(AccountEntity account, String chainId) {
        repository.deleteDraftsButNewest(account.getId(), chainId, DRAFTS - 1);
        repository.deleteRevisionsButNewest(account.getId(), chainId, REVISIONS - 1);
    }

    /**
     * Ten stored saves of draft {@code chain-y}, on a server that withholds
     * APPENDUID so each replaced revision's entry stays, set aside.
     */
    private void autosaveAnotherDraft(AccountEntity account, LocalDateTime start) {
        for (int save = 1; save <= 10; save++) {
            pruneForSave(account, "chain-y");
            String messageId = "<y" + save + "@voxrox.org>";
            keep(messageId, "stable-y" + save, "chain-y", account, start.plusMinutes(save));
            repository.markStored(account.getId(), "chain-y", messageId, start.plusMinutes(save));
        }
        em.clear();
    }

    /**
     * A hidden draft's entry, then {@code saves} stored saves of another draft in
     * the service's order: prune, keep the new revision's entry, and store it — in
     * the draft's chain when {@code inChain}, so it sets the earlier revisions
     * aside, and otherwise in a chain of its own.
     */
    private void autosave(AccountEntity account, int saves, boolean inChain) {
        LocalDateTime start = LocalDateTime.now().minusHours(1);
        stored("<hidden@voxrox.org>", account, start);
        for (int save = 1; save <= saves; save++) {
            String messageId = "<rev" + save + "@voxrox.org>";
            String chainId = inChain ? "stable-rev1" : "stable-rev" + save;
            pruneForSave(account, chainId);
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
    @DisplayName("The newest drafts an account keeps are the last written, whatever their time")
    void newestGoesByTheOrderWritten() {
        AccountEntity account = newAccount("user@example.com");
        LocalDateTime now = LocalDateTime.now();
        stored("<written-first@voxrox.org>", account, now);
        stored("<written-last@voxrox.org>", account, now.minusHours(1));
        em.clear();

        assertThat(repository.deleteDraftsButNewest(account.getId(), "chain-being-saved", 1)).isEqualTo(1);
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

    private void keep(String messageId, AccountEntity account, LocalDateTime savedAt) {
        keep(messageId, null, "chain-" + messageId, account, savedAt);
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
