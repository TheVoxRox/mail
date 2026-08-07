package org.voxrox.mailbackend.feature.contact.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
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
import org.voxrox.mailbackend.feature.account.service.AccountService;
import org.voxrox.mailbackend.feature.contact.EmailLabel;
import org.voxrox.mailbackend.feature.contact.entity.ContactEmailEntity;
import org.voxrox.mailbackend.feature.contact.entity.ContactEntity;
import org.voxrox.mailbackend.feature.contact.entity.ContactLabelEntity;
import org.voxrox.mailbackend.feature.contact.mapper.ContactMapper;
import org.voxrox.mailbackend.feature.contact.service.ContactLabelService;
import org.voxrox.mailbackend.feature.contact.service.ContactService;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * Integration tests for the repository layer against a real SQLite + Flyway
 * migration V5. They verify constructs that mock-based unit tests cannot catch:
 * - UNIQUE (contact_id, email) constraint in contact_emails - FK CASCADE on
 * account deletion (contacts -> contact_emails) - subquery search over
 * contact_emails, name, surname - case-insensitivity - scoped
 * findByIdAndAccountId (cross-account isolation) - findByAccountIdAndAnyEmail
 * (cross-contact duplicate check) - NULLS LAST ordering on surname / name.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("it")
@Sql(statements = {"DELETE FROM contact_label_links", "DELETE FROM contact_labels", "DELETE FROM contact_emails",
        "DELETE FROM contacts", "DELETE FROM accounts",
        "DELETE FROM mail_providers"}, executionPhase = ExecutionPhase.BEFORE_TEST_METHOD)
class ContactRepositoryIT {

    private static final Path DB_DIR = Path
            .of("target", "test-tmp", "ContactRepositoryIT", UUID.randomUUID().toString()).toAbsolutePath().normalize();

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
    private ContactRepository contactRepository;
    @Autowired
    private ContactLabelRepository labelRepository;
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

        account = newAccount("owner1@example.com", provider);
        otherAccount = newAccount("owner2@example.com", provider);
        account = accountRepository.save(account);
        otherAccount = accountRepository.save(otherAccount);
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

    private ContactEntity newContact(AccountEntity owner, String email, String name, String surname) {
        ContactEntity c = new ContactEntity();
        c.setAccount(owner);
        c.setName(name);
        c.setSurname(surname);
        LocalDateTime now = LocalDateTime.now();
        c.setCreatedAt(now);
        c.setUpdatedAt(now);
        if (email != null) {
            ContactEmailEntity em = new ContactEmailEntity();
            em.setEmail(email);
            em.setLabel(null);
            em.setPrimary(true);
            em.setContact(c);
            c.getEmails().add(em);
        }
        return c;
    }

    /**
     * Persists a label the way the service does — name_key is the case-folded name,
     * because that is the column carrying the uniqueness constraint.
     */
    private ContactLabelEntity newLabel(AccountEntity owner, String name) {
        ContactLabelEntity l = new ContactLabelEntity();
        l.setAccount(owner);
        l.setName(name);
        l.setNameKey(name.toLowerCase(Locale.ROOT));
        l.setCreatedAt(LocalDateTime.now());
        return labelRepository.saveAndFlush(l);
    }

    private String primaryEmail(ContactEntity c) {
        return c.getEmails().stream().filter(ContactEmailEntity::isPrimary).findFirst()
                .map(ContactEmailEntity::getEmail).orElse(null);
    }

    @Nested
    @DisplayName("UNIQUE (contact_id, email)")
    class UniqueConstraint {

        @Test
        @DisplayName("A second insert of the same email on the same contact -> constraint violation")
        void duplicateEmailOnSameContact() {
            ContactEntity c = newContact(account, "first@x.cz", "A", "A");
            ContactEmailEntity second = new ContactEmailEntity();
            second.setEmail("first@x.cz");
            second.setPrimary(false);
            second.setContact(c);
            c.getEmails().add(second);

            assertThatThrownBy(() -> contactRepository.saveAndFlush(c)).isInstanceOf(DataAccessException.class)
                    .hasMessageContaining("UNIQUE");
        }

        @Test
        @DisplayName("Same email on two different contacts of the same account passes (cross-contact uniqueness is app-level)")
        void sameEmailOnDifferentContactsAllowed() {
            contactRepository.saveAndFlush(newContact(account, "shared@x.cz", "A", null));
            contactRepository.saveAndFlush(newContact(account, "shared@x.cz", "B", null));

            assertThat(contactRepository.findAll()).hasSize(2);
        }

        @Test
        @DisplayName("Same email on contacts of different accounts passes")
        void sameEmailOtherAccountAllowed() {
            contactRepository.saveAndFlush(newContact(account, "shared@x.cz", "A", null));
            contactRepository.saveAndFlush(newContact(otherAccount, "shared@x.cz", "B", null));

            assertThat(contactRepository.findAll()).hasSize(2);
        }
    }

    @Test
    @DisplayName("Deleting an account cascades to its contacts and their emails")
    void cascadeOnAccountDelete() {
        contactRepository.saveAndFlush(newContact(account, "a@x.cz", null, null));
        contactRepository.saveAndFlush(newContact(account, "b@x.cz", null, null));
        contactRepository.saveAndFlush(newContact(otherAccount, "c@x.cz", null, null));

        accountRepository.delete(account);
        accountRepository.flush();
        em.clear();

        List<ContactEntity> remaining = contactRepository.findAll();
        assertThat(remaining).hasSize(1);
        assertThat(primaryEmail(remaining.get(0))).isEqualTo("c@x.cz");
    }

    @Test
    @DisplayName("findByAccountIdAndAnyEmail — finds the contact by any email")
    void findByAnyEmail() {
        ContactEntity c = newContact(account, "primary@x.cz", "Alice", null);
        ContactEmailEntity second = new ContactEmailEntity();
        second.setEmail("secondary@x.cz");
        second.setLabel(EmailLabel.HOME);
        second.setPrimary(false);
        second.setContact(c);
        c.getEmails().add(second);
        contactRepository.saveAndFlush(c);
        em.clear();

        List<ContactEntity> byPrimary = contactRepository.findByAccountIdAndAnyEmail(account.getId(), "primary@x.cz");
        List<ContactEntity> bySecondary = contactRepository.findByAccountIdAndAnyEmail(account.getId(),
                "secondary@x.cz");
        List<ContactEntity> byOtherAccount = contactRepository.findByAccountIdAndAnyEmail(otherAccount.getId(),
                "primary@x.cz");

        assertThat(byPrimary).hasSize(1);
        assertThat(bySecondary).hasSize(1);
        assertThat(bySecondary.get(0).getId()).isEqualTo(byPrimary.get(0).getId());
        assertThat(byOtherAccount).isEmpty();
    }

    @Nested
    @DisplayName("Contact counts")
    class Counts {

        private void addEmail(ContactEntity c, String email, EmailLabel label) {
            ContactEmailEntity e = new ContactEmailEntity();
            e.setEmail(email);
            e.setLabel(label);
            e.setPrimary(false);
            e.setContact(c);
            c.getEmails().add(e);
        }

        @Test
        @DisplayName("countByAccountId counts only the account's own contacts")
        void totalIsAccountScoped() {
            contactRepository.saveAndFlush(newContact(account, "a@x.cz", null, null));
            contactRepository.saveAndFlush(newContact(account, "b@x.cz", null, null));
            contactRepository.saveAndFlush(newContact(otherAccount, "c@x.cz", null, null));

            assertThat(contactRepository.countByAccountId(account.getId())).isEqualTo(2);
            assertThat(contactRepository.countByAccountId(otherAccount.getId())).isEqualTo(1);
        }

        @Test
        @DisplayName("label counts are per contact, ignore unused labels and foreign accounts")
        void labelCountsPerContact() {
            ContactLabelEntity family = newLabel(account, "Rodina");
            ContactLabelEntity clients = newLabel(account, "Klienti");
            // Nobody carries this one — it must be absent from the aggregate, and the
            // service is what turns that absence into a zero badge.
            newLabel(account, "Škola");
            ContactLabelEntity foreignLabel = newLabel(otherAccount, "Rodina");

            ContactEntity both = newContact(account, "a@x.cz", "A", null);
            both.getLabels().add(family);
            both.getLabels().add(clients);
            addEmail(both, "a2@x.cz", EmailLabel.WORK);
            contactRepository.saveAndFlush(both);

            ContactEntity onlyFamily = newContact(account, "b@x.cz", "B", null);
            onlyFamily.getLabels().add(family);
            contactRepository.saveAndFlush(onlyFamily);

            contactRepository.saveAndFlush(newContact(account, "c@x.cz", "C", null));

            ContactEntity foreign = newContact(otherAccount, "f@x.cz", "D", null);
            foreign.getLabels().add(foreignLabel);
            contactRepository.saveAndFlush(foreign);
            em.clear();

            List<ContactLabelCount> counts = contactRepository.countByAccountIdGroupedByLabel(account.getId());

            assertThat(counts).containsExactlyInAnyOrder(new ContactLabelCount(family.getId(), 2L),
                    new ContactLabelCount(clients.getId(), 1L));
        }
    }

    @Test
    @DisplayName("setPrimaryEmail promoting a lower-id email must not violate the one-primary index")
    void setPrimaryEmailPromotesLowerId() {
        // Two emails; the CURRENT primary is the higher-id one. 'low' is inserted
        // first so it gets the lower AUTOINCREMENT id. A single-pass flag swap would
        // flush the lower-id promote (->primary) before the higher-id demote
        // (->non-primary), producing a transient two-primaries state that the partial
        // unique index ux_contact_emails_contact_primary rejects.
        ContactEntity c = new ContactEntity();
        c.setAccount(account);
        LocalDateTime now = LocalDateTime.now();
        c.setCreatedAt(now);
        c.setUpdatedAt(now);
        ContactEmailEntity low = new ContactEmailEntity();
        low.setEmail("low@x.cz");
        low.setPrimary(false);
        low.setContact(c);
        ContactEmailEntity high = new ContactEmailEntity();
        high.setEmail("high@x.cz");
        high.setPrimary(true);
        high.setContact(c);
        c.getEmails().add(low);
        c.getEmails().add(high);
        ContactEntity saved = contactRepository.saveAndFlush(c);
        Long contactId = saved.getId();
        Long lowId = saved.getEmails().stream().filter(e -> "low@x.cz".equals(e.getEmail())).findFirst().orElseThrow()
                .getId();
        em.clear();

        AccountService accountService = mock(AccountService.class);
        when(accountService.getAccountOrThrow(account.getId())).thenReturn(account);
        ContactLabelService labelService = new ContactLabelService(labelRepository, contactRepository, accountService);
        ContactService service = new ContactService(contactRepository, labelRepository, labelService, accountService,
                new ContactMapper());

        // Called directly (no @Transactional proxy), so setPrimaryEmail joins the
        // test's transaction and its final change flushes here rather than on commit.
        // The flush is where a single-pass swap would trip the partial unique index.
        assertThatCode(() -> {
            service.setPrimaryEmail(account.getId(), contactId, lowId);
            em.flush();
        }).doesNotThrowAnyException();

        em.clear();
        assertThat(primaryEmail(contactRepository.findById(contactId).orElseThrow())).isEqualTo("low@x.cz");
    }

    @Test
    @DisplayName("findByAccountIdAndAnyEmailIn — finds collisions for multiple emails in a single query")
    void findByAnyEmailIn() {
        contactRepository.saveAndFlush(newContact(account, "first@x.cz", "First", null));
        contactRepository.saveAndFlush(newContact(account, "second@x.cz", "Second", null));
        contactRepository.saveAndFlush(newContact(otherAccount, "third@x.cz", "Other", null));
        em.clear();

        List<ContactEntity> found = contactRepository.findByAccountIdAndAnyEmailIn(account.getId(),
                List.of("missing@x.cz", "second@x.cz", "third@x.cz"));

        assertThat(found).hasSize(1);
        assertThat(primaryEmail(found.get(0))).isEqualTo("second@x.cz");
    }

    @Nested
    @DisplayName("searchByAccountId")
    class Search {

        @BeforeEach
        void seed() {
            ContactEntity alice = newContact(account, "alice@x.cz", "Alice", "Liddell");
            ContactEmailEntity aliceWork = new ContactEmailEntity();
            aliceWork.setEmail("alice.work@x.cz");
            aliceWork.setLabel(EmailLabel.WORK);
            aliceWork.setPrimary(false);
            aliceWork.setContact(alice);
            alice.getEmails().add(aliceWork);
            contactRepository.saveAndFlush(alice);

            contactRepository.saveAndFlush(newContact(account, "bob@x.cz", "Bob", "Dylan"));
            contactRepository.saveAndFlush(newContact(account, "c@x.cz", null, null));
            // Foreign account — must not leak into the results:
            contactRepository.saveAndFlush(newContact(otherAccount, "alice2@x.cz", "Alice", "Other"));
        }

        @Test
        @DisplayName("Match via the primary email")
        void matchByPrimaryEmail() {
            Page<ContactEntity> p = contactRepository.searchByAccountId(account.getId(), "%bob%", null,
                    PageRequest.of(0, 10));
            assertThat(p.getContent()).hasSize(1);
            assertThat(primaryEmail(p.getContent().get(0))).isEqualTo("bob@x.cz");
        }

        @Test
        @DisplayName("Match via a contact's secondary email")
        void matchBySecondaryEmail() {
            Page<ContactEntity> p = contactRepository.searchByAccountId(account.getId(), "%alice.work%", null,
                    PageRequest.of(0, 10));
            assertThat(p.getContent()).hasSize(1);
            assertThat(primaryEmail(p.getContent().get(0))).isEqualTo("alice@x.cz");
        }

        @Test
        @DisplayName("Match via name (case-insensitive) — each contact only once (no duplicates)")
        void matchByNameNoDuplicates() {
            Page<ContactEntity> p = contactRepository.searchByAccountId(account.getId(), "%alice%", null,
                    PageRequest.of(0, 10));
            // Alice has two emails — must be returned only once
            assertThat(p.getContent()).hasSize(1);
            assertThat(primaryEmail(p.getContent().get(0))).isEqualTo("alice@x.cz");
        }

        @Test
        @DisplayName("Match via surname")
        void matchBySurname() {
            Page<ContactEntity> p = contactRepository.searchByAccountId(account.getId(), "%dylan%", null,
                    PageRequest.of(0, 10));
            assertThat(p.getContent()).hasSize(1);
            assertThat(p.getContent().get(0).getSurname()).isEqualTo("Dylan");
        }

        @Test
        @DisplayName("Scope by accountId — does not return foreign contacts")
        void scopedByAccount() {
            Page<ContactEntity> p = contactRepository.searchByAccountId(account.getId(), "%alice%", null,
                    PageRequest.of(0, 10));
            assertThat(p.getContent()).noneMatch(c -> "alice2@x.cz".equals(primaryEmail(c)));
        }
    }

    @Test
    @DisplayName("findByIdAndAccountId — cross-account returns empty")
    void findByIdScopedByAccount() {
        ContactEntity ownersContact = contactRepository.saveAndFlush(newContact(account, "owner@x.cz", null, null));

        Optional<ContactEntity> crossAccount = contactRepository.findByIdAndAccountId(ownersContact.getId(),
                otherAccount.getId());
        Optional<ContactEntity> sameAccount = contactRepository.findByIdAndAccountId(ownersContact.getId(),
                account.getId());

        assertThat(crossAccount).isEmpty();
        assertThat(sameAccount).isPresent();
    }

    @Test
    @DisplayName("Ordering surname/name with NULLS LAST — null surnames fall to the end")
    void sortNullsLast() {
        contactRepository.saveAndFlush(newContact(account, "x@x.cz", "Zoe", null));
        contactRepository.saveAndFlush(newContact(account, "a@x.cz", "Bob", "Alpha"));
        contactRepository.saveAndFlush(newContact(account, "b@x.cz", "Alice", "Beta"));

        Sort sort = Sort.by(Sort.Order.asc("surname").nullsLast(), Sort.Order.asc("name").nullsLast(),
                Sort.Order.asc("id"));
        Page<ContactEntity> p = contactRepository.findByAccountId(account.getId(), null, PageRequest.of(0, 10, sort));

        assertThat(p.getContent()).extracting(ContactRepositoryIT.this::primaryEmail).containsExactly("a@x.cz",
                "b@x.cz", "x@x.cz");
    }

    @Test
    @DisplayName("findByAccountId — paginates in SQL, not in memory (no HHH90003004 collection fetch-join)")
    void paginationDoesNotApplyLimitInMemory() {
        // More contacts than the page size, each with several emails: a fetch-join
        // of the emails collection together with Pageable would force Hibernate to
        // page the result in memory and log HHH90003004. Batch fetching keeps the
        // LIMIT in SQL while still loading the emails.
        for (int i = 0; i < 5; i++) {
            ContactEntity c = newContact(account, "primary" + i + "@x.cz", "Name" + i, "Surname" + i);
            ContactEmailEntity work = new ContactEmailEntity();
            work.setEmail("work" + i + "@x.cz");
            work.setLabel(EmailLabel.WORK);
            work.setPrimary(false);
            work.setContact(c);
            c.getEmails().add(work);
            contactRepository.saveAndFlush(c);
        }
        em.clear();

        Logger hibernate = (Logger) LoggerFactory.getLogger("org.hibernate");
        Level previousLevel = hibernate.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        hibernate.setLevel(Level.WARN);
        hibernate.addAppender(appender);
        try {
            Page<ContactEntity> page = contactRepository.findByAccountId(account.getId(), null,
                    PageRequest.of(0, 2, Sort.by("id")));

            // SQL-level pagination: the page holds exactly the requested slice while
            // the total reflects every matching row.
            assertThat(page.getContent()).hasSize(2);
            assertThat(page.getTotalElements()).isEqualTo(5);
            // Emails are still loaded (batch fetch), not dropped by removing the graph.
            assertThat(page.getContent()).allSatisfy(c -> assertThat(c.getEmails()).hasSize(2));
        } finally {
            hibernate.detachAppender(appender);
            hibernate.setLevel(previousLevel);
        }

        assertThat(appender.list).as("Hibernate must page the contact listing in SQL, not in memory (HHH90003004)")
                .noneMatch(e -> e.getFormattedMessage().contains("applying in memory")
                        || e.getFormattedMessage().contains("HHH90003004"));
    }

    @Nested
    @DisplayName("findByAccountId/searchByAccountId — filtering by contact label")
    class LabelFilter {

        private ContactLabelEntity family;
        private ContactLabelEntity clients;

        @BeforeEach
        void seed() {
            family = newLabel(account, "Rodina");
            clients = newLabel(account, "Klienti");

            ContactEntity familyOnly = newContact(account, "family@x.cz", "Family", "Person");
            familyOnly.getLabels().add(family);
            contactRepository.saveAndFlush(familyOnly);

            ContactEntity clientOnly = newContact(account, "client@x.cz", "Client", "Person");
            clientOnly.getLabels().add(clients);
            contactRepository.saveAndFlush(clientOnly);

            // No label — must not pass the label filter.
            contactRepository.saveAndFlush(newContact(account, "noop@x.cz", "Noop", "Person"));

            ContactEntity bothLabels = newContact(account, "both@x.cz", "Both", "Person");
            bothLabels.getLabels().add(family);
            bothLabels.getLabels().add(clients);
            contactRepository.saveAndFlush(bothLabels);
        }

        @Test
        @DisplayName("findByAccountId(labelId) returns every contact carrying that label, each once")
        void listByLabel() {
            Page<ContactEntity> p = contactRepository.findByAccountId(account.getId(), family.getId(),
                    PageRequest.of(0, 10));
            assertThat(p.getContent()).extracting(ContactRepositoryIT.this::primaryEmail)
                    .containsExactlyInAnyOrder("family@x.cz", "both@x.cz");
        }

        @Test
        @DisplayName("findByAccountId(labelId) of another label returns its own contacts")
        void listByOtherLabel() {
            Page<ContactEntity> p = contactRepository.findByAccountId(account.getId(), clients.getId(),
                    PageRequest.of(0, 10));
            assertThat(p.getContent()).extracting(ContactRepositoryIT.this::primaryEmail)
                    .containsExactlyInAnyOrder("client@x.cz", "both@x.cz");
        }

        @Test
        @DisplayName("findByAccountId(labelId=null) returns all contacts (filter inactive)")
        void listAllWhenLabelNull() {
            Page<ContactEntity> p = contactRepository.findByAccountId(account.getId(), (Long) null,
                    PageRequest.of(0, 10));
            assertThat(p.getContent()).hasSize(4);
        }

        @Test
        @DisplayName("searchByAccountId kombinuje q-filtr s label-filtrem")
        void searchWithLabel() {
            Page<ContactEntity> p = contactRepository.searchByAccountId(account.getId(), "%both%", family.getId(),
                    PageRequest.of(0, 10));
            assertThat(p.getContent()).extracting(ContactRepositoryIT.this::primaryEmail).containsExactly("both@x.cz");
        }

        @Test
        @DisplayName("A label of another account never matches, even with the same name")
        void foreignLabelDoesNotLeak() {
            ContactLabelEntity foreignFamily = newLabel(otherAccount, "Rodina");
            ContactEntity foreign = newContact(otherAccount, "foreign@x.cz", "Foreign", "Person");
            foreign.getLabels().add(foreignFamily);
            contactRepository.saveAndFlush(foreign);
            em.clear();

            Page<ContactEntity> p = contactRepository.findByAccountId(account.getId(), foreignFamily.getId(),
                    PageRequest.of(0, 10));
            assertThat(p.getContent()).isEmpty();
        }
    }

    @Nested
    @DisplayName("contact_labels constraints")
    class LabelConstraints {

        @Test
        @DisplayName("Two labels with the same name_key on one account -> constraint violation")
        void duplicateNameKeyRejected() {
            newLabel(account, "Rodina");
            // Hibernate wraps the SQLite unique violation as JpaSystemException, so
            // assert on the common DataAccessException base like the e-mail unique
            // test above, and pin the constraint by name in the message.
            assertThatThrownBy(() -> newLabel(account, "Rodina")).isInstanceOf(DataAccessException.class)
                    .hasMessageContaining("contact_labels.name_key");
        }

        @Test
        @DisplayName("The same name_key on two accounts is fine — labels are account-scoped")
        void sameNameOnDifferentAccounts() {
            newLabel(account, "Rodina");
            assertThatCode(() -> newLabel(otherAccount, "Rodina")).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("Deleting a contact drops its assignments but keeps the label")
        void deletingContactKeepsLabel() {
            ContactLabelEntity family = newLabel(account, "Rodina");
            ContactEntity c = newContact(account, "a@x.cz", "A", null);
            c.getLabels().add(family);
            ContactEntity saved = contactRepository.saveAndFlush(c);
            em.clear();

            contactRepository.deleteById(saved.getId());
            contactRepository.flush();
            em.clear();

            assertThat(labelRepository.findByIdAndAccountId(family.getId(), account.getId())).isPresent();
            assertThat(contactRepository.findByAccountIdAndLabelId(account.getId(), family.getId())).isEmpty();
        }
    }
}
