package org.voxrox.mailbackend.feature.contact.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;
import org.voxrox.mailbackend.exception.AccountNotFoundException;
import org.voxrox.mailbackend.exception.ValidationException;
import org.voxrox.mailbackend.feature.account.entity.AccountEntity;
import org.voxrox.mailbackend.feature.account.service.AccountService;
import org.voxrox.mailbackend.feature.contact.AutocompleteSource;
import org.voxrox.mailbackend.feature.contact.dto.ContactAutocompleteResponse;
import org.voxrox.mailbackend.feature.contact.entity.ContactEmailEntity;
import org.voxrox.mailbackend.feature.contact.entity.ContactEntity;
import org.voxrox.mailbackend.feature.contact.entity.CorrespondentEntity;
import org.voxrox.mailbackend.feature.contact.repository.ContactRepository;

@ExtendWith(MockitoExtension.class)
class ContactAutocompleteServiceTest {

    private static final Long ACCOUNT_ID = 1L;

    @Mock
    private ContactRepository contactRepository;
    @Mock
    private CorrespondentService correspondentService;
    @Mock
    private AccountService accountService;

    private ContactAutocompleteService service;

    @BeforeEach
    void setUp() {
        service = new ContactAutocompleteService(contactRepository, correspondentService, accountService);
    }

    private AccountEntity account() {
        AccountEntity a = new AccountEntity();
        a.setId(ACCOUNT_ID);
        a.setEmail("owner@example.com");
        return a;
    }

    private ContactEntity namedContact(Long id, String name, String surname, String... emails) {
        ContactEntity c = new ContactEntity();
        c.setId(id);
        c.setName(name);
        c.setSurname(surname);
        for (int i = 0; i < emails.length; i++) {
            ContactEmailEntity em = new ContactEmailEntity();
            em.setId(10 * id + i);
            em.setEmail(emails[i]);
            em.setPrimary(i == 0);
            em.setContact(c);
            c.getEmails().add(em);
        }
        return c;
    }

    @Test
    @DisplayName("autocomplete — empty q -> ValidationException")
    void autocompleteBlankQ() {
        assertThatThrownBy(() -> service.autocomplete(ACCOUNT_ID, "  ", 10)).isInstanceOf(ValidationException.class);
        verify(contactRepository, never()).search(anyString(), anyString(), any(), any());
    }

    @Test
    @DisplayName("autocomplete — ranking: email prefix > surname prefix > name prefix > substring")
    void autocompleteRanking() {

        // Substring match (rank 3) - email contains "ali" but does not start with it
        ContactEntity c1 = namedContact(1L, "Zara", "Smith", "contact.ali@example.com");
        // Name prefix match (rank 2)
        ContactEntity c2 = namedContact(2L, "Alice", "Johnson", "john@example.com");
        // Surname prefix match (rank 1)
        ContactEntity c3 = namedContact(3L, "Bob", "Alioto", "bob@example.com");
        // Email prefix match (rank 0)
        ContactEntity c4 = namedContact(4L, "Xena", "Warrior", "ali@example.com");

        when(contactRepository.search(eq("%ali%"), eq("%ali%"), eq(null), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(c1, c2, c3, c4)));

        List<ContactAutocompleteResponse> result = service.autocomplete(ACCOUNT_ID, "ali", 10);

        assertThat(result).extracting(ContactAutocompleteResponse::email).containsExactly("ali@example.com", // rank 0:
                                                                                                             // email
                                                                                                             // prefix
                "bob@example.com", // rank 1: surname prefix "Alioto"
                "john@example.com", // rank 2: name prefix "Alice"
                "contact.ali@example.com"); // rank 3: substring fallback
    }

    @ParameterizedTest(name = "query {0}")
    @ValueSource(strings = {"cer", "Čer", "ČER"})
    @DisplayName("autocomplete — names rank by their folded form, so a diacritic does not demote a prefix match")
    void autocompleteRanksFoldedNames(String query) {
        // Substring only: "cer" inside the folded "kocerova" (rank 3).
        ContactEntity substring = namedContact(1L, "Alena", "Kocerová", "alena@example.com");
        // Surname prefix of the folded "cermak" (rank 1).
        ContactEntity surname = namedContact(2L, "Jan", "Čermák", "jan@example.com");
        when(contactRepository.search(eq("%" + query.toLowerCase(java.util.Locale.ROOT) + "%"), eq("%cer%"), eq(null),
                any(Pageable.class))).thenReturn(new PageImpl<>(List.of(substring, surname)));
        // Display-name prefix of the folded "cermakova eva" (rank 2).
        when(correspondentService.search(eq(ACCOUNT_ID), anyString(), anyInt()))
                .thenReturn(List.of(correspondent("eva@example.com", "Čermáková Eva")));

        List<ContactAutocompleteResponse> result = service.autocomplete(ACCOUNT_ID, query, 10);

        assertThat(result).extracting(ContactAutocompleteResponse::email).containsExactly("jan@example.com",
                "eva@example.com", "alena@example.com");
    }

    @Test
    @DisplayName("autocomplete — flattening: a contact with 2 emails produces 2 rows")
    void autocompleteFlattensEmails() {

        ContactEntity c = namedContact(10L, "Alice", "Liddell", "work@x.cz", "home@x.cz");
        when(contactRepository.search(anyString(), anyString(), eq(null), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(c)));

        List<ContactAutocompleteResponse> result = service.autocomplete(ACCOUNT_ID, "alice", 10);

        assertThat(result).hasSize(2);
        // Both rows have rank 2 (name "Alice" prefix); the secondary order is
        // alphabetical by email:
        // home@x.cz (primary=false) < work@x.cz (primary=true)
        assertThat(result).extracting(ContactAutocompleteResponse::email).containsExactly("home@x.cz", "work@x.cz");
        assertThat(result).extracting(ContactAutocompleteResponse::primary).containsExactly(false, true);
        assertThat(result.get(0).contactId()).isEqualTo(10L);
    }

    @Test
    @DisplayName("autocomplete — limit clamped to the hard cap of 20")
    void autocompleteHardCap() {

        List<ContactEntity> many = new java.util.ArrayList<>();
        for (int i = 0; i < 25; i++) {
            many.add(namedContact((long) i, "N" + i, "S" + i, "x" + i + "@cz"));
        }

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        when(contactRepository.search(anyString(), anyString(), eq(null), pageableCaptor.capture()))
                .thenReturn(new PageImpl<>(many.subList(0, 20)));

        List<ContactAutocompleteResponse> result = service.autocomplete(ACCOUNT_ID, "x", 100);

        // The repository was asked for a page of 20 (the cap), not 100
        assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(20);
        // And the result has at most 20 rows
        assertThat(result).hasSizeLessThanOrEqualTo(20);
    }

    @Test
    @DisplayName("autocomplete — non-existing account -> AccountNotFoundException")
    void autocompleteAccountNotFound() {
        when(accountService.getAccountOrThrow(999L)).thenThrow(new AccountNotFoundException(999L));

        assertThatThrownBy(() -> service.autocomplete(999L, "ali", 10)).isInstanceOf(AccountNotFoundException.class);
    }

    // --- autocomplete: merging the address book with harvested history ---

    private CorrespondentEntity correspondent(String email, String displayName) {
        return new CorrespondentEntity(account(), email, displayName, 0, 1, LocalDateTime.now());
    }

    @Test
    @DisplayName("autocomplete — harvested addresses are offered and marked as not being contacts")
    void autocompleteIncludesHistory() {
        when(contactRepository.search(anyString(), anyString(), eq(null), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));
        CorrespondentEntity harvested = correspondent("jan.dvorak@example.com", "Jan Dvorak");
        // The id is generated by the database, and the entity has no setter for it.
        ReflectionTestUtils.setField(harvested, "id", 33L);
        when(correspondentService.search(eq(ACCOUNT_ID), eq("jan"), anyInt())).thenReturn(List.of(harvested));

        List<ContactAutocompleteResponse> result = service.autocomplete(ACCOUNT_ID, "jan", 10);

        assertThat(result).singleElement().satisfies(row -> {
            assertThat(row.email()).isEqualTo("jan.dvorak@example.com");
            assertThat(row.source()).isEqualTo(AutocompleteSource.HISTORY);
            // What the client forgets the suggestion by.
            assertThat(row.historyId()).isEqualTo(33L);
            // The display name lands in name undivided; surname stays null because
            // splitting a header display name would be a guess.
            assertThat(row.name()).isEqualTo("Jan Dvorak");
            assertThat(row.surname()).isNull();
            assertThat(row.contactId()).isNull();
            assertThat(row.emailId()).isNull();
            assertThat(row.primary()).isNull();
        });
    }

    @Test
    @DisplayName("autocomplete — an address in both sources appears once, as the contact")
    void autocompleteDeduplicatesAcrossSources() {
        ContactEntity jana = namedContact(1L, "Jana", "Novak", "jana@example.com");
        when(contactRepository.search(anyString(), anyString(), eq(null), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(jana)));
        when(correspondentService.search(eq(ACCOUNT_ID), eq("jana"), anyInt()))
                .thenReturn(List.of(correspondent("jana@example.com", "Jana Novak")));

        List<ContactAutocompleteResponse> result = service.autocomplete(ACCOUNT_ID, "jana", 10);

        assertThat(result).singleElement()
                .satisfies(row -> assertThat(row.source()).isEqualTo(AutocompleteSource.CONTACT));
    }

    @Test
    @DisplayName("autocomplete — sources interleave by rank rather than stacking as blocks")
    void autocompleteInterleavesByRank() {
        // Contact matches only as a substring (rank 3); the harvested address
        // matches the prefix (rank 0), so it has to come first even though the
        // address book is the preferred source on a tie.
        ContactEntity weak = namedContact(1L, "Zara", "Smith", "contact.ali@example.com");
        when(contactRepository.search(anyString(), anyString(), eq(null), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(weak)));
        when(correspondentService.search(eq(ACCOUNT_ID), eq("ali"), anyInt()))
                .thenReturn(List.of(correspondent("ali@example.com", "Ali Baba")));

        List<ContactAutocompleteResponse> result = service.autocomplete(ACCOUNT_ID, "ali", 10);

        assertThat(result).extracting(ContactAutocompleteResponse::email).containsExactly("ali@example.com",
                "contact.ali@example.com");
    }

    @Test
    @DisplayName("autocomplete — a contact wins a rank tie against an equally good history row")
    void autocompleteContactWinsTie() {
        ContactEntity contact = namedContact(1L, "Alice", "Smith", "ali.contact@example.com");
        when(contactRepository.search(anyString(), anyString(), eq(null), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(contact)));
        when(correspondentService.search(eq(ACCOUNT_ID), eq("ali"), anyInt()))
                .thenReturn(List.of(correspondent("ali.history@example.com", "Ali History")));

        List<ContactAutocompleteResponse> result = service.autocomplete(ACCOUNT_ID, "ali", 10);

        assertThat(result).extracting(ContactAutocompleteResponse::email).containsExactly("ali.contact@example.com",
                "ali.history@example.com");
    }

    @Test
    @DisplayName("autocomplete — the limit applies to the merged list, not per source")
    void autocompleteLimitsTheMergedList() {
        when(contactRepository.search(anyString(), anyString(), eq(null), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(namedContact(1L, "Ann", "A", "ali1@example.com"),
                        namedContact(2L, "Ann", "B", "ali2@example.com"))));
        when(correspondentService.search(eq(ACCOUNT_ID), eq("ali"), anyInt())).thenReturn(
                List.of(correspondent("ali3@example.com", "Ali Three"), correspondent("ali4@example.com", "Ali Four")));

        // Four candidates, three of them asked for: a client stitching two
        // independently limited lists together would show all four.
        assertThat(service.autocomplete(ACCOUNT_ID, "ali", 3)).hasSize(3);
    }
}
