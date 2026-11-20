package org.voxrox.mailbackend.feature.contact.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.voxrox.mailbackend.exception.ContactLabelNotFoundException;
import org.voxrox.mailbackend.exception.ContactNotFoundException;
import org.voxrox.mailbackend.exception.DuplicateContactException;
import org.voxrox.mailbackend.exception.ValidationException;
import org.voxrox.mailbackend.feature.contact.EmailLabel;
import org.voxrox.mailbackend.feature.contact.dto.ContactCountsResponse;
import org.voxrox.mailbackend.feature.contact.dto.ContactCreateRequest;
import org.voxrox.mailbackend.feature.contact.dto.ContactEmailRequest;
import org.voxrox.mailbackend.feature.contact.dto.ContactEmailResponse;
import org.voxrox.mailbackend.feature.contact.dto.ContactLabelCountResponse;
import org.voxrox.mailbackend.feature.contact.dto.ContactLabelResponse;
import org.voxrox.mailbackend.feature.contact.dto.ContactMergeRequest;
import org.voxrox.mailbackend.feature.contact.dto.ContactResponse;
import org.voxrox.mailbackend.feature.contact.dto.ContactUpdateRequest;
import org.voxrox.mailbackend.feature.contact.entity.ContactEmailEntity;
import org.voxrox.mailbackend.feature.contact.entity.ContactEntity;
import org.voxrox.mailbackend.feature.contact.entity.ContactLabelEntity;
import org.voxrox.mailbackend.feature.contact.mapper.ContactMapper;
import org.voxrox.mailbackend.feature.contact.repository.ContactLabelCount;
import org.voxrox.mailbackend.feature.contact.repository.ContactLabelRepository;
import org.voxrox.mailbackend.feature.contact.repository.ContactRepository;

@ExtendWith(MockitoExtension.class)
class ContactServiceTest {

    private static final Long CONTACT_ID = 10L;
    private static final String EMAIL = "alice@example.com";

    @Mock
    private ContactRepository contactRepository;
    @Mock
    private ContactLabelRepository contactLabelRepository;
    @Mock
    private ContactLabelService contactLabelService;

    private final ContactMapper contactMapper = new ContactMapper();
    private ContactService service;

    @BeforeEach
    void setUp() {
        service = new ContactService(contactRepository, contactLabelRepository, contactLabelService, contactMapper);
    }

    private ContactEntity contact(Long id, String... emails) {
        ContactEntity c = new ContactEntity();
        c.setId(id);
        c.setName("Alice");
        c.setSurname("Liddell");
        for (int i = 0; i < emails.length; i++) {
            ContactEmailEntity em = new ContactEmailEntity();
            em.setId((long) (i + 1));
            em.setEmail(emails[i]);
            em.setPrimary(i == 0);
            em.setContact(c);
            c.getEmails().add(em);
        }
        return c;
    }

    private ContactEmailRequest emailReq(String email) {
        return new ContactEmailRequest(email, null);
    }

    @Test
    @DisplayName("listContacts — paginated listing via the mapper")
    void listContactsReturnsPage() {
        Page<ContactEntity> page = new PageImpl<>(List.of(contact(10L, EMAIL), contact(11L, "bob@example.com")));
        when(contactRepository.findAllFiltered(eq(null), any(Pageable.class))).thenReturn(page);

        Page<ContactResponse> result = service.listContacts(0, 20, null, null);

        assertThat(result.getContent()).hasSize(2);
        assertThat(result.getContent().get(0).emails().get(0).email()).isEqualTo(EMAIL);
    }

    @Test
    @DisplayName("getCounts — every label is listed, the unused ones with zero")
    void getCountsMapsLabels() {
        when(contactRepository.count()).thenReturn(5L);
        // The aggregate only knows labels somebody uses — "Archive" is missing from it.
        when(contactRepository.countGroupedByLabel())
                .thenReturn(List.of(new ContactLabelCount(1L, 3L), new ContactLabelCount(3L, 1L)));
        when(contactLabelRepository.findAllByOrderByNameFoldAscNameKeyAsc())
                .thenReturn(List.of(label(1L, "Clients"), label(2L, "Archive"), label(3L, "Family")));

        ContactCountsResponse counts = service.getCounts();

        assertThat(counts).isEqualTo(new ContactCountsResponse(5L,
                List.of(new ContactLabelCountResponse(1L, "Clients", 3L),
                        new ContactLabelCountResponse(2L, "Archive", 0L),
                        new ContactLabelCountResponse(3L, "Family", 1L))));
    }

    private ContactLabelEntity label(Long id, String name) {
        ContactLabelEntity l = new ContactLabelEntity();
        l.setId(id);
        l.setName(name);
        l.setNameKey(name.toLowerCase(java.util.Locale.ROOT));
        return l;
    }

    @Test
    @DisplayName("searchContacts — q zabaleno %...% a lowercased")
    void searchContactsWrapsPattern() {
        when(contactRepository.search(eq("%alice%"), isNull(), isNull(), isNull(), eq(null), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(contact(10L, EMAIL))));

        // Padded on purpose: both halves search the trimmed query, as autocomplete
        // does.
        Page<ContactResponse> result = service.searchContacts(" ALICE ", 0, 20, null, null);

        assertThat(result.getContent()).hasSize(1);
    }

    @Test
    @DisplayName("getContact — happy path")
    void getContactHappyPath() {
        when(contactRepository.findById(CONTACT_ID)).thenReturn(Optional.of(contact(CONTACT_ID, EMAIL)));

        ContactResponse r = service.getContact(CONTACT_ID);

        assertThat(r.id()).isEqualTo(CONTACT_ID);
        assertThat(r.emails().get(0).email()).isEqualTo(EMAIL);
    }

    @Test
    @DisplayName("getContact — cross-account → ContactNotFoundException")
    void getContactCrossAccountReturnsNotFound() {
        when(contactRepository.findById(CONTACT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getContact(CONTACT_ID)).isInstanceOf(ContactNotFoundException.class);
    }

    @Test
    @DisplayName("createContact — saves with audit trail, multiple emails")
    void createContactSaves() {
        when(contactRepository.findByAnyEmailIn(any())).thenReturn(List.of());
        when(contactRepository.save(any(ContactEntity.class))).thenAnswer(inv -> {
            ContactEntity e = inv.getArgument(0);
            e.setId(CONTACT_ID);
            return e;
        });

        ContactResponse r = service.createContact(new ContactCreateRequest(
                List.of(emailReq(EMAIL), new ContactEmailRequest("home@example.com", EmailLabel.HOME)), null, "Alice",
                "Liddell", "VIP"));

        assertThat(r.id()).isEqualTo(CONTACT_ID);
        ArgumentCaptor<ContactEntity> captor = ArgumentCaptor.forClass(ContactEntity.class);
        verify(contactRepository).save(captor.capture());
        assertThat(captor.getValue().getEmails()).hasSize(2);
        assertThat(captor.getValue().getEmails().get(0).getEmail()).isEqualTo(EMAIL);
        assertThat(captor.getValue().getEmails().get(0).isPrimary()).isTrue();
        assertThat(captor.getValue().getEmails().get(1).getLabel()).isEqualTo(EmailLabel.HOME);
    }

    @Nested
    @DisplayName("Contact labels")
    class Labels {

        @Test
        @DisplayName("createContact attaches the resolved labels")
        void createAttachesLabels() {
            ContactLabelEntity family = label(1L, "Family");
            when(contactLabelService.resolveLabels(List.of(1L))).thenReturn(Set.of(family));
            when(contactRepository.save(any(ContactEntity.class))).thenAnswer(inv -> {
                ContactEntity e = inv.getArgument(0);
                e.setId(CONTACT_ID);
                return e;
            });

            ContactResponse r = service.createContact(
                    new ContactCreateRequest(List.of(emailReq(EMAIL)), List.of(1L), "Alice", null, null));

            assertThat(r.labels()).containsExactly(new ContactLabelResponse(1L, "Family"));
        }

        @Test
        @DisplayName("PUT with no labelIds clears the labels — replace semantics")
        void updateClearsLabelsWhenAbsent() {
            ContactEntity existing = contact(CONTACT_ID, EMAIL);
            existing.getLabels().add(label(1L, "Family"));
            when(contactRepository.findById(CONTACT_ID)).thenReturn(Optional.of(existing));
            when(contactLabelService.resolveLabels(null)).thenReturn(Set.of());
            when(contactRepository.save(existing)).thenReturn(existing);

            ContactResponse r = service.updateContact(CONTACT_ID,
                    new ContactUpdateRequest(List.of(emailReq(EMAIL)), null, "Alice", null, null));

            assertThat(r.labels()).isEmpty();
        }

        @Test
        @DisplayName("merge unions the labels — one only the source carried is not lost")
        void mergeUnionsLabels() {
            ContactLabelEntity family = label(1L, "Family");
            ContactLabelEntity clients = label(2L, "Clients");

            ContactEntity target = contact(CONTACT_ID, EMAIL);
            target.getLabels().add(family);
            ContactEntity source = contact(11L, "bob@example.com");
            source.getLabels().add(clients);
            when(contactRepository.findById(CONTACT_ID)).thenReturn(Optional.of(target));
            when(contactRepository.findById(11L)).thenReturn(Optional.of(source));
            when(contactRepository.save(target)).thenReturn(target);

            ContactResponse r = service.merge(CONTACT_ID, new ContactMergeRequest(List.of(11L)));

            assertThat(r.labels()).containsExactlyInAnyOrder(new ContactLabelResponse(1L, "Family"),
                    new ContactLabelResponse(2L, "Clients"));
            verify(contactRepository).delete(source);
        }
    }

    @Test
    @DisplayName("createContact — email taken by another contact -> DuplicateContactException")
    void createContactDuplicate() {
        when(contactRepository.findByAnyEmailIn(any())).thenReturn(List.of(contact(99L, EMAIL)));

        assertThatThrownBy(() -> service
                .createContact(new ContactCreateRequest(List.of(emailReq(EMAIL)), null, "Alice", null, null)))
                .isInstanceOf(DuplicateContactException.class);

        verify(contactRepository, never()).save(any());
    }

    @Test
    @DisplayName("createContact — duplicate email within the request -> ValidationException (400)")
    void createContactDuplicateWithinRequest() {

        assertThatThrownBy(() -> service.createContact(
                new ContactCreateRequest(List.of(emailReq(EMAIL), emailReq(EMAIL)), null, null, null, null)))
                .isInstanceOf(ValidationException.class).hasMessageContaining("appears more than once");

        verify(contactRepository, never()).save(any());
    }

    @Test
    @DisplayName("updateContact — overwrites all fields including emails")
    void updateContactReplacesFields() {
        ContactEntity existing = contact(CONTACT_ID, EMAIL);
        when(contactRepository.findById(CONTACT_ID)).thenReturn(Optional.of(existing));
        when(contactRepository.findByAnyEmailIn(any())).thenReturn(List.of(existing));
        when(contactRepository.save(existing)).thenReturn(existing);

        ContactResponse r = service.updateContact(CONTACT_ID,
                new ContactUpdateRequest(List.of(emailReq(EMAIL)), null, "Alice", "Liddell", "note"));

        assertThat(existing.getName()).isEqualTo("Alice");
        assertThat(existing.getSurname()).isEqualTo("Liddell");
        assertThat(existing.getNote()).isEqualTo("note");
        assertThat(r.name()).isEqualTo("Alice");
    }

    @Test
    @DisplayName("updateContact — new email taken by a foreign contact -> DuplicateContactException")
    void updateContactDuplicate() {
        ContactEntity existing = contact(CONTACT_ID, EMAIL);
        ContactEntity other = contact(22L, "taken@example.com");
        when(contactRepository.findById(CONTACT_ID)).thenReturn(Optional.of(existing));
        when(contactRepository.findByAnyEmailIn(any())).thenReturn(List.of(other));

        assertThatThrownBy(() -> service.updateContact(CONTACT_ID,
                new ContactUpdateRequest(List.of(emailReq("taken@example.com")), null, "X", null, null)))
                .isInstanceOf(DuplicateContactException.class);

        verify(contactRepository, never()).save(any());
    }

    @Test
    @DisplayName("updateContact — same email on its own contact (no-op) passes")
    void updateContactSameEmailSkipsCheck() {
        ContactEntity existing = contact(CONTACT_ID, EMAIL);
        when(contactRepository.findById(CONTACT_ID)).thenReturn(Optional.of(existing));
        // findByAccountIdAndAnyEmailIn returns the same contact — must pass
        // (excludeContactId = CONTACT_ID)
        when(contactRepository.findByAnyEmailIn(any())).thenReturn(List.of(existing));
        when(contactRepository.save(existing)).thenReturn(existing);

        service.updateContact(CONTACT_ID, new ContactUpdateRequest(List.of(emailReq(EMAIL)), null, "X", null, null));

        verify(contactRepository).save(any());
    }

    @Test
    @DisplayName("deleteContact — deletes the entity")
    void deleteContactDeletes() {
        ContactEntity existing = contact(CONTACT_ID, EMAIL);
        when(contactRepository.findById(CONTACT_ID)).thenReturn(Optional.of(existing));

        service.deleteContact(CONTACT_ID);

        verify(contactRepository).delete(existing);
    }

    @Test
    @DisplayName("deleteContact — missing kontakt → ContactNotFoundException")
    void deleteContactMissing() {
        when(contactRepository.findById(CONTACT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deleteContact(CONTACT_ID)).isInstanceOf(ContactNotFoundException.class);
    }

    @Test
    @DisplayName("createContact — mixed-case email with whitespace is stored normalized")
    void createContactNormalizesEmail() {
        when(contactRepository.findByAnyEmailIn(any())).thenReturn(List.of());
        when(contactRepository.save(any(ContactEntity.class))).thenAnswer(inv -> {
            ContactEntity e = inv.getArgument(0);
            e.setId(CONTACT_ID);
            return e;
        });

        service.createContact(new ContactCreateRequest(List.of(new ContactEmailRequest("  Alice@Example.COM  ", null)),
                null, null, null, null));

        ArgumentCaptor<ContactEntity> captor = ArgumentCaptor.forClass(ContactEntity.class);
        verify(contactRepository).save(captor.capture());
        assertThat(captor.getValue().getEmails().get(0).getEmail()).isEqualTo("alice@example.com");
        verify(contactRepository).findByAnyEmailIn(any());
    }

    @Test
    @DisplayName("createContact — multiple emails checked for collisions in a single batch query")
    void createContactChecksDuplicatesWithSingleBatchLookup() {
        when(contactRepository.findByAnyEmailIn(any())).thenReturn(List.of());
        when(contactRepository.save(any(ContactEntity.class))).thenAnswer(inv -> {
            ContactEntity e = inv.getArgument(0);
            e.setId(CONTACT_ID);
            return e;
        });

        service.createContact(new ContactCreateRequest(
                List.of(emailReq("first@example.com"), emailReq("second@example.com"), emailReq("third@example.com")),
                null, null, null, null));

        verify(contactRepository).findByAnyEmailIn(any());
    }

    @Test
    @DisplayName("searchContacts — null q → ValidationException")
    void searchContactsNullQ() {
        assertThatThrownBy(() -> service.searchContacts(null, 0, 20, null, null))
                .isInstanceOf(ValidationException.class);
        verify(contactRepository, never()).search(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("searchContacts — blank q → ValidationException")
    void searchContactsBlankQ() {
        assertThatThrownBy(() -> service.searchContacts("   ", 0, 20, null, null))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    @DisplayName("listContacts — Pageable passed with the requested page size")
    void listContactsPageableSize() {
        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        when(contactRepository.findAllFiltered(eq(null), pageableCaptor.capture()))
                .thenReturn(new PageImpl<>(List.of()));

        service.listContacts(3, 50, null, null);

        Pageable captured = pageableCaptor.getValue();
        assertThat(captured.getPageNumber()).isEqualTo(3);
        assertThat(captured.getPageSize()).isEqualTo(50);
    }

    @Test
    @DisplayName("listContacts — sort=name → primary order asc by the folded given name")
    void listContactsSortByName() {
        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        when(contactRepository.findAllFiltered(eq(null), pageableCaptor.capture()))
                .thenReturn(new PageImpl<>(List.of()));

        service.listContacts(0, 10, "name", null);

        var orders = pageableCaptor.getValue().getSort().toList();
        // The folded key, not the name: ContactRepositoryIT.CzechNames shows why.
        assertThat(orders.get(0).getProperty()).isEqualTo("nameFold");
        assertThat(orders.get(0).getDirection()).isEqualTo(org.springframework.data.domain.Sort.Direction.ASC);
    }

    @Test
    @DisplayName("listContacts — sort=recent → primary order desc by updatedAt")
    void listContactsSortByRecent() {
        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        when(contactRepository.findAllFiltered(eq(null), pageableCaptor.capture()))
                .thenReturn(new PageImpl<>(List.of()));

        service.listContacts(0, 10, "recent", null);

        var orders = pageableCaptor.getValue().getSort().toList();
        assertThat(orders.get(0).getProperty()).isEqualTo("updatedAt");
        assertThat(orders.get(0).getDirection()).isEqualTo(org.springframework.data.domain.Sort.Direction.DESC);
    }

    @Test
    @DisplayName("listContacts — unknown sort -> ValidationException")
    void listContactsInvalidSort() {
        assertThatThrownBy(() -> service.listContacts(0, 10, "bogus", null)).isInstanceOf(ValidationException.class);
    }

    @Test
    @DisplayName("listContacts — labelId → repository dostane label parametr")
    void listContactsByLabel() {
        when(contactLabelRepository.findById(7L)).thenReturn(java.util.Optional.of(label(7L, "Family")));
        when(contactRepository.findAllFiltered(eq(7L), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(contact(10L, EMAIL))));

        Page<ContactResponse> result = service.listContacts(0, 10, null, 7L);

        assertThat(result.getContent()).hasSize(1);
    }

    @Test
    @DisplayName("listContacts — unknown labelId -> 404, not an empty page")
    void listContactsUnknownLabel() {
        when(contactLabelRepository.findById(99L)).thenReturn(java.util.Optional.empty());

        assertThatThrownBy(() -> service.listContacts(0, 10, null, 99L))
                .isInstanceOf(ContactLabelNotFoundException.class);
        verify(contactRepository, never()).findAllFiltered(any(), any());
    }

    @Test
    @DisplayName("searchContacts — unknown labelId -> 404 before the search runs")
    void searchContactsUnknownLabel() {
        when(contactLabelRepository.findById(99L)).thenReturn(java.util.Optional.empty());

        assertThatThrownBy(() -> service.searchContacts("alice", 0, 10, null, 99L))
                .isInstanceOf(ContactLabelNotFoundException.class);
        verify(contactRepository, never()).search(any(), any(), any(), any(), any(), any());
    }

    // --- merge ---

    private ContactEntity contactWithLabel(Long id, String email, EmailLabel label, String note) {
        ContactEntity c = new ContactEntity();
        c.setId(id);
        c.setName("Bob");
        c.setSurname("Builder");
        c.setNote(note);
        ContactEmailEntity em = new ContactEmailEntity();
        em.setId(id * 100);
        em.setEmail(email);
        em.setLabel(label);
        em.setPrimary(true);
        em.setContact(c);
        c.getEmails().add(em);
        return c;
    }

    @Test
    @DisplayName("merge — happy path: source email is added to the target, source contact deleted")
    void mergeHappyPath() {
        ContactEntity target = contact(CONTACT_ID, "alice@example.com");
        target.setNote("VIP");
        ContactEntity src = contactWithLabel(20L, "alice.work@example.com", EmailLabel.WORK, null);
        when(contactRepository.findById(CONTACT_ID)).thenReturn(Optional.of(target));
        when(contactRepository.findById(20L)).thenReturn(Optional.of(src));
        when(contactRepository.save(any(ContactEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        ContactResponse r = service.merge(CONTACT_ID, new ContactMergeRequest(List.of(20L)));

        assertThat(r.emails()).hasSize(2);
        assertThat(r.emails().stream().map(ContactEmailResponse::email)).containsExactlyInAnyOrder("alice@example.com",
                "alice.work@example.com");
        assertThat(r.emails().stream().filter(ContactEmailResponse::primary).map(ContactEmailResponse::email))
                .containsExactly("alice@example.com");
        verify(contactRepository).delete(src);
    }

    @Test
    @DisplayName("merge — email collision: source email already exists in the target -> dedupe (skipped)")
    void mergeEmailCollisionDedupes() {
        ContactEntity target = contact(CONTACT_ID, "alice@example.com");
        ContactEntity src = contactWithLabel(20L, "ALICE@example.com", EmailLabel.HOME, null);
        when(contactRepository.findById(CONTACT_ID)).thenReturn(Optional.of(target));
        when(contactRepository.findById(20L)).thenReturn(Optional.of(src));
        when(contactRepository.save(any(ContactEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        ContactResponse r = service.merge(CONTACT_ID, new ContactMergeRequest(List.of(20L)));

        assertThat(r.emails()).hasSize(1);
        assertThat(r.emails().get(0).email()).isEqualTo("alice@example.com");
        verify(contactRepository).delete(src);
    }

    @Test
    @DisplayName("merge — target and source notes concatenated with a separator; empty notes skipped")
    void mergeNotesConcatenated() {
        ContactEntity target = contact(CONTACT_ID, "alice@example.com");
        target.setNote("note A");
        ContactEntity src1 = contactWithLabel(20L, "src1@example.com", null, "note B");
        ContactEntity src2 = contactWithLabel(21L, "src2@example.com", null, "  ");
        when(contactRepository.findById(CONTACT_ID)).thenReturn(Optional.of(target));
        when(contactRepository.findById(20L)).thenReturn(Optional.of(src1));
        when(contactRepository.findById(21L)).thenReturn(Optional.of(src2));
        when(contactRepository.save(any(ContactEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        ContactResponse r = service.merge(CONTACT_ID, new ContactMergeRequest(List.of(20L, 21L)));

        assertThat(r.note()).isEqualTo("note A\n\n---\n\nnote B");
    }

    @Test
    @DisplayName("merge — notes that join to more than 1000 characters -> ValidationException, nothing deleted")
    void mergeExceedsNoteLength() {
        ContactEntity target = contact(CONTACT_ID, "alice@example.com");
        target.setNote("a".repeat(600));
        // 600 + the 7-character separator + 394 = 1001, one over.
        ContactEntity src = contactWithLabel(20L, "src@example.com", null, "b".repeat(394));
        when(contactRepository.findById(CONTACT_ID)).thenReturn(Optional.of(target));
        when(contactRepository.findById(20L)).thenReturn(Optional.of(src));

        assertThatThrownBy(() -> service.merge(CONTACT_ID, new ContactMergeRequest(List.of(20L))))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("1001 characters long; the maximum is 1000");

        verify(contactRepository, never()).save(any(ContactEntity.class));
        verify(contactRepository, never()).delete(any(ContactEntity.class));
        assertThat(target.getNote()).hasSize(600);
    }

    @Test
    @DisplayName("merge — notes that join to exactly 1000 characters are kept whole")
    void mergeNoteAtTheLimit() {
        ContactEntity target = contact(CONTACT_ID, "alice@example.com");
        target.setNote("a".repeat(600));
        ContactEntity src = contactWithLabel(20L, "src@example.com", null, "b".repeat(393));
        when(contactRepository.findById(CONTACT_ID)).thenReturn(Optional.of(target));
        when(contactRepository.findById(20L)).thenReturn(Optional.of(src));
        when(contactRepository.save(any(ContactEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        ContactResponse r = service.merge(CONTACT_ID, new ContactMergeRequest(List.of(20L)));

        assertThat(r.note()).hasSize(1000);
    }

    @Test
    @DisplayName("merge — exceeding the 10-email limit -> ValidationException, save is not called")
    void mergeExceedsMaxEmails() {
        ContactEntity target = new ContactEntity();
        target.setId(CONTACT_ID);
        for (int i = 0; i < 8; i++) {
            ContactEmailEntity em = new ContactEmailEntity();
            em.setId((long) i);
            em.setEmail("t" + i + "@example.com");
            em.setPrimary(i == 0);
            em.setContact(target);
            target.getEmails().add(em);
        }
        ContactEntity src = new ContactEntity();
        src.setId(20L);
        for (int i = 0; i < 3; i++) {
            ContactEmailEntity em = new ContactEmailEntity();
            em.setId(100L + i);
            em.setEmail("s" + i + "@example.com");
            em.setContact(src);
            src.getEmails().add(em);
        }
        when(contactRepository.findById(CONTACT_ID)).thenReturn(Optional.of(target));
        when(contactRepository.findById(20L)).thenReturn(Optional.of(src));

        assertThatThrownBy(() -> service.merge(CONTACT_ID, new ContactMergeRequest(List.of(20L))))
                .isInstanceOf(ValidationException.class).hasMessageContaining("11 e-mail addresses; the maximum is 10");

        verify(contactRepository, never()).save(any(ContactEntity.class));
        verify(contactRepository, never()).delete(any(ContactEntity.class));
    }

    @Test
    @DisplayName("merge — target ∈ source → ValidationException")
    void mergeTargetInSource() {

        assertThatThrownBy(() -> service.merge(CONTACT_ID, new ContactMergeRequest(List.of(CONTACT_ID, 20L))))
                .isInstanceOf(ValidationException.class).hasMessageContaining("must not also be");

        verify(contactRepository, never()).delete(any(ContactEntity.class));
    }

    @Test
    @DisplayName("merge — duplicate ID in source -> ValidationException")
    void mergeDuplicateSourceIds() {

        assertThatThrownBy(() -> service.merge(CONTACT_ID, new ContactMergeRequest(List.of(20L, 20L))))
                .isInstanceOf(ValidationException.class).hasMessageContaining("duplicate IDs");
    }

    @Test
    @DisplayName("merge — source kontakt neexistuje → ContactNotFoundException")
    void mergeSourceNotFound() {
        ContactEntity target = contact(CONTACT_ID, "alice@example.com");
        when(contactRepository.findById(CONTACT_ID)).thenReturn(Optional.of(target));
        when(contactRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.merge(CONTACT_ID, new ContactMergeRequest(List.of(99L))))
                .isInstanceOf(ContactNotFoundException.class);

        verify(contactRepository, never()).save(any(ContactEntity.class));
    }

    @Test
    @DisplayName("merge — target without primary, after merge the first email is promoted")
    void mergePromotesPrimaryWhenTargetHasNone() {
        ContactEntity target = new ContactEntity();
        target.setId(CONTACT_ID); // target with an email lacking the primary flag (edge case after an earlier
        // primary deletion)
        ContactEmailEntity te = new ContactEmailEntity();
        te.setId(1L);
        te.setEmail("t@example.com");
        te.setPrimary(false);
        te.setContact(target);
        target.getEmails().add(te);
        ContactEntity src = contactWithLabel(20L, "src@example.com", null, null);
        when(contactRepository.findById(CONTACT_ID)).thenReturn(Optional.of(target));
        when(contactRepository.findById(20L)).thenReturn(Optional.of(src));
        when(contactRepository.save(any(ContactEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        ContactResponse r = service.merge(CONTACT_ID, new ContactMergeRequest(List.of(20L)));

        assertThat(r.emails().stream().filter(ContactEmailResponse::primary).map(ContactEmailResponse::email))
                .containsExactly("t@example.com");
    }

    @Test
    @DisplayName("exportToVCard — happy path: 2 contacts serialized to vCard, audit written")
    void exportToVCardHappyPath() {
        when(contactRepository.findAllBy(any(org.springframework.data.domain.Sort.class)))
                .thenReturn(List.of(contact(10L, EMAIL), contact(11L, "bob@example.com")));

        String vcard = service.exportToVCard();

        assertThat(vcard).startsWith("BEGIN:VCARD\r\n").contains("VERSION:4.0\r\n").contains("EMAIL;PREF=1:" + EMAIL)
                .contains("EMAIL;PREF=1:bob@example.com").endsWith("END:VCARD\r\n");
        int beginCount = vcard.split("BEGIN:VCARD", -1).length - 1;
        assertThat(beginCount).isEqualTo(2);
    }

    @Test
    @DisplayName("exportToVCard — empty address book -> empty string, audit written with count=0")
    void exportToVCardEmpty() {
        when(contactRepository.findAllBy(any(org.springframework.data.domain.Sort.class))).thenReturn(List.of());

        String vcard = service.exportToVCard();

        assertThat(vcard).isEmpty();
    }
}
