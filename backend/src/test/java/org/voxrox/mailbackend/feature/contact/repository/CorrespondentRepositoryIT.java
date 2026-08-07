package org.voxrox.mailbackend.feature.contact.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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
import org.voxrox.mailbackend.feature.account.entity.MailProviderEntity;
import org.voxrox.mailbackend.feature.account.entity.MailServerConfig;
import org.voxrox.mailbackend.feature.account.repository.AccountRepository;
import org.voxrox.mailbackend.feature.account.repository.MailProviderRepository;
import org.voxrox.mailbackend.feature.contact.entity.CorrespondentEntity;
import org.voxrox.mailbackend.feature.contact.service.CorrespondentService;

/**
 * Integration tests for the correspondent cache against real SQLite + Flyway.
 *
 * <p>
 * The logic under test lives in SQL, not in Java: an {@code ON CONFLICT DO
 * UPDATE} whose three assignment clauses each encode a decision, and a ranking
 * query whose ORDER BY is the whole feature. Neither is reachable by a
 * mock-based unit test.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("it")
@Sql(statements = {"DELETE FROM correspondent", "DELETE FROM accounts",
        "DELETE FROM mail_providers"}, executionPhase = ExecutionPhase.BEFORE_TEST_METHOD)
class CorrespondentRepositoryIT {

    private static final Path DB_DIR = Path
            .of("target", "test-tmp", "CorrespondentRepositoryIT", UUID.randomUUID().toString()).toAbsolutePath()
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

    private static final LocalDateTime OLD = LocalDateTime.of(2024, 1, 1, 10, 0);
    private static final LocalDateTime RECENT = LocalDateTime.of(2026, 6, 1, 10, 0);

    @Autowired
    private CorrespondentRepository correspondentRepository;
    @Autowired
    private AccountRepository accountRepository;
    @Autowired
    private MailProviderRepository providerRepository;
    @Autowired
    private EntityManager em;

    private AccountEntity account;
    private AccountEntity otherAccount;

    @BeforeEach
    void setUp() {
        MailProviderEntity provider = new MailProviderEntity();
        provider.setName("TestProvider");
        provider.setDomains(",example.com,");
        provider.setImapConfig(new MailServerConfig("imap.example.com", 993, true));
        provider.setSmtpConfig(new MailServerConfig("smtp.example.com", 465, true));
        provider = providerRepository.saveAndFlush(provider);

        account = accountRepository.save(newAccount("owner1@example.com", provider));
        otherAccount = accountRepository.save(newAccount("owner2@example.com", provider));
    }

    private AccountEntity newAccount(String email, MailProviderEntity provider) {
        AccountEntity a = new AccountEntity();
        a.setAccountName("Test " + email);
        a.setEmail(email);
        a.setDisplayName("Test");
        a.setProvider(provider);
        a.setImapConfig(new MailServerConfig("imap.example.com", 993, true));
        a.setSmtpConfig(new MailServerConfig("smtp.example.com", 465, true));
        a.setActive(true);
        return a;
    }

    /** One received sighting, the shape the harvest produces for incoming mail. */
    private void received(AccountEntity owner, String email, String displayName, LocalDateTime at) {
        correspondentRepository.upsert(owner.getId(), email, displayName, 0, 1, at);
        em.flush();
    }

    private void sent(AccountEntity owner, String email, String displayName, LocalDateTime at) {
        correspondentRepository.upsert(owner.getId(), email, displayName, 1, 0, at);
        em.flush();
    }

    private CorrespondentEntity reload(AccountEntity owner, String email) {
        em.clear();
        return correspondentRepository.findByAccountIdAndEmail(owner.getId(), email).orElseThrow();
    }

    private List<String> searchEmails(String q, int limit) {
        em.clear();
        return correspondentRepository
                .search(account.getId(), q + "%", "%" + q + "%", CorrespondentService.ROBOT_LOCAL_PARTS, limit).stream()
                .map(CorrespondentEntity::getEmail).toList();
    }

    @Nested
    @DisplayName("upsert")
    class Upsert {

        @Test
        @DisplayName("first sighting inserts the row with its counter set")
        void insertsOnFirstSighting() {
            received(account, "jana@example.com", "Jana Novak", RECENT);

            CorrespondentEntity row = reload(account, "jana@example.com");
            assertThat(row.getReceivedCount()).isEqualTo(1);
            assertThat(row.getSentCount()).isZero();
            assertThat(row.getDisplayName()).isEqualTo("Jana Novak");
            assertThat(row.getLastSeenAt()).isEqualTo(RECENT);
        }

        @Test
        @DisplayName("repeat sightings add to the counters instead of replacing them")
        void countersAccumulate() {
            received(account, "jana@example.com", "Jana Novak", OLD);
            received(account, "jana@example.com", "Jana Novak", OLD);
            sent(account, "jana@example.com", "Jana Novak", OLD);

            CorrespondentEntity row = reload(account, "jana@example.com");
            assertThat(row.getReceivedCount()).isEqualTo(2);
            assertThat(row.getSentCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("a later sighting without a display name keeps the name already learned")
        void nullDisplayNameDoesNotEraseTheStoredOne() {
            received(account, "jana@example.com", "Jana Novak", OLD);
            received(account, "jana@example.com", null, RECENT);

            assertThat(reload(account, "jana@example.com").getDisplayName()).isEqualTo("Jana Novak");
        }

        @Test
        @DisplayName("last_seen_at keeps the later date even when the older message is processed second")
        void lastSeenTakesTheMaximum() {
            received(account, "jana@example.com", "Jana Novak", RECENT);
            // The backfill walks by id, not by date, so an older message routinely
            // lands after a newer one. A plain assignment would stamp the row with
            // whichever message happened to come last.
            received(account, "jana@example.com", "Jana Novak", OLD);

            assertThat(reload(account, "jana@example.com").getLastSeenAt()).isEqualTo(RECENT);
        }

        @Test
        @DisplayName("the same address on two accounts stays two independent rows")
        void scopedPerAccount() {
            received(account, "jana@example.com", "Jana Novak", RECENT);
            received(otherAccount, "jana@example.com", "Jana Novak", RECENT);
            received(otherAccount, "jana@example.com", "Jana Novak", RECENT);

            assertThat(reload(account, "jana@example.com").getReceivedCount()).isEqualTo(1);
            assertThat(reload(otherAccount, "jana@example.com").getReceivedCount()).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("search")
    class Search {

        @Test
        @DisplayName("matches an address prefix and a display-name substring")
        void matchesBothHalves() {
            received(account, "jana@example.com", "Jana Novak", RECENT);
            received(account, "petr@example.com", "Petr Svoboda", RECENT);

            assertThat(searchEmails("jan", 10)).containsExactly("jana@example.com");
            // "novak" is the surname — inside the display name, not at its start,
            // which is why the name half is a substring match.
            assertThat(searchEmails("novak", 10)).containsExactly("jana@example.com");
        }

        @Test
        @DisplayName("addresses the user wrote to outrank ones that only wrote in")
        void writtenToRanksFirst() {
            received(account, "jana.a@example.com", "Jana A", RECENT);
            sent(account, "jana.b@example.com", "Jana B", OLD);

            // Despite being the older sighting, the address written TO comes first.
            assertThat(searchEmails("jana", 10)).containsExactly("jana.b@example.com", "jana.a@example.com");
        }

        @Test
        @DisplayName("within one tier the more recent correspondent beats the more frequent one")
        void recencyBeatsFrequency() {
            // Both are received-only and both match on the address prefix, so the
            // first two ORDER BY terms tie and last_seen_at is what decides.
            received(account, "a.old@example.com", "Old Friend", OLD);
            received(account, "a.old@example.com", "Old Friend", OLD);
            received(account, "a.old@example.com", "Old Friend", OLD);
            received(account, "a.new@example.com", "New Contact", RECENT);

            assertThat(searchEmails("a.", 10)).containsExactly("a.new@example.com", "a.old@example.com");
        }

        @Test
        @DisplayName("an address-prefix match outranks a name-only match")
        void addressPrefixBeatsNameMatch() {
            // Same tier (both received-only) and the name match is the more recent
            // sighting, so only the prefix term can put the other one first.
            received(account, "novakova@example.com", "Jana N", OLD);
            received(account, "j.dvorak@example.com", "Novak Dvorak", RECENT);

            assertThat(searchEmails("novak", 10)).containsExactly("novakova@example.com", "j.dvorak@example.com");
        }

        @Test
        @DisplayName("no-reply is hidden until the user writes to it")
        void robotFilterAppliesOnlyToNeverWrittenAddresses() {
            received(account, "no-reply@example.com", "Newsletter", RECENT);
            received(account, "noreply@shop.example.com", "Shop", RECENT);

            assertThat(searchEmails("no", 10)).isEmpty();

            // Replying to a robot address makes it a real correspondent.
            sent(account, "no-reply@example.com", "Newsletter", RECENT);
            assertThat(searchEmails("no", 10)).containsExactly("no-reply@example.com");
        }

        @Test
        @DisplayName("a query containing LIKE wildcards matches literally, not everything")
        void wildcardsInTheQueryAreEscaped() {
            received(account, "jana@example.com", "Jana Novak", RECENT);
            received(account, "a%b@example.com", "Percent Person", RECENT);

            String escaped = CorrespondentService.escapeLikeWildcards("a%");
            List<String> hits = correspondentRepository.search(account.getId(), escaped + "%", "%" + escaped + "%",
                    CorrespondentService.ROBOT_LOCAL_PARTS, 10).stream().map(CorrespondentEntity::getEmail).toList();

            // Without ESCAPE the pattern "a%%" would also drag in jana@example.com.
            assertThat(hits).containsExactly("a%b@example.com");
        }

        @Test
        @DisplayName("results stop at the limit")
        void respectsLimit() {
            received(account, "jana@example.com", "Jana Novak", RECENT);
            received(account, "jan@example.com", "Jan Dvorak", RECENT);
            received(account, "jana.b@example.com", "Jana B", RECENT);

            assertThat(searchEmails("jan", 2)).hasSize(2);
        }

        @Test
        @DisplayName("another account's correspondents are never returned")
        void scopedPerAccount() {
            received(otherAccount, "foreign@example.com", "Foreign", RECENT);

            assertThat(searchEmails("foreign", 10)).isEmpty();
        }
    }
}
