package org.voxrox.mailbackend.feature.contact.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
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
import org.voxrox.mailbackend.feature.contact.AutocompleteSource;
import org.voxrox.mailbackend.feature.contact.EmailLabel;
import org.voxrox.mailbackend.feature.contact.dto.ContactAutocompleteResponse;
import org.voxrox.mailbackend.feature.contact.dto.ContactEmailRequest;
import org.voxrox.mailbackend.feature.contact.dto.ContactEmailResponse;
import org.voxrox.mailbackend.feature.contact.dto.ContactLabelResponse;
import org.voxrox.mailbackend.feature.contact.dto.ContactResponse;
import org.voxrox.mailbackend.feature.contact.dto.ContactUpdateRequest;
import org.voxrox.mailbackend.feature.contact.entity.ContactEmailEntity;
import org.voxrox.mailbackend.feature.contact.entity.ContactEntity;
import org.voxrox.mailbackend.feature.contact.entity.ContactLabelEntity;
import org.voxrox.mailbackend.feature.contact.mapper.ContactMapper;
import org.voxrox.mailbackend.feature.contact.service.ContactAutocompleteService;
import org.voxrox.mailbackend.feature.contact.service.ContactLabelService;
import org.voxrox.mailbackend.feature.contact.service.ContactService;
import org.voxrox.mailbackend.feature.contact.service.CorrespondentService;
import org.voxrox.mailbackend.util.TextFold;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * Integration tests for the repository layer against a real SQLite built by the
 * real Flyway migration. They verify constructs that mock-based unit tests
 * cannot catch: the UNIQUE (contact_id, email) constraint, the global
 * contact_labels.name_key uniqueness, that deleting a mail account leaves the
 * address book alone, the subquery search over contact_emails/name/surname,
 * case-insensitivity, the cross-contact duplicate check and NULLS LAST ordering
 * on surname / name.
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
    private CorrespondentRepository correspondentRepository;
    @Autowired
    private EntityManager em;

    private AccountEntity account;

    @BeforeEach
    void setUp() {
        MailProviderEntity provider = new MailProviderEntity();
        provider.setName("TestProvider");
        provider.setDomains(",example.com,");
        provider.setImapConfig(new MailServerConfig("imap.example.com", 993, true));
        provider.setSmtpConfig(new MailServerConfig("smtp.example.com", 465, true));
        provider = providerRepository.saveAndFlush(provider);

        account = newAccount("mailbox@example.com", provider);
        account = accountRepository.save(account);
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

    private ContactEntity newContact(String email, String name, String surname) {
        ContactEntity c = new ContactEntity();
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
    private ContactLabelEntity newLabel(String name) {
        ContactLabelEntity l = new ContactLabelEntity();
        l.setName(name);
        l.setNameKey(name.toLowerCase(Locale.ROOT));
        l.setCreatedAt(LocalDateTime.now());
        return labelRepository.saveAndFlush(l);
    }

    private ContactService newService() {
        ContactLabelService labelService = new ContactLabelService(labelRepository, contactRepository);
        return new ContactService(contactRepository, labelRepository, labelService, new ContactMapper());
    }

    private static String primaryEmailOf(ContactResponse c) {
        return c.emails().stream().filter(ContactEmailResponse::primary).findFirst().map(ContactEmailResponse::email)
                .orElse(null);
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
            ContactEntity c = newContact("first@x.cz", "A", "A");
            ContactEmailEntity second = new ContactEmailEntity();
            second.setEmail("first@x.cz");
            second.setPrimary(false);
            second.setContact(c);
            c.getEmails().add(second);

            assertThatThrownBy(() -> contactRepository.saveAndFlush(c)).isInstanceOf(DataAccessException.class)
                    .hasMessageContaining("UNIQUE");
        }

        @Test
        @DisplayName("Same email on two different contacts passes (cross-contact uniqueness is app-level)")
        void sameEmailOnDifferentContactsAllowed() {
            contactRepository.saveAndFlush(newContact("shared@x.cz", "A", null));
            contactRepository.saveAndFlush(newContact("shared@x.cz", "B", null));

            assertThat(contactRepository.findAll()).hasSize(2);
        }
    }

    /**
     * The inverse of what this used to assert. The address book deliberately has no
     * account column any more, so removing a mailbox must leave every contact
     * standing — messages come back from IMAP on the next sync, hand-curated
     * contacts come back from nowhere.
     */
    @Test
    @DisplayName("Deleting an account leaves the address book untouched")
    void accountDeleteKeepsContacts() {
        contactRepository.saveAndFlush(newContact("a@x.cz", null, null));
        contactRepository.saveAndFlush(newContact("b@x.cz", null, null));
        contactRepository.saveAndFlush(newContact("c@x.cz", null, null));

        accountRepository.delete(account);
        accountRepository.flush();
        em.clear();

        assertThat(contactRepository.findAll()).extracting(this::primaryEmail).containsExactlyInAnyOrder("a@x.cz",
                "b@x.cz", "c@x.cz");
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
        @DisplayName("count returns every contact in the address book")
        void totalCountsEveryContact() {
            contactRepository.saveAndFlush(newContact("a@x.cz", null, null));
            contactRepository.saveAndFlush(newContact("b@x.cz", null, null));
            contactRepository.saveAndFlush(newContact("c@x.cz", null, null));

            assertThat(contactRepository.count()).isEqualTo(3);
        }

        @Test
        @DisplayName("label counts are per contact and ignore unused labels")
        void labelCountsPerContact() {
            ContactLabelEntity family = newLabel("Family");
            ContactLabelEntity clients = newLabel("Clients");
            // Nobody carries this one — it must be absent from the aggregate, and the
            // service is what turns that absence into a zero badge.
            newLabel("Archive");

            ContactEntity both = newContact("a@x.cz", "A", null);
            both.getLabels().add(family);
            both.getLabels().add(clients);
            addEmail(both, "a2@x.cz", EmailLabel.WORK);
            contactRepository.saveAndFlush(both);

            ContactEntity onlyFamily = newContact("b@x.cz", "B", null);
            onlyFamily.getLabels().add(family);
            contactRepository.saveAndFlush(onlyFamily);

            contactRepository.saveAndFlush(newContact("c@x.cz", "C", null));

            em.clear();

            List<ContactLabelCount> counts = contactRepository.countGroupedByLabel();

            assertThat(counts).containsExactlyInAnyOrder(new ContactLabelCount(family.getId(), 2L),
                    new ContactLabelCount(clients.getId(), 1L));
        }
    }

    @Test
    @DisplayName("updateContact reordering the primary must not violate the one-primary index")
    void updateContactReordersPrimary() {
        // PUT carries primacy as the order of the addresses, so moving the primary
        // means the incoming list starts with what used to be the second address.
        // replaceEmails clears the collection and adds fresh rows; if the inserts
        // reach the database before the orphan deletes, the contact briefly holds
        // two is_primary=1 rows and ux_contact_emails_contact_primary rejects it.
        ContactEntity c = new ContactEntity();
        LocalDateTime now = LocalDateTime.now();
        c.setCreatedAt(now);
        c.setUpdatedAt(now);
        ContactEmailEntity first = new ContactEmailEntity();
        first.setEmail("first@x.cz");
        first.setPrimary(true);
        first.setContact(c);
        ContactEmailEntity second = new ContactEmailEntity();
        second.setEmail("second@x.cz");
        second.setPrimary(false);
        second.setContact(c);
        c.getEmails().add(first);
        c.getEmails().add(second);
        Long contactId = contactRepository.saveAndFlush(c).getId();
        em.clear();

        ContactLabelService labelService = new ContactLabelService(labelRepository, contactRepository);
        ContactService service = new ContactService(contactRepository, labelRepository, labelService,
                new ContactMapper());

        ContactUpdateRequest request = new ContactUpdateRequest(
                List.of(new ContactEmailRequest("second@x.cz", EmailLabel.HOME),
                        new ContactEmailRequest("first@x.cz", EmailLabel.WORK)),
                List.of(), "Jana", "Novak", null);

        // Called directly (no @Transactional proxy), so the write joins the test's
        // transaction and the flush below is where the index would trip.
        assertThatCode(() -> {
            service.updateContact(contactId, request);
            em.flush();
        }).doesNotThrowAnyException();

        em.clear();
        assertThat(primaryEmail(contactRepository.findById(contactId).orElseThrow())).isEqualTo("second@x.cz");
    }

    @Test
    @DisplayName("updateContact keeping the order must not violate the unique-email index either")
    void updateContactKeepsOrder() {
        ContactEntity c = new ContactEntity();
        LocalDateTime now = LocalDateTime.now();
        c.setCreatedAt(now);
        c.setUpdatedAt(now);
        ContactEmailEntity only = new ContactEmailEntity();
        only.setEmail("only@x.cz");
        only.setPrimary(true);
        only.setContact(c);
        c.getEmails().add(only);
        Long contactId = contactRepository.saveAndFlush(c).getId();
        em.clear();

        ContactLabelService labelService = new ContactLabelService(labelRepository, contactRepository);
        ContactService service = new ContactService(contactRepository, labelRepository, labelService,
                new ContactMapper());

        ContactUpdateRequest request = new ContactUpdateRequest(
                List.of(new ContactEmailRequest("only@x.cz", EmailLabel.WORK)), List.of(), "Jana", "Novak", null);

        assertThatCode(() -> {
            service.updateContact(contactId, request);
            em.flush();
        }).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("findByAnyEmailIn — finds collisions for multiple emails in a single query, across the whole book")
    void findByAnyEmailIn() {
        contactRepository.saveAndFlush(newContact("first@x.cz", "First", null));
        contactRepository.saveAndFlush(newContact("second@x.cz", "Second", null));
        contactRepository.saveAndFlush(newContact("third@x.cz", "Other", null));
        em.clear();

        List<ContactEntity> found = contactRepository
                .findByAnyEmailIn(List.of("missing@x.cz", "second@x.cz", "third@x.cz"));

        assertThat(found).extracting(this::primaryEmail).containsExactlyInAnyOrder("second@x.cz", "third@x.cz");
    }

    @Nested
    @DisplayName("search")
    class Search {

        @BeforeEach
        void seed() {
            ContactEntity alice = newContact("alice@x.cz", "Alice", "Liddell");
            ContactEmailEntity aliceWork = new ContactEmailEntity();
            aliceWork.setEmail("alice.work@x.cz");
            aliceWork.setLabel(EmailLabel.WORK);
            aliceWork.setPrimary(false);
            aliceWork.setContact(alice);
            alice.getEmails().add(aliceWork);
            contactRepository.saveAndFlush(alice);

            contactRepository.saveAndFlush(newContact("bob@x.cz", "Bob", "Dylan"));
            contactRepository.saveAndFlush(newContact("c@x.cz", null, null));
            // A second contact sharing the given name — the name match must return both.
            contactRepository.saveAndFlush(newContact("alice2@x.cz", "Alice", "Other"));
        }

        @Test
        @DisplayName("Match via the primary email")
        void matchByPrimaryEmail() {
            Page<ContactEntity> p = contactRepository.search("%bob%", null, null, null, null, PageRequest.of(0, 10));
            assertThat(p.getContent()).hasSize(1);
            assertThat(primaryEmail(p.getContent().get(0))).isEqualTo("bob@x.cz");
        }

        @Test
        @DisplayName("Match via a contact's secondary email")
        void matchBySecondaryEmail() {
            Page<ContactEntity> p = contactRepository.search("%alice.work%", null, null, null, null,
                    PageRequest.of(0, 10));
            assertThat(p.getContent()).hasSize(1);
            assertThat(primaryEmail(p.getContent().get(0))).isEqualTo("alice@x.cz");
        }

        @Test
        @DisplayName("Match via name (case-insensitive) — each contact only once (no duplicates)")
        void matchByNameNoDuplicates() {
            Page<ContactEntity> p = contactRepository.search("%alice%", null, null, null, null, PageRequest.of(0, 10));
            // The first Alice has two emails — the join must not return her twice.
            assertThat(p.getContent()).extracting(ContactRepositoryIT.this::primaryEmail)
                    .containsExactlyInAnyOrder("alice@x.cz", "alice2@x.cz");
        }

        @Test
        @DisplayName("Match via surname")
        void matchBySurname() {
            Page<ContactEntity> p = contactRepository.search("%dylan%", null, null, null, null, PageRequest.of(0, 10));
            assertThat(p.getContent()).hasSize(1);
            assertThat(p.getContent().get(0).getSurname()).isEqualTo("Dylan");
        }
    }

    @Test
    @DisplayName("The listing's default order puts null surnames at the end")
    void sortNullsLast() {
        contactRepository.saveAndFlush(newContact("x@x.cz", "Zoe", null));
        contactRepository.saveAndFlush(newContact("a@x.cz", "Bob", "Alpha"));
        contactRepository.saveAndFlush(newContact("b@x.cz", "Alice", "Beta"));
        // A blank surname has no key either, so it orders with the missing one
        // rather than ahead of "Alpha".
        contactRepository.saveAndFlush(newContact("y@x.cz", "Yan", "  "));

        List<String> emails = newService().listContacts(0, 10, null, null).getContent().stream()
                .map(ContactRepositoryIT::primaryEmailOf).toList();

        assertThat(emails).containsExactly("a@x.cz", "b@x.cz", "y@x.cz", "x@x.cz");
    }

    @Nested
    @DisplayName("Czech names — search and order ignore case and diacritics")
    class CzechNames {

        private List<String> searchEmails(String q) {
            em.flush();
            em.clear();
            return newService().searchContacts(q, 0, 20, null, null).getContent().stream()
                    .map(ContactRepositoryIT::primaryEmailOf).toList();
        }

        private List<String> listSurnames(String sort) {
            em.flush();
            em.clear();
            return newService().listContacts(0, 20, sort, null).getContent().stream().map(ContactResponse::surname)
                    .toList();
        }

        @Test
        @DisplayName("a name starting with a capital diacritic is found by its letters, with the diacritics or without")
        void searchIgnoresCaseAndDiacritics() {
            contactRepository.saveAndFlush(newContact("jan@x.cz", "Jan", "Čermák"));
            contactRepository.saveAndFlush(newContact("eva@x.cz", "Eva", "Šťastná"));
            contactRepository.saveAndFlush(newContact("petr@x.cz", "Řehoř", "Novák"));

            for (String q : List.of("čer", "Čer", "cer", "ČERMÁK")) {
                assertThat(searchEmails(q)).as("query %s", q).containsExactly("jan@x.cz");
            }
            assertThat(searchEmails("stastna")).containsExactly("eva@x.cz");
            assertThat(searchEmails("novak")).containsExactly("petr@x.cz");
            // The given name is folded the same way as the surname.
            assertThat(searchEmails("rehor")).containsExactly("petr@x.cz");
        }

        @Test
        @DisplayName("the default order sorts diacritics and lower case among their letters, a missing surname last")
        void defaultOrderIgnoresCaseAndDiacritics() {
            int n = 0;
            for (String surname : List.of("Zeman", "Čermák", "novák", "Adámek", "Šťastný", "Říha", "Žák")) {
                contactRepository.saveAndFlush(newContact("c" + n++ + "@x.cz", "Jan", surname));
            }
            contactRepository.saveAndFlush(newContact("nobody@x.cz", "Petr", null));

            // "Žák" lands before "Zeman": its key is "zak". A folded order, not a
            // Czech collation, which would put every Ž after every Z (TextFold).
            assertThat(listSurnames(null)).containsExactly("Adámek", "Čermák", "novák", "Říha", "Šťastný", "Žák",
                    "Zeman", null);
        }

        @Test
        @DisplayName("two surnames that fold the same keep one stable order")
        void foldTiesBreakOnTheName() {
            contactRepository.saveAndFlush(newContact("c1@x.cz", "Jan", "novák"));
            contactRepository.saveAndFlush(newContact("c2@x.cz", "Jan", "Novák"));
            contactRepository.saveAndFlush(newContact("c3@x.cz", "Jan", "Novak"));

            assertThat(listSurnames(null)).containsExactly("Novak", "Novák", "novák");
        }

        @Test
        @DisplayName("the sort by given name folds the same way")
        void nameOrderIgnoresCaseAndDiacritics() {
            contactRepository.saveAndFlush(newContact("c1@x.cz", "Šárka", "A"));
            contactRepository.saveAndFlush(newContact("c2@x.cz", "eva", "B"));
            contactRepository.saveAndFlush(newContact("c3@x.cz", "Čeněk", "C"));
            contactRepository.saveAndFlush(newContact("c4@x.cz", "Adam", "D"));

            assertThat(listSurnames("name")).containsExactly("D", "C", "B", "A");
        }

        @Test
        @DisplayName("editing a surname refreshes the key the search matches on")
        void editingTheSurnameRefreshesItsKey() {
            Long id = contactRepository.saveAndFlush(newContact("jan@x.cz", "Jan", "Čermák")).getId();

            newService().updateContact(id, new ContactUpdateRequest(List.of(new ContactEmailRequest("jan@x.cz", null)),
                    List.of(), "Jan", "Dvořák", null));

            assertThat(searchEmails("dvor")).containsExactly("jan@x.cz");
            assertThat(searchEmails("cer")).isEmpty();
        }

        @Test
        @DisplayName("labels are listed, on their own and on a contact, with diacritics and case among their letters")
        void labelOrderIgnoresCaseAndDiacritics() {
            ContactEntity carrier = newContact("jan@x.cz", "Jan", "Novák");
            for (String name : List.of("Zahrada", "Škola", "rodina", "Archiv")) {
                carrier.getLabels().add(newLabel(name));
            }
            Long id = contactRepository.saveAndFlush(carrier).getId();
            em.clear();

            List<String> expected = List.of("Archiv", "rodina", "Škola", "Zahrada");
            ContactLabelService labelService = new ContactLabelService(labelRepository, contactRepository);
            assertThat(labelService.listLabels()).extracting(ContactLabelResponse::name)
                    .containsExactlyElementsOf(expected);
            assertThat(newService().getContact(id).labels()).extracting(ContactLabelResponse::name)
                    .containsExactlyElementsOf(expected);
        }
    }

    @Nested
    @DisplayName("Query words — each word matched on its own, LIKE wildcards taken literally")
    class Words {

        private List<String> searchEmails(String q) {
            em.flush();
            em.clear();
            return newService().searchContacts(q, 0, 20, null, null).getContent().stream()
                    .map(ContactRepositoryIT::primaryEmailOf).toList();
        }

        private ContactAutocompleteService typeahead() {
            return new ContactAutocompleteService(contactRepository, new CorrespondentService(correspondentRepository),
                    Mockito.mock(AccountService.class));
        }

        @Test
        @DisplayName("a full name finds the contact in either order, the words on different fields")
        void fullNameMatchesWordByWord() {
            contactRepository.saveAndFlush(newContact("jan@x.cz", "Jan", "Novák"));
            contactRepository.saveAndFlush(newContact("jana@x.cz", "Jana", "Dvořáková"));
            contactRepository.saveAndFlush(newContact("petr@x.cz", "Petr", "Novák"));

            for (String q : List.of("Jan Novák", "novák jan", "Novák, Jan", "jan no")) {
                assertThat(searchEmails(q)).as("query %s", q).containsExactly("jan@x.cz");
            }
            // One word still matches every field it occurs in.
            assertThat(searchEmails("novák")).containsExactlyInAnyOrder("jan@x.cz", "petr@x.cz");
            // A word may also be found in an address.
            assertThat(searchEmails("petr x.cz")).containsExactly("petr@x.cz");
        }

        @Test
        @DisplayName("_ and % in the query match themselves, not any character")
        void likeWildcardsMatchLiterally() {
            contactRepository.saveAndFlush(newContact("jan_novak@x.cz", "Jan", "Novák"));
            contactRepository.saveAndFlush(newContact("jan.novak@x.cz", "Jan", "Nováček"));
            contactRepository.saveAndFlush(newContact("eva@x.cz", "Eva", "Malá"));

            // Unescaped, "_" matched the dot as well.
            assertThat(searchEmails("jan_novak")).containsExactly("jan_novak@x.cz");
            assertThat(searchEmails("_")).containsExactly("jan_novak@x.cz");
            // Unescaped, "%" listed the whole address book.
            assertThat(searchEmails("%")).isEmpty();
        }

        @Test
        @DisplayName("a query of separators alone matches nothing rather than everything")
        void separatorsAloneMatchNothing() {
            contactRepository.saveAndFlush(newContact("jan@x.cz", "Jan", "Novák"));

            assertThat(searchEmails(",")).isEmpty();
        }

        @Test
        @DisplayName("the typeahead keeps the best match even when weaker ones fill its limit alphabetically")
        void typeaheadRanksBeforeTheLimit() {
            // Twelve weak matches ("in" inside the given name) that all sort before Z.
            for (int i = 0; i < 12; i++) {
                contactRepository.saveAndFlush(newContact("martin" + i + "@x.cz", "Martin", "A" + i));
            }
            // The one whose address starts with the query, sorting last by surname.
            contactRepository.saveAndFlush(newContact("info@zahradnictvi.cz", "Zdeněk", "Zahradník"));
            em.flush();
            em.clear();

            List<ContactAutocompleteResponse> rows = typeahead().autocomplete(account.getId(), "in", 10);

            assertThat(rows).hasSize(10);
            assertThat(rows.getFirst().email()).isEqualTo("info@zahradnictvi.cz");
        }

        @Test
        @DisplayName("a contact found by its full name is offered as a contact, not as history")
        void fullNameTypeaheadOffersTheContact() {
            contactRepository.saveAndFlush(newContact("jan@x.cz", "Jan", "Novák"));
            // The same person harvested from mail: a display name is one string, so
            // the history half always matched "jan no".
            correspondentRepository.upsert(account.getId(), "jan@x.cz", "Jan Novák", TextFold.key("Jan Novák"), 1, 0,
                    LocalDateTime.now());
            em.flush();
            em.clear();

            List<ContactAutocompleteResponse> rows = typeahead().autocomplete(account.getId(), "jan no", 10);

            assertThat(rows).singleElement().satisfies(row -> {
                assertThat(row.email()).isEqualTo("jan@x.cz");
                assertThat(row.source()).isEqualTo(AutocompleteSource.CONTACT);
            });
        }
    }

    @Test
    @DisplayName("findAllFiltered — paginates in SQL, not in memory (no HHH90003004 collection fetch-join)")
    void paginationDoesNotApplyLimitInMemory() {
        // More contacts than the page size, each with several emails: a fetch-join
        // of the emails collection together with Pageable would force Hibernate to
        // page the result in memory and log HHH90003004. Batch fetching keeps the
        // LIMIT in SQL while still loading the emails.
        for (int i = 0; i < 5; i++) {
            ContactEntity c = newContact("primary" + i + "@x.cz", "Name" + i, "Surname" + i);
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
            Page<ContactEntity> page = contactRepository.findAllFiltered(null, PageRequest.of(0, 2, Sort.by("id")));

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
    @DisplayName("findAllFiltered/search — filtering by contact label")
    class LabelFilter {

        private ContactLabelEntity family;
        private ContactLabelEntity clients;

        @BeforeEach
        void seed() {
            family = newLabel("Family");
            clients = newLabel("Clients");

            ContactEntity familyOnly = newContact("family@x.cz", "Family", "Person");
            familyOnly.getLabels().add(family);
            contactRepository.saveAndFlush(familyOnly);

            ContactEntity clientOnly = newContact("client@x.cz", "Client", "Person");
            clientOnly.getLabels().add(clients);
            contactRepository.saveAndFlush(clientOnly);

            // No label — must not pass the label filter.
            contactRepository.saveAndFlush(newContact("noop@x.cz", "Noop", "Person"));

            ContactEntity bothLabels = newContact("both@x.cz", "Both", "Person");
            bothLabels.getLabels().add(family);
            bothLabels.getLabels().add(clients);
            contactRepository.saveAndFlush(bothLabels);
        }

        @Test
        @DisplayName("findAllFiltered(labelId) returns every contact carrying that label, each once")
        void listByLabel() {
            Page<ContactEntity> p = contactRepository.findAllFiltered(family.getId(), PageRequest.of(0, 10));
            assertThat(p.getContent()).extracting(ContactRepositoryIT.this::primaryEmail)
                    .containsExactlyInAnyOrder("family@x.cz", "both@x.cz");
        }

        @Test
        @DisplayName("findAllFiltered(labelId) of another label returns its own contacts")
        void listByOtherLabel() {
            Page<ContactEntity> p = contactRepository.findAllFiltered(clients.getId(), PageRequest.of(0, 10));
            assertThat(p.getContent()).extracting(ContactRepositoryIT.this::primaryEmail)
                    .containsExactlyInAnyOrder("client@x.cz", "both@x.cz");
        }

        @Test
        @DisplayName("findAllFiltered(labelId=null) returns all contacts (filter inactive)")
        void listAllWhenLabelNull() {
            Page<ContactEntity> p = contactRepository.findAllFiltered((Long) null, PageRequest.of(0, 10));
            assertThat(p.getContent()).hasSize(4);
        }

        @Test
        @DisplayName("search combines the q filter with the label filter")
        void searchWithLabel() {
            Page<ContactEntity> p = contactRepository.search("%both%", null, null, null, family.getId(),
                    PageRequest.of(0, 10));
            assertThat(p.getContent()).extracting(ContactRepositoryIT.this::primaryEmail).containsExactly("both@x.cz");
        }
    }

    @Nested
    @DisplayName("contact_labels constraints")
    class LabelConstraints {

        @Test
        @DisplayName("Two labels with the same name_key -> constraint violation")
        void duplicateNameKeyRejected() {
            newLabel("Family");
            // Hibernate wraps the SQLite unique violation as JpaSystemException, so
            // assert on the common DataAccessException base like the e-mail unique
            // test above, and pin the constraint by name in the message.
            assertThatThrownBy(() -> newLabel("Family")).isInstanceOf(DataAccessException.class)
                    .hasMessageContaining("contact_labels.name_key");
        }

        @Test
        @DisplayName("Deleting a contact drops its assignments but keeps the label")
        void deletingContactKeepsLabel() {
            ContactLabelEntity family = newLabel("Family");
            ContactEntity c = newContact("a@x.cz", "A", null);
            c.getLabels().add(family);
            ContactEntity saved = contactRepository.saveAndFlush(c);
            em.clear();

            contactRepository.deleteById(saved.getId());
            contactRepository.flush();
            em.clear();

            assertThat(labelRepository.findById(family.getId())).isPresent();
            assertThat(contactRepository.findByLabelId(family.getId())).isEmpty();
        }
    }
}
