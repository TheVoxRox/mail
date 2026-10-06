package org.voxrox.mailbackend.feature.mail.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import javax.sql.DataSource;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;

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
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.voxrox.mailbackend.feature.account.entity.AccountEntity;
import org.voxrox.mailbackend.feature.account.entity.MailServerConfig;
import org.voxrox.mailbackend.feature.account.repository.AccountRepository;
import org.voxrox.mailbackend.feature.account.service.AccountService;
import org.voxrox.mailbackend.feature.mail.dto.DraftRequest;
import org.voxrox.mailbackend.feature.mail.dto.FolderRole;
import org.voxrox.mailbackend.feature.mail.entity.DraftRecipientsEntity;
import org.voxrox.mailbackend.feature.mail.mapper.MessageMapper;
import org.voxrox.mailbackend.feature.mail.repository.DraftRecipientsRepository;

/**
 * {@link DraftPersistenceService} keeping typed recipients in a real SQLite
 * database, with the IMAP side stubbed so the test decides which APPENDs the
 * server stores (IMAP/SMTP audit B1-5). A save goes the way the controller
 * sends it: {@code acceptDraftSave} before the 202, then
 * {@code saveDraftAsync}. No test transaction: each repository call commits on
 * its own, as it does in the running app, so what a later query sees is what an
 * earlier one wrote.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("it")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Sql(statements = {"DELETE FROM draft_recipients", "DELETE FROM messages", "DELETE FROM account_credentials",
        "DELETE FROM accounts"}, executionPhase = ExecutionPhase.BEFORE_TEST_METHOD)
class DraftRecipientsChainIT {

    private static final Path DB_DIR = Path
            .of("target", "test-tmp", "DraftRecipientsChainIT", UUID.randomUUID().toString()).toAbsolutePath()
            .normalize();

    @DynamicPropertySource
    static void configureSqliteDatasource(DynamicPropertyRegistry registry) {
        try {
            Files.createDirectories(DB_DIR);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot create directory for SQLite test DB: " + DB_DIR, e);
        }
        Path dbFile = DB_DIR.resolve("test.db");
        // The production pragmas and pool (application.properties), not the IT
        // profile's single connection: with one connection a second writer waits for
        // the pool, never for SQLite's lock, and no lock conflict can happen at all.
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + dbFile.toAbsolutePath()
                + "?journal_mode=WAL&synchronous=NORMAL&foreign_keys=ON&busy_timeout=5000");
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "4");
    }

    @Autowired
    private DraftRecipientsRepository repository;
    @Autowired
    private AccountRepository accountRepository;
    @Autowired
    private DataSource dataSource;

    private final ImapAppendService appendService = mock(ImapAppendService.class);
    private final MimeMessageBuilder builder = mock(MimeMessageBuilder.class);
    /** Whether the server stores the next APPEND; it answers without APPENDUID. */
    private final AtomicBoolean storeNext = new AtomicBoolean(true);
    private AccountEntity account;
    private DraftPersistenceService service;

    @BeforeEach
    void setUp() throws Exception {
        account = newAccount("user@example.com");
        AccountService accountService = mock(AccountService.class);
        when(accountService.getAccountOrThrow(account.getId())).thenReturn(account);
        ImapFolderService folders = mock(ImapFolderService.class);
        when(folders.findFolderNameByRoleOrThrow(account.getId(), FolderRole.DRAFTS)).thenReturn("Drafts");
        when(builder.build(any(), any(), any(), any(), any())).thenAnswer(invocation -> mock(MimeMessage.class));
        when(appendService.appendDraft(anyLong(), anyString(), any()))
                .thenAnswer(invocation -> new ImapAppendService.DraftAppendOutcome(storeNext.get(), null, null));
        // No message rows: without APPENDUID a save writes none, so a replaced
        // revision is never found and never deleted from the server.
        service = new DraftPersistenceService(accountService, folders, mock(MessageService.class),
                mock(ImapActionService.class), appendService, builder, mock(MessageMapper.class),
                mock(AccountRepository.class), repository);
    }

    /**
     * Found by the verification pass over 1.39. The client names as the revision a
     * save replaces the stableId the previous save returned, and the 202 returns it
     * before the APPEND's outcome, so after a rejected save the next one names the
     * rejected revision and the last stored one is named by no save at all. A
     * server rejecting every other APPEND left that stored revision current each
     * time — one more current entry per two autosaves — and could push out a hidden
     * draft's entry that way, the attack of 1.31 by a third route.
     */
    @Test
    @DisplayName("Saves after a rejected one still leave the draft a single current entry (B1-5)")
    void aRejectedSaveDoesNotLeaveTheStoredRevisionCurrent() {
        DraftPersistenceService.DraftIdentity hidden = save(null, true);
        DraftPersistenceService.DraftIdentity previous = null;
        DraftPersistenceService.DraftIdentity lastStored = null;
        for (int revision = 1; revision <= 10; revision++) {
            boolean stored = revision % 2 == 1;
            previous = save(previous == null ? null : previous.stableId(), stored);
            if (stored) {
                lastStored = previous;
            }
        }

        assertThat(currentEntries()).containsExactlyInAnyOrder(hidden.messageId(), lastStored.messageId());
    }

    /**
     * Found by the pass over 1.40 (B1-5, reopened at 1.41). The entry was written
     * by the async save, after the 202 had handed its stableId to the client, so
     * the next save could look its chain up before the entry existed — and a server
     * holding the APPEND lane while saves queued, then answering fast, made exactly
     * that happen, splitting the chain with every APPEND stored. The entry is now
     * written when the save is accepted. Here every save is accepted before any
     * async save runs, and those then run newest first.
     */
    @Test
    @DisplayName("The chain holds when the save a revision names has not run yet (B1-5)")
    void theChainHoldsBeforeTheNamedSaveRuns() {
        DraftPersistenceService.DraftIdentity hidden = save(null, true);
        List<DraftPersistenceService.DraftIdentity> accepted = new ArrayList<>();
        List<String> named = new ArrayList<>();
        String replaces = null;
        for (int revision = 1; revision <= 5; revision++) {
            DraftPersistenceService.DraftIdentity identity = service.acceptDraftSave(account.getId(), request(),
                    replaces);
            accepted.add(identity);
            named.add(replaces);
            replaces = identity.stableId();
        }
        storeNext.set(true);
        for (int i = accepted.size() - 1; i >= 0; i--) {
            service.saveDraftAsync(account.getId(), request(), named.get(i), accepted.get(i));
        }

        assertThat(currentEntries()).containsExactlyInAnyOrder(hidden.messageId(), accepted.getLast().messageId());
        assertThat(repository.findAll()).extracting(DraftRecipientsEntity::getChainId)
                .filteredOn(chain -> !hidden.stableId().equals(chain)).containsOnly(accepted.getFirst().stableId());
    }

    /**
     * Found by the pass over 1.40 (B1-5, reopened at 1.41). The entry was written
     * by {@code save}, a merge — a SELECT, then the INSERT — and SQLite refuses
     * that read's upgrade to a write while another connection holds the write lock,
     * or once one has committed since the read, without waiting out
     * {@code busy_timeout}: the revision went without an entry and was checked
     * against the server's own copy. Here another connection holds the write lock
     * and commits while the entry waits for it; a read-then-write in its place
     * fails with {@code SQLITE_BUSY} at once.
     */
    @Test
    @DisplayName("The entry is written even when another connection writes meanwhile (B1-5)")
    void theEntryWaitsOutAConcurrentWriter() throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread writer;
        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            try (Statement statement = holder.createStatement()) {
                statement.executeUpdate("UPDATE accounts SET display_name = 'held' WHERE id = " + account.getId());
            }
            writer = Thread.ofPlatform().start(() -> {
                try {
                    repository.insertEntry(account.getId(), "<waiting@voxrox.org>", "stable-waiting", "stable-waiting",
                            "to@example.com", null, null, LocalDateTime.now());
                } catch (Throwable e) {
                    failure.set(e);
                }
            });
            writer.join(300);
            assertThat(failure.get()).as("the entry waits for the lock rather than failing").isNull();
            assertThat(writer.isAlive()).as("still waiting while the other connection holds the lock").isTrue();
            holder.commit();
        }
        writer.join(10_000);

        assertThat(failure.get()).isNull();
        assertThat(repository.findAll()).extracting(DraftRecipientsEntity::getMessageId)
                .containsExactly("<waiting@voxrox.org>");
    }

    /**
     * Found by the pass over 1.42 (B1-5, reopened at 1.43). The entry is kept when
     * the save is accepted, and only a failed APPEND set it aside, so a save whose
     * task stopped before its APPEND left its entry current, and each further save
     * of the draft, naming it, added another. A reply carrying a folded
     * {@code References} header failed the message build every time. Since 1.47
     * such an entry is never current: it becomes so only once its revision is
     * stored.
     */
    @Test
    @DisplayName("Saves whose message cannot be built leave no current entry behind (B1-5)")
    void savesThatCannotBeBuiltLeaveNoCurrentEntry() throws Exception {
        DraftPersistenceService.DraftIdentity hidden = save(null, true);
        when(builder.build(any(), any(), any(), any(), any()))
                .thenThrow(new MessagingException("Illegal line break in References"));
        String replaces = null;
        for (int revision = 1; revision <= 10; revision++) {
            replaces = save(replaces, true).stableId();
        }

        assertThat(currentEntries()).containsExactly(hidden.messageId());
    }

    /**
     * Found by the pass over 1.44 (B1-5, reopened at 1.46). The entry counted as
     * current from the save's acceptance until its task settled it, and the bound
     * prunes the current entries at every acceptance, so a server holding the lane
     * the APPEND waits for kept every later save waiting with a current entry: the
     * thousandth such autosave deleted a hidden draft's entry, though every APPEND
     * was then stored and nothing was recorded. An entry is now current only once
     * its revision is stored. Here every save is accepted before any runs, as while
     * the lane is held, and they then run in order.
     */
    @Test
    @DisplayName("Saves waiting for the server take no current place from another draft (B1-5)")
    void savesWaitingForTheLaneTakeNoCurrentPlace() {
        DraftPersistenceService.DraftIdentity hidden = save(null, true);
        List<DraftPersistenceService.DraftIdentity> accepted = new ArrayList<>();
        List<String> named = new ArrayList<>();
        String replaces = null;
        for (int revision = 1; revision <= DraftPersistenceService.KEPT_DRAFT_RECIPIENTS; revision++) {
            DraftPersistenceService.DraftIdentity identity = service.acceptDraftSave(account.getId(), request(),
                    replaces);
            accepted.add(identity);
            named.add(replaces);
            replaces = identity.stableId();
        }

        assertThat(repository.findById(new DraftRecipientsEntity.Key(account.getId(), hidden.messageId())))
                .as("the hidden draft's entry outlasts the saves waiting for the lane").isPresent();
        assertThat(currentEntries()).containsExactly(hidden.messageId());

        storeNext.set(true);
        for (int i = 0; i < accepted.size(); i++) {
            service.saveDraftAsync(account.getId(), request(), named.get(i), accepted.get(i));
        }

        assertThat(currentEntries()).containsExactlyInAnyOrder(hidden.messageId(), accepted.getLast().messageId());
    }

    /** One save, accepted and run the way the controller sends it. */
    private DraftPersistenceService.DraftIdentity save(String replaces, boolean stored) {
        DraftPersistenceService.DraftIdentity identity = service.acceptDraftSave(account.getId(), request(), replaces);
        storeNext.set(stored);
        service.saveDraftAsync(account.getId(), request(), replaces, identity);
        return identity;
    }

    private static DraftRequest request() {
        return new DraftRequest("to@example.com", null, null, "subject", "body", null, null, null);
    }

    /**
     * Entries whose revision the server stored and no later stored save set aside.
     */
    private List<String> currentEntries() {
        return repository.findAll().stream()
                .filter(entry -> entry.getStoredAt() != null && entry.getSupersededAt() == null)
                .map(DraftRecipientsEntity::getMessageId).toList();
    }

    private AccountEntity newAccount(String email) {
        AccountEntity created = new AccountEntity();
        created.setAccountName("Acct " + email);
        created.setEmail(email);
        created.setDisplayName("User");
        created.setActive(true);
        created.setImapConfig(new MailServerConfig("imap.example.com", 993, true));
        created.setSmtpConfig(new MailServerConfig("smtp.example.com", 465, true));
        return accountRepository.saveAndFlush(created);
    }
}
