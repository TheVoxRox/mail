package org.voxrox.mailbackend.feature.contact.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.UUID;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.AdditionalAnswers;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.Sql.ExecutionPhase;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.voxrox.mailbackend.feature.account.entity.AccountEntity;
import org.voxrox.mailbackend.feature.account.entity.MailProviderEntity;
import org.voxrox.mailbackend.feature.account.entity.MailServerConfig;
import org.voxrox.mailbackend.feature.account.repository.AccountRepository;
import org.voxrox.mailbackend.feature.account.repository.MailProviderRepository;
import org.voxrox.mailbackend.feature.contact.entity.CorrespondentEntity;
import org.voxrox.mailbackend.feature.contact.repository.CorrespondentRepository;
import org.voxrox.mailbackend.feature.mail.repository.FolderSyncStateRepository;
import org.voxrox.mailbackend.feature.mail.repository.MessageRepository;

/**
 * Integration tests for the correspondent backfill against real SQLite.
 *
 * <p>
 * Two things here are only reachable end to end. The batch query is a native
 * projection whose {@code receivedAt} is a {@code LocalDateTime} — a mapping no
 * unit test exercises and whose failure would surface at runtime, on the startup
 * pass, as a broken cache. And the empty-cache guard is the whole re-entry
 * contract: it is what stops a second run from doubling every counter, and what
 * {@code rebuildAccount} deliberately re-arms.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("it")
@Sql(statements = {"DELETE FROM correspondent", "DELETE FROM messages", "DELETE FROM folder_sync_state",
        "DELETE FROM accounts", "DELETE FROM mail_providers"}, executionPhase = ExecutionPhase.BEFORE_TEST_METHOD)
class CorrespondentBackfillIT {

    private static final Path DB_DIR = Path
            .of("target", "test-tmp", "CorrespondentBackfillIT", UUID.randomUUID().toString()).toAbsolutePath()
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

    private static final LocalDateTime RECEIVED_AT = LocalDateTime.of(2026, 5, 20, 8, 30);

    @Autowired
    private CorrespondentRepository correspondentRepository;
    @Autowired
    private MessageRepository messageRepository;
    @Autowired
    private FolderSyncStateRepository folderSyncStateRepository;
    @Autowired
    private AccountRepository accountRepository;
    @Autowired
    private MailProviderRepository providerRepository;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private EntityManager em;

    private AccountEntity account;
    private CorrespondentBackfillService backfillService;

    @BeforeEach
    void setUp() {
        MailProviderEntity provider = new MailProviderEntity();
        provider.setName("TestProvider");
        provider.setDomains(",example.com,");
        provider.setImapConfig(new MailServerConfig("imap.example.com", 993, true));
        provider.setSmtpConfig(new MailServerConfig("smtp.example.com", 465, true));
        provider = providerRepository.saveAndFlush(provider);

        AccountEntity a = new AccountEntity();
        a.setAccountName("Test");
        a.setEmail("owner@example.com");
        a.setDisplayName("Owner");
        a.setProvider(provider);
        a.setImapConfig(new MailServerConfig("imap.example.com", 993, true));
        a.setSmtpConfig(new MailServerConfig("smtp.example.com", 465, true));
        a.setActive(true);
        account = accountRepository.saveAndFlush(a);

        backfillService = new CorrespondentBackfillService(accountRepository, messageRepository,
                folderSyncStateRepository, correspondentRepository, new CorrespondentService(correspondentRepository),
                new TransactionTemplate(transactionManager));
    }

    /**
     * Inserts a message row directly. Native SQL rather than the mapper: the point
     * is to reproduce what the sync leaves behind, and MessageEntity would drag the
     * whole persist path (and the FTS triggers' expectations) into the fixture.
     */
    private void insertMessage(String folder, String sender, String recipientsTo) {
        em.createNativeQuery("""
                INSERT INTO messages (stable_id, account_id, folder_name, uid, uid_validity, subject,
                                      sender, recipients_to, received_at, seen, flagged, answered,
                                      body_oversize, has_attachments)
                VALUES (:stableId, :accountId, :folder, :uid, 1, 'Subject', :sender, :to, :receivedAt, 0, 0, 0, 0, 0)
                """).setParameter("stableId", UUID.randomUUID().toString().substring(0, 30))
                .setParameter("accountId", account.getId()).setParameter("folder", folder)
                .setParameter("uid", System.nanoTime() % 1_000_000).setParameter("sender", sender)
                .setParameter("to", recipientsTo).setParameter("receivedAt", RECEIVED_AT).executeUpdate();
    }

    private void insertFolder(String folder, String role) {
        em.createNativeQuery("""
                INSERT INTO folder_sync_state (account_id, folder_name, role, last_known_uid, version)
                VALUES (:accountId, :folder, :role, 0, 0)
                """).setParameter("accountId", account.getId()).setParameter("folder", folder)
                .setParameter("role", role).executeUpdate();
    }

    private void seedMailbox() {
        insertFolder("INBOX", "INBOX");
        insertFolder("Sent", "SENT");
        insertFolder("Trash", "TRASH");
        insertMessage("INBOX", "Jana Novak <jana@example.com>", "owner@example.com");
        insertMessage("Sent", "owner@example.com", "Petr Svoboda <petr@example.com>");
        insertMessage("Trash", "spam@example.com", "owner@example.com");
        em.flush();
        em.clear();
    }

    private CorrespondentEntity reload(String email) {
        em.clear();
        return correspondentRepository.findByAccountIdAndEmail(account.getId(), email).orElseThrow();
    }

    @Test
    @DisplayName("backfill harvests the mailbox already in the database, direction taken from the folder role")
    void backfillsExistingMail() {
        seedMailbox();

        int harvested = backfillService.backfillAccount(account);

        assertThat(harvested).isEqualTo(3);
        // The native projection carried folder, headers and — the part no unit
        // test can prove — the received_at timestamp.
        assertThat(reload("jana@example.com").getReceivedCount()).isEqualTo(1);
        assertThat(reload("jana@example.com").getLastSeenAt()).isEqualTo(RECEIVED_AT);
        assertThat(reload("jana@example.com").getDisplayName()).isEqualTo("Jana Novak");
        // From the Sent copy, so it counts as written-to.
        assertThat(reload("petr@example.com").getSentCount()).isEqualTo(1);
        // Trash is skipped, and the account's own address is never harvested.
        assertThat(correspondentRepository.findByAccountIdAndEmail(account.getId(), "spam@example.com")).isEmpty();
        assertThat(correspondentRepository.findByAccountIdAndEmail(account.getId(), "owner@example.com")).isEmpty();
    }

    @Test
    @DisplayName("a second backfill is a no-op — counters must not double")
    void secondRunIsGuarded() {
        seedMailbox();
        backfillService.backfillAccount(account);

        assertThat(backfillService.backfillAccount(account)).isZero();
        assertThat(reload("jana@example.com").getReceivedCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("rebuild drops the cache and harvests again, so a partial backfill is recoverable")
    void rebuildRepairsAPartialCache() {
        seedMailbox();
        // What an interrupted first run leaves behind: some rows present, so the
        // startup guard will never revisit this account.
        correspondentRepository.upsert(account.getId(), "leftover@example.com", null, 0, 1, RECEIVED_AT);
        em.flush();
        assertThat(backfillService.backfillAccount(account)).isZero();

        int harvested = backfillService.rebuildAccount(account);

        assertThat(harvested).isEqualTo(3);
        assertThat(correspondentRepository.findByAccountIdAndEmail(account.getId(), "leftover@example.com")).isEmpty();
        assertThat(reload("jana@example.com").getReceivedCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("an empty mailbox harvests nothing and leaves the guard open")
    void emptyMailboxIsHarmless() {
        assertThat(backfillService.backfillAccount(account)).isZero();
        assertThat(correspondentRepository.countByAccountId(account.getId())).isZero();
    }

    @Test
    @DisplayName("rebuild harvests even when the cache looks non-empty — the startup guard must not apply to it")
    void rebuildIgnoresTheStartupGuard() {
        seedMailbox();

        // Reproduces the race the guard creates for a rebuild: the DELETE commits,
        // and before the harvest starts a concurrent sync pass persists a message
        // and harvests its sender, so the cache is no longer empty. A rebuild that
        // re-entered backfillAccount would read that as "already done", return 0,
        // and leave the caller with a cache that was wiped and never refilled.
        // A delegating mock, not spy(): the injected repository is a Spring Data
        // JDK proxy, which Mockito cannot unwrap to spy on.
        CorrespondentRepository guardAlwaysClosed = Mockito.mock(CorrespondentRepository.class,
                AdditionalAnswers.delegatesTo(correspondentRepository));
        Mockito.doReturn(1L).when(guardAlwaysClosed).countByAccountId(account.getId());
        CorrespondentBackfillService service = new CorrespondentBackfillService(accountRepository, messageRepository,
                folderSyncStateRepository, guardAlwaysClosed, new CorrespondentService(guardAlwaysClosed),
                new TransactionTemplate(transactionManager));

        assertThat(service.rebuildAccount(account)).isEqualTo(3);
        assertThat(reload("jana@example.com").getReceivedCount()).isEqualTo(1);

        // And the guard still stops a second startup pass on the same service.
        assertThat(service.backfillAccount(account)).isZero();
    }

    @Test
    @DisplayName("the sweep is bounded by the highest message id it saw at the start")
    void ceilingIsPinnedBeforeTheSweep() {
        seedMailbox();
        Long ceiling = messageRepository.findMaxMessageIdByAccount(account.getId());
        assertThat(ceiling).isNotNull();

        // Messages arriving after the ceiling was taken are the sync's to harvest —
        // it does so inline in saveMessagesBatchAtomic — so the backfill must not
        // walk into them and count them a second time.
        insertMessage("INBOX", "later@example.com", "owner@example.com");
        em.flush();
        em.clear();

        assertThat(messageRepository.findMessagesForCorrespondentBackfill(account.getId(), 0L, ceiling,
                PageRequest.of(0, 200))).hasSize(3).noneMatch(row -> "later@example.com".equals(row.sender()));
    }
}
