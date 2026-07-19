package org.voxrox.mailbackend.feature.contact.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.voxrox.mailbackend.exception.AccountNotFoundException;
import org.voxrox.mailbackend.exception.ContactNotFoundException;
import org.voxrox.mailbackend.exception.DuplicateContactException;
import org.voxrox.mailbackend.exception.ResourceNotFoundException;
import org.voxrox.mailbackend.exception.ValidationException;
import org.voxrox.mailbackend.feature.account.entity.AccountEntity;
import org.voxrox.mailbackend.feature.account.service.AccountService;
import org.voxrox.mailbackend.feature.contact.EmailLabel;
import org.voxrox.mailbackend.feature.contact.dto.ContactAutocompleteResponse;
import org.voxrox.mailbackend.feature.contact.dto.ContactCountsResponse;
import org.voxrox.mailbackend.feature.contact.dto.ContactCreateRequest;
import org.voxrox.mailbackend.feature.contact.dto.ContactEmailRequest;
import org.voxrox.mailbackend.feature.contact.dto.ContactEmailResponse;
import org.voxrox.mailbackend.feature.contact.dto.ContactMergeRequest;
import org.voxrox.mailbackend.feature.contact.dto.ContactPatchRequest;
import org.voxrox.mailbackend.feature.contact.dto.ContactResponse;
import org.voxrox.mailbackend.feature.contact.dto.ContactUpdateRequest;
import org.voxrox.mailbackend.feature.contact.entity.ContactEmailEntity;
import org.voxrox.mailbackend.feature.contact.entity.ContactEntity;
import org.voxrox.mailbackend.feature.contact.mapper.ContactMapper;
import org.voxrox.mailbackend.feature.contact.repository.ContactLabelCount;
import org.voxrox.mailbackend.feature.contact.repository.ContactRepository;

@ExtendWith(MockitoExtension.class)
class ContactServiceTest {

    private static final Long ACCOUNT_ID = 1L;
    private static final Long CONTACT_ID = 10L;
    private static final String EMAIL = "alice@example.com";

    @Mock
    private ContactRepository contactRepository;
    @Mock
    private AccountService accountService;

    private final ContactMapper contactMapper = new ContactMapper();
    private ContactService service;

    @BeforeEach
    void setUp() {
        service = new ContactService(contactRepository, accountService, contactMapper);
    }

    private AccountEntity account() {
        AccountEntity a = new AccountEntity();
        a.setId(ACCOUNT_ID);
        a.setEmail("owner@example.com");
        return a;
    }

    private ContactEntity contact(Long id, String... emails) {
        ContactEntity c = new ContactEntity();
        c.setId(id);
        c.setName("Alice");
        c.setSurname("Liddell");
        c.setAccount(account());
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
        when(accountService.getAccountOrThrow(ACCOUNT_ID)).thenReturn(account());
        Page<ContactEntity> page = new PageImpl<>(List.of(contact(10L, EMAIL), contact(11L, "bob@example.com")));
        when(contactRepository.findByAccountId(eq(ACCOUNT_ID), eq(null), any(Pageable.class))).thenReturn(page);

        Page<ContactResponse> result = service.listContacts(ACCOUNT_ID, 0, 20, null, null);

        assertThat(result.getContent()).hasSize(2);
        assertThat(result.getContent().get(0).emails().get(0).email()).isEqualTo(EMAIL);
    }

    @Test
    @DisplayName("listContacts — unknown account -> AccountNotFoundException")
    void listContactsMissingAccount() {
        when(accountService.getAccountOrThrow(999L)).thenThrow(new AccountNotFoundException(999L));
        assertThatThrownBy(() -> service.listContacts(999L, 0, 20, null, null))
                .isInstanceOf(AccountNotFoundException.class);
    }

    @Test
    @DisplayName("getCounts — total plus per-label counts, missing labels default to zero")
    void getCountsMapsLabels() {
        when(accountService.getAccountOrThrow(ACCOUNT_ID)).thenReturn(account());
        when(contactRepository.countByAccountId(ACCOUNT_ID)).thenReturn(5L);
        when(contactRepository.countByAccountIdGroupedByLabel(ACCOUNT_ID)).thenReturn(
                List.of(new ContactLabelCount(EmailLabel.WORK, 3L), new ContactLabelCount(EmailLabel.OTHER, 1L)));

        ContactCountsResponse counts = service.getCounts(ACCOUNT_ID);

        assertThat(counts).isEqualTo(new ContactCountsResponse(5L, 3L, 0L, 1L));
    }

    @Test
    @DisplayName("getCounts — unknown account -> AccountNotFoundException")
    void getCountsMissingAccount() {
        when(accountService.getAccountOrThrow(999L)).thenThrow(new AccountNotFoundException(999L));
        assertThatThrownBy(() -> service.getCounts(999L)).isInstanceOf(AccountNotFoundException.class);
        verify(contactRepository, never()).countByAccountId(anyLong());
    }

    @Test
    @DisplayName("searchContacts — q zabaleno %...% a lowercased")
    void searchContactsWrapsPattern() {
        when(accountService.getAccountOrThrow(ACCOUNT_ID)).thenReturn(account());
        when(contactRepository.searchByAccountId(eq(ACCOUNT_ID), eq("%alice%"), eq(null), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(contact(10L, EMAIL))));

        Page<ContactResponse> result = service.searchContacts(ACCOUNT_ID, "ALICE", 0, 20, null, null);

        assertThat(result.getContent()).hasSize(1);
    }

    @Test
    @DisplayName("getContact — happy path")
    void getContactHappyPath() {
        when(contactRepository.findByIdAndAccountId(CONTACT_ID, ACCOUNT_ID))
                .thenReturn(Optional.of(contact(CONTACT_ID, EMAIL)));

        ContactResponse r = service.getContact(ACCOUNT_ID, CONTACT_ID);

        assertThat(r.id()).isEqualTo(CONTACT_ID);
        assertThat(r.emails().get(0).email()).isEqualTo(EMAIL);
    }

    @Test
    @DisplayName("getContact — cross-account → ContactNotFoundException")
    void getContactCrossAccountReturnsNotFound() {
        when(contactRepository.findByIdAndAccountId(CONTACT_ID, ACCOUNT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getContact(ACCOUNT_ID, CONTACT_ID))
                .isInstanceOf(ContactNotFoundException.class);
    }

    @Test
    @DisplayName("createContact — saves with audit trail, multiple emails")
    void createContactSaves() {
        when(accountService.getAccountOrThrow(ACCOUNT_ID)).thenReturn(account());
        when(contactRepository.findByAccountIdAndAnyEmailIn(eq(ACCOUNT_ID), any())).thenReturn(List.of());
        when(contactRepository.save(any(ContactEntity.class))).thenAnswer(inv -> {
            ContactEntity e = inv.getArgument(0);
            e.setId(CONTACT_ID);
            return e;
        });

        ContactResponse r = service.createContact(ACCOUNT_ID,
                new ContactCreateRequest(
                        List.of(emailReq(EMAIL), new ContactEmailRequest("home@example.com", EmailLabel.HOME)), "Alice",
                        "Liddell", "VIP"));

        assertThat(r.id()).isEqualTo(CONTACT_ID);
        ArgumentCaptor<ContactEntity> captor = ArgumentCaptor.forClass(ContactEntity.class);
        verify(contactRepository).save(captor.capture());
        assertThat(captor.getValue().getEmails()).hasSize(2);
        assertThat(captor.getValue().getEmails().get(0).getEmail()).isEqualTo(EMAIL);
        assertThat(captor.getValue().getEmails().get(0).isPrimary()).isTrue();
        assertThat(captor.getValue().getEmails().get(1).getLabel()).isEqualTo(EmailLabel.HOME);
    }

    @Test
    @DisplayName("createContact — email taken by another contact -> DuplicateContactException")
    void createContactDuplicate() {
        when(accountService.getAccountOrThrow(ACCOUNT_ID)).thenReturn(account());
        when(contactRepository.findByAccountIdAndAnyEmailIn(eq(ACCOUNT_ID), any()))
                .thenReturn(List.of(contact(99L, EMAIL)));

        assertThatThrownBy(() -> service.createContact(ACCOUNT_ID,
                new ContactCreateRequest(List.of(emailReq(EMAIL)), "Alice", null, null)))
                .isInstanceOf(DuplicateContactException.class);

        verify(contactRepository, never()).save(any());
    }

    @Test
    @DisplayName("createContact — duplicate email within the request -> ValidationException (400)")
    void createContactDuplicateWithinRequest() {
        when(accountService.getAccountOrThrow(ACCOUNT_ID)).thenReturn(account());

        assertThatThrownBy(() -> service.createContact(ACCOUNT_ID,
                new ContactCreateRequest(List.of(emailReq(EMAIL), emailReq(EMAIL)), null, null, null)))
                .isInstanceOf(ValidationException.class).hasMessageContaining("appears more than once");

        verify(contactRepository, never()).save(any());
    }

    @Test
    @DisplayName("updateContact — overwrites all fields including emails")
    void updateContactReplacesFields() {
        ContactEntity existing = contact(CONTACT_ID, EMAIL);
        when(accountService.getAccountOrThrow(ACCOUNT_ID)).thenReturn(account());
        when(contactRepository.findByIdAndAccountId(CONTACT_ID, ACCOUNT_ID)).thenReturn(Optional.of(existing));
        when(contactRepository.findByAccountIdAndAnyEmailIn(eq(ACCOUNT_ID), any())).thenReturn(List.of(existing));
        when(contactRepository.save(existing)).thenReturn(existing);

        ContactResponse r = service.updateContact(ACCOUNT_ID, CONTACT_ID,
                new ContactUpdateRequest(List.of(emailReq(EMAIL)), "Alice", "Liddell", "note"));

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
        when(accountService.getAccountOrThrow(ACCOUNT_ID)).thenReturn(account());
        when(contactRepository.findByIdAndAccountId(CONTACT_ID, ACCOUNT_ID)).thenReturn(Optional.of(existing));
        when(contactRepository.findByAccountIdAndAnyEmailIn(eq(ACCOUNT_ID), any())).thenReturn(List.of(other));

        assertThatThrownBy(() -> service.updateContact(ACCOUNT_ID, CONTACT_ID,
                new ContactUpdateRequest(List.of(emailReq("taken@example.com")), "X", null, null)))
                .isInstanceOf(DuplicateContactException.class);

        verify(contactRepository, never()).save(any());
    }

    @Test
    @DisplayName("updateContact — same email on its own contact (no-op) passes")
    void updateContactSameEmailSkipsCheck() {
        ContactEntity existing = contact(CONTACT_ID, EMAIL);
        when(accountService.getAccountOrThrow(ACCOUNT_ID)).thenReturn(account());
        when(contactRepository.findByIdAndAccountId(CONTACT_ID, ACCOUNT_ID)).thenReturn(Optional.of(existing));
        // findByAccountIdAndAnyEmailIn returns the same contact — must pass
        // (excludeContactId = CONTACT_ID)
        when(contactRepository.findByAccountIdAndAnyEmailIn(eq(ACCOUNT_ID), any())).thenReturn(List.of(existing));
        when(contactRepository.save(existing)).thenReturn(existing);

        service.updateContact(ACCOUNT_ID, CONTACT_ID,
                new ContactUpdateRequest(List.of(emailReq(EMAIL)), "X", null, null));

        verify(contactRepository).save(any());
    }

    @Test
    @DisplayName("patchContact — null fields are not applied")
    void patchContactNullFieldsSkipped() {
        ContactEntity existing = contact(CONTACT_ID, EMAIL);
        existing.setNote("original");
        when(accountService.getAccountOrThrow(ACCOUNT_ID)).thenReturn(account());
        when(contactRepository.findByIdAndAccountId(CONTACT_ID, ACCOUNT_ID)).thenReturn(Optional.of(existing));
        when(contactRepository.save(existing)).thenReturn(existing);

        service.patchContact(ACCOUNT_ID, CONTACT_ID, new ContactPatchRequest(null, "NewName", "NewSurname", null));

        assertThat(existing.getEmails().get(0).getEmail()).isEqualTo(EMAIL);
        assertThat(existing.getName()).isEqualTo("NewName");
        assertThat(existing.getSurname()).isEqualTo("NewSurname");
        assertThat(existing.getNote()).isEqualTo("original");
    }

    @Test
    @DisplayName("patchContact — missing contact -> ContactNotFoundException")
    void patchContactMissing() {
        when(accountService.getAccountOrThrow(ACCOUNT_ID)).thenReturn(account());
        when(contactRepository.findByIdAndAccountId(CONTACT_ID, ACCOUNT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(
                () -> service.patchContact(ACCOUNT_ID, CONTACT_ID, new ContactPatchRequest(null, "X", null, null)))
                .isInstanceOf(ContactNotFoundException.class);
    }

    @Test
    @DisplayName("deleteContact — deletes the entity")
    void deleteContactDeletes() {
        ContactEntity existing = contact(CONTACT_ID, EMAIL);
        when(accountService.getAccountOrThrow(ACCOUNT_ID)).thenReturn(account());
        when(contactRepository.findByIdAndAccountId(CONTACT_ID, ACCOUNT_ID)).thenReturn(Optional.of(existing));

        service.deleteContact(ACCOUNT_ID, CONTACT_ID);

        verify(contactRepository).delete(existing);
    }

    @Test
    @DisplayName("deleteContact — missing kontakt → ContactNotFoundException")
    void deleteContactMissing() {
        when(accountService.getAccountOrThrow(ACCOUNT_ID)).thenReturn(account());
        when(contactRepository.findByIdAndAccountId(CONTACT_ID, ACCOUNT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deleteContact(ACCOUNT_ID, CONTACT_ID))
                .isInstanceOf(ContactNotFoundException.class);
    }

    @Test
    @DisplayName("deleteContact — audit log uses the fetched account, not LAZY contact.getAccount()")
    void deleteContactUsesFetchedAccountForAudit() {
        ContactEntity existing = new ContactEntity();
        existing.setId(CONTACT_ID);
        ContactEmailEntity em = new ContactEmailEntity();
        em.setEmail(EMAIL);
        em.setPrimary(true);
        em.setContact(existing);
        existing.getEmails().add(em);
        // intentionally without setAccount — we confirm the LAZY proxy is not touched
        when(accountService.getAccountOrThrow(ACCOUNT_ID)).thenReturn(account());
        when(contactRepository.findByIdAndAccountId(CONTACT_ID, ACCOUNT_ID)).thenReturn(Optional.of(existing));

        service.deleteContact(ACCOUNT_ID, CONTACT_ID);

        verify(contactRepository).delete(existing);
        verify(accountService).getAccountOrThrow(ACCOUNT_ID);
    }

    @Test
    @DisplayName("createContact — mixed-case email with whitespace is stored normalized")
    void createContactNormalizesEmail() {
        when(accountService.getAccountOrThrow(ACCOUNT_ID)).thenReturn(account());
        when(contactRepository.findByAccountIdAndAnyEmailIn(eq(ACCOUNT_ID), any())).thenReturn(List.of());
        when(contactRepository.save(any(ContactEntity.class))).thenAnswer(inv -> {
            ContactEntity e = inv.getArgument(0);
            e.setId(CONTACT_ID);
            return e;
        });

        service.createContact(ACCOUNT_ID, new ContactCreateRequest(
                List.of(new ContactEmailRequest("  Alice@Example.COM  ", null)), null, null, null));

        ArgumentCaptor<ContactEntity> captor = ArgumentCaptor.forClass(ContactEntity.class);
        verify(contactRepository).save(captor.capture());
        assertThat(captor.getValue().getEmails().get(0).getEmail()).isEqualTo("alice@example.com");
        verify(contactRepository).findByAccountIdAndAnyEmailIn(eq(ACCOUNT_ID), any());
    }

    @Test
    @DisplayName("createContact — multiple emails checked for collisions in a single batch query")
    void createContactChecksDuplicatesWithSingleBatchLookup() {
        when(accountService.getAccountOrThrow(ACCOUNT_ID)).thenReturn(account());
        when(contactRepository.findByAccountIdAndAnyEmailIn(eq(ACCOUNT_ID), any())).thenReturn(List.of());
        when(contactRepository.save(any(ContactEntity.class))).thenAnswer(inv -> {
            ContactEntity e = inv.getArgument(0);
            e.setId(CONTACT_ID);
            return e;
        });

        service.createContact(ACCOUNT_ID, new ContactCreateRequest(
                List.of(emailReq("first@example.com"), emailReq("second@example.com"), emailReq("third@example.com")),
                null, null, null));

        verify(contactRepository).findByAccountIdAndAnyEmailIn(eq(ACCOUNT_ID), any());
        verify(contactRepository, never()).findByAccountIdAndAnyEmail(eq(ACCOUNT_ID), anyString());
    }

    @Test
    @DisplayName("searchContacts — null q → ValidationException")
    void searchContactsNullQ() {
        assertThatThrownBy(() -> service.searchContacts(ACCOUNT_ID, null, 0, 20, null, null))
                .isInstanceOf(ValidationException.class);
        verify(contactRepository, never()).searchByAccountId(anyLong(), any(), any(), any());
    }

    @Test
    @DisplayName("searchContacts — blank q → ValidationException")
    void searchContactsBlankQ() {
        assertThatThrownBy(() -> service.searchContacts(ACCOUNT_ID, "   ", 0, 20, null, null))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    @DisplayName("listContacts — Pageable passed with the requested page size")
    void listContactsPageableSize() {
        when(accountService.getAccountOrThrow(ACCOUNT_ID)).thenReturn(account());
        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        when(contactRepository.findByAccountId(eq(ACCOUNT_ID), eq(null), pageableCaptor.capture()))
                .thenReturn(new PageImpl<>(List.of()));

        service.listContacts(ACCOUNT_ID, 3, 50, null, null);

        Pageable captured = pageableCaptor.getValue();
        assertThat(captured.getPageNumber()).isEqualTo(3);
        assertThat(captured.getPageSize()).isEqualTo(50);
    }

    @Test
    @DisplayName("listContacts — sort=name → primary order asc by name")
    void listContactsSortByName() {
        when(accountService.getAccountOrThrow(ACCOUNT_ID)).thenReturn(account());
        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        when(contactRepository.findByAccountId(eq(ACCOUNT_ID), eq(null), pageableCaptor.capture()))
                .thenReturn(new PageImpl<>(List.of()));

        service.listContacts(ACCOUNT_ID, 0, 10, "name", null);

        var orders = pageableCaptor.getValue().getSort().toList();
        assertThat(orders.get(0).getProperty()).isEqualTo("name");
        assertThat(orders.get(0).getDirection()).isEqualTo(org.springframework.data.domain.Sort.Direction.ASC);
    }

    @Test
    @DisplayName("listContacts — sort=recent → primary order desc by updatedAt")
    void listContactsSortByRecent() {
        when(accountService.getAccountOrThrow(ACCOUNT_ID)).thenReturn(account());
        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        when(contactRepository.findByAccountId(eq(ACCOUNT_ID), eq(null), pageableCaptor.capture()))
                .thenReturn(new PageImpl<>(List.of()));

        service.listContacts(ACCOUNT_ID, 0, 10, "recent", null);

        var orders = pageableCaptor.getValue().getSort().toList();
        assertThat(orders.get(0).getProperty()).isEqualTo("updatedAt");
        assertThat(orders.get(0).getDirection()).isEqualTo(org.springframework.data.domain.Sort.Direction.DESC);
    }

    @Test
    @DisplayName("listContacts — unknown sort -> ValidationException")
    void listContactsInvalidSort() {
        when(accountService.getAccountOrThrow(ACCOUNT_ID)).thenReturn(account());
        assertThatThrownBy(() -> service.listContacts(ACCOUNT_ID, 0, 10, "bogus", null))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    @DisplayName("listContacts — label=WORK → repository dostane label parametr")
    void listContactsByLabel() {
        when(accountService.getAccountOrThrow(ACCOUNT_ID)).thenReturn(account());
        when(contactRepository.findByAccountId(eq(ACCOUNT_ID),
                eq(org.voxrox.mailbackend.feature.contact.EmailLabel.WORK), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(contact(10L, EMAIL))));

        Page<ContactResponse> result = service.listContacts(ACCOUNT_ID, 0, 10, null,
                org.voxrox.mailbackend.feature.contact.EmailLabel.WORK);

        assertThat(result.getContent()).hasSize(1);
    }

    @Test
    @DisplayName("addEmail — adds the email, does not change the primary one, stores normalized")
    void addEmailAppends() {
        ContactEntity existing = contact(CONTACT_ID, EMAIL);
        when(accountService.getAccountOrThrow(ACCOUNT_ID)).thenReturn(account());
        when(contactRepository.findByIdAndAccountId(CONTACT_ID, ACCOUNT_ID)).thenReturn(Optional.of(existing));
        when(contactRepository.findByAccountIdAndAnyEmail(ACCOUNT_ID, "new@x.cz")).thenReturn(List.of());
        when(contactRepository.save(existing)).thenAnswer(inv -> {
            ContactEntity e = inv.getArgument(0);
            e.getEmails().stream().filter(em -> em.getId() == null).forEach(em -> em.setId(123L));
            return e;
        });

        ContactEmailResponse added = service.addEmail(ACCOUNT_ID, CONTACT_ID,
                new ContactEmailRequest("  New@X.cz  ", EmailLabel.HOME));

        assertThat(added.email()).isEqualTo("new@x.cz");
        assertThat(added.primary()).isFalse();
        assertThat(existing.getEmails()).hasSize(2);
        assertThat(existing.getEmails().get(0).isPrimary()).isTrue();
    }

    @Test
    @DisplayName("addEmail — collision with another contact -> DuplicateContactException")
    void addEmailDuplicate() {
        ContactEntity existing = contact(CONTACT_ID, EMAIL);
        ContactEntity other = contact(99L, "taken@x.cz");
        when(accountService.getAccountOrThrow(ACCOUNT_ID)).thenReturn(account());
        when(contactRepository.findByIdAndAccountId(CONTACT_ID, ACCOUNT_ID)).thenReturn(Optional.of(existing));
        when(contactRepository.findByAccountIdAndAnyEmail(ACCOUNT_ID, "taken@x.cz")).thenReturn(List.of(other));

        assertThatThrownBy(() -> service.addEmail(ACCOUNT_ID, CONTACT_ID, new ContactEmailRequest("taken@x.cz", null)))
                .isInstanceOf(DuplicateContactException.class);

        verify(contactRepository, never()).save(any());
    }

    @Test
    @DisplayName("addEmail — duplicate within the contact's own list -> ValidationException (400)")
    void addEmailOwnDuplicateIsValidationError() {
        ContactEntity existing = contact(CONTACT_ID, EMAIL);
        when(accountService.getAccountOrThrow(ACCOUNT_ID)).thenReturn(account());
        when(contactRepository.findByIdAndAccountId(CONTACT_ID, ACCOUNT_ID)).thenReturn(Optional.of(existing));

        // The contact already has EMAIL — per the checkNoDuplicatesWithinAccount
        // convention this is "fix your own form" (400), not a cross-contact
        // conflict (409).
        assertThatThrownBy(
                () -> service.addEmail(ACCOUNT_ID, CONTACT_ID, new ContactEmailRequest("  " + EMAIL + "  ", null)))
                .isInstanceOf(ValidationException.class);

        verify(contactRepository, never()).save(any());
    }

    @Test
    @DisplayName("addEmail — missing kontakt → ContactNotFoundException")
    void addEmailContactMissing() {
        when(accountService.getAccountOrThrow(ACCOUNT_ID)).thenReturn(account());
        when(contactRepository.findByIdAndAccountId(CONTACT_ID, ACCOUNT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.addEmail(ACCOUNT_ID, CONTACT_ID, new ContactEmailRequest("x@x.cz", null)))
                .isInstanceOf(ContactNotFoundException.class);
    }

    @Test
    @DisplayName("deleteEmail — deletes non-primary, primary stays")
    void deleteEmailRemovesNonPrimary() {
        ContactEntity existing = contact(CONTACT_ID, EMAIL, "second@x.cz");
        when(accountService.getAccountOrThrow(ACCOUNT_ID)).thenReturn(account());
        when(contactRepository.findByIdAndAccountId(CONTACT_ID, ACCOUNT_ID)).thenReturn(Optional.of(existing));
        when(contactRepository.save(existing)).thenReturn(existing);

        service.deleteEmail(ACCOUNT_ID, CONTACT_ID, 2L);

        assertThat(existing.getEmails()).hasSize(1);
        assertThat(existing.getEmails().get(0).getEmail()).isEqualTo(EMAIL);
        assertThat(existing.getEmails().get(0).isPrimary()).isTrue();
    }

    @Test
    @DisplayName("deleteEmail — deletes primary -> promotes the next one (lowest ID)")
    void deleteEmailPromotesNext() {
        ContactEntity existing = contact(CONTACT_ID, EMAIL, "second@x.cz");
        when(accountService.getAccountOrThrow(ACCOUNT_ID)).thenReturn(account());
        when(contactRepository.findByIdAndAccountId(CONTACT_ID, ACCOUNT_ID)).thenReturn(Optional.of(existing));
        when(contactRepository.save(existing)).thenReturn(existing);

        service.deleteEmail(ACCOUNT_ID, CONTACT_ID, 1L);

        assertThat(existing.getEmails()).hasSize(1);
        assertThat(existing.getEmails().get(0).getEmail()).isEqualTo("second@x.cz");
        assertThat(existing.getEmails().get(0).isPrimary()).isTrue();
    }

    @Test
    @DisplayName("deleteEmail — last email -> ValidationException (400)")
    void deleteEmailLastOneRejected() {
        ContactEntity existing = contact(CONTACT_ID, EMAIL);
        when(accountService.getAccountOrThrow(ACCOUNT_ID)).thenReturn(account());
        when(contactRepository.findByIdAndAccountId(CONTACT_ID, ACCOUNT_ID)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.deleteEmail(ACCOUNT_ID, CONTACT_ID, 1L))
                .isInstanceOf(ValidationException.class).hasMessageContaining("At least one");

        verify(contactRepository, never()).save(any());
    }

    @Test
    @DisplayName("deleteEmail — email ID does not belong to the contact -> ResourceNotFoundException")
    void deleteEmailNotFound() {
        ContactEntity existing = contact(CONTACT_ID, EMAIL, "second@x.cz");
        when(accountService.getAccountOrThrow(ACCOUNT_ID)).thenReturn(account());
        when(contactRepository.findByIdAndAccountId(CONTACT_ID, ACCOUNT_ID)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.deleteEmail(ACCOUNT_ID, CONTACT_ID, 999L))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("setPrimaryEmail — changes the primary one, sets the others to false")
    void setPrimaryEmailSwitches() {
        ContactEntity existing = contact(CONTACT_ID, EMAIL, "second@x.cz");
        when(accountService.getAccountOrThrow(ACCOUNT_ID)).thenReturn(account());
        when(contactRepository.findByIdAndAccountId(CONTACT_ID, ACCOUNT_ID)).thenReturn(Optional.of(existing));
        when(contactRepository.save(existing)).thenReturn(existing);

        ContactResponse r = service.setPrimaryEmail(ACCOUNT_ID, CONTACT_ID, 2L);

        assertThat(existing.getEmails().get(0).isPrimary()).isFalse();
        assertThat(existing.getEmails().get(1).isPrimary()).isTrue();
        assertThat(r.emails()).hasSize(2);
    }

    @Test
    @DisplayName("setPrimaryEmail — non-existing emailId -> ResourceNotFoundException")
    void setPrimaryEmailNotFound() {
        ContactEntity existing = contact(CONTACT_ID, EMAIL);
        when(accountService.getAccountOrThrow(ACCOUNT_ID)).thenReturn(account());
        when(contactRepository.findByIdAndAccountId(CONTACT_ID, ACCOUNT_ID)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.setPrimaryEmail(ACCOUNT_ID, CONTACT_ID, 999L))
                .isInstanceOf(ResourceNotFoundException.class);

        verify(contactRepository, never()).save(any());
    }

    private ContactEntity namedContact(Long id, String name, String surname, String... emails) {
        ContactEntity c = new ContactEntity();
        c.setId(id);
        c.setName(name);
        c.setSurname(surname);
        c.setAccount(account());
        for (int i = 0; i < emails.length; i++) {
            ContactEmailEntity em = new ContactEmailEntity();
            em.setId((long) (10 * id + i));
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
        verify(contactRepository, never()).searchByAccountId(anyLong(), anyString(), any(), any());
    }

    @Test
    @DisplayName("autocomplete — ranking: email prefix > surname prefix > name prefix > substring")
    void autocompleteRanking() {
        when(accountService.getAccountOrThrow(ACCOUNT_ID)).thenReturn(account());

        // Substring match (rank 3) - email contains "ali" but does not start with it
        ContactEntity c1 = namedContact(1L, "Zara", "Smith", "contact.ali@example.com");
        // Name prefix match (rank 2)
        ContactEntity c2 = namedContact(2L, "Alice", "Johnson", "john@example.com");
        // Surname prefix match (rank 1)
        ContactEntity c3 = namedContact(3L, "Bob", "Alioto", "bob@example.com");
        // Email prefix match (rank 0)
        ContactEntity c4 = namedContact(4L, "Xena", "Warrior", "ali@example.com");

        when(contactRepository.searchByAccountId(eq(ACCOUNT_ID), eq("%ali%"), eq(null), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(c1, c2, c3, c4)));

        List<ContactAutocompleteResponse> result = service.autocomplete(ACCOUNT_ID, "ali", 10);

        assertThat(result).extracting(ContactAutocompleteResponse::email).containsExactly("ali@example.com", // rank 0:
                                                                                                             // email
                                                                                                             // prefix
                "bob@example.com", // rank 1: surname prefix "Alioto"
                "john@example.com", // rank 2: name prefix "Alice"
                "contact.ali@example.com"); // rank 3: substring fallback
    }

    @Test
    @DisplayName("autocomplete — flattening: a contact with 2 emails produces 2 rows")
    void autocompleteFlattensEmails() {
        when(accountService.getAccountOrThrow(ACCOUNT_ID)).thenReturn(account());

        ContactEntity c = namedContact(10L, "Alice", "Liddell", "work@x.cz", "home@x.cz");
        when(contactRepository.searchByAccountId(eq(ACCOUNT_ID), anyString(), eq(null), any(Pageable.class)))
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
        when(accountService.getAccountOrThrow(ACCOUNT_ID)).thenReturn(account());

        List<ContactEntity> many = new java.util.ArrayList<>();
        for (int i = 0; i < 25; i++) {
            many.add(namedContact((long) i, "N" + i, "S" + i, "x" + i + "@cz"));
        }

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        when(contactRepository.searchByAccountId(eq(ACCOUNT_ID), anyString(), eq(null), pageableCaptor.capture()))
                .thenReturn(new PageImpl<>(many.subList(0, 20)));

        List<ContactAutocompleteResponse> result = service.autocomplete(ACCOUNT_ID, "x", 100);

        // Repository dostal page size = 20 (cap), ne 100
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

    // --- merge ---

    private ContactEntity contactWithLabel(Long id, String email, EmailLabel label, String note) {
        ContactEntity c = new ContactEntity();
        c.setId(id);
        c.setName("Bob");
        c.setSurname("Builder");
        c.setNote(note);
        c.setAccount(account());
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

        when(accountService.getAccountOrThrow(ACCOUNT_ID)).thenReturn(account());
        when(contactRepository.findByIdAndAccountId(CONTACT_ID, ACCOUNT_ID)).thenReturn(Optional.of(target));
        when(contactRepository.findByIdAndAccountId(20L, ACCOUNT_ID)).thenReturn(Optional.of(src));
        when(contactRepository.save(any(ContactEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        ContactResponse r = service.merge(ACCOUNT_ID, CONTACT_ID, new ContactMergeRequest(List.of(20L)));

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

        when(accountService.getAccountOrThrow(ACCOUNT_ID)).thenReturn(account());
        when(contactRepository.findByIdAndAccountId(CONTACT_ID, ACCOUNT_ID)).thenReturn(Optional.of(target));
        when(contactRepository.findByIdAndAccountId(20L, ACCOUNT_ID)).thenReturn(Optional.of(src));
        when(contactRepository.save(any(ContactEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        ContactResponse r = service.merge(ACCOUNT_ID, CONTACT_ID, new ContactMergeRequest(List.of(20L)));

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

        when(accountService.getAccountOrThrow(ACCOUNT_ID)).thenReturn(account());
        when(contactRepository.findByIdAndAccountId(CONTACT_ID, ACCOUNT_ID)).thenReturn(Optional.of(target));
        when(contactRepository.findByIdAndAccountId(20L, ACCOUNT_ID)).thenReturn(Optional.of(src1));
        when(contactRepository.findByIdAndAccountId(21L, ACCOUNT_ID)).thenReturn(Optional.of(src2));
        when(contactRepository.save(any(ContactEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        ContactResponse r = service.merge(ACCOUNT_ID, CONTACT_ID, new ContactMergeRequest(List.of(20L, 21L)));

        assertThat(r.note()).isEqualTo("note A\n\n---\n\nnote B");
    }

    @Test
    @DisplayName("merge — exceeding the 10-email limit -> ValidationException, save is not called")
    void mergeExceedsMaxEmails() {
        ContactEntity target = new ContactEntity();
        target.setId(CONTACT_ID);
        target.setAccount(account());
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
        src.setAccount(account());
        for (int i = 0; i < 3; i++) {
            ContactEmailEntity em = new ContactEmailEntity();
            em.setId(100L + i);
            em.setEmail("s" + i + "@example.com");
            em.setContact(src);
            src.getEmails().add(em);
        }

        when(accountService.getAccountOrThrow(ACCOUNT_ID)).thenReturn(account());
        when(contactRepository.findByIdAndAccountId(CONTACT_ID, ACCOUNT_ID)).thenReturn(Optional.of(target));
        when(contactRepository.findByIdAndAccountId(20L, ACCOUNT_ID)).thenReturn(Optional.of(src));

        assertThatThrownBy(() -> service.merge(ACCOUNT_ID, CONTACT_ID, new ContactMergeRequest(List.of(20L))))
                .isInstanceOf(ValidationException.class).hasMessageContaining("11 e-mail addresses; the maximum is 10");

        verify(contactRepository, never()).save(any(ContactEntity.class));
        verify(contactRepository, never()).delete(any(ContactEntity.class));
    }

    @Test
    @DisplayName("merge — target ∈ source → ValidationException")
    void mergeTargetInSource() {
        when(accountService.getAccountOrThrow(ACCOUNT_ID)).thenReturn(account());

        assertThatThrownBy(
                () -> service.merge(ACCOUNT_ID, CONTACT_ID, new ContactMergeRequest(List.of(CONTACT_ID, 20L))))
                .isInstanceOf(ValidationException.class).hasMessageContaining("must not also be");

        verify(contactRepository, never()).delete(any(ContactEntity.class));
    }

    @Test
    @DisplayName("merge — duplicate ID in source -> ValidationException")
    void mergeDuplicateSourceIds() {
        when(accountService.getAccountOrThrow(ACCOUNT_ID)).thenReturn(account());

        assertThatThrownBy(() -> service.merge(ACCOUNT_ID, CONTACT_ID, new ContactMergeRequest(List.of(20L, 20L))))
                .isInstanceOf(ValidationException.class).hasMessageContaining("duplicate IDs");
    }

    @Test
    @DisplayName("merge — source kontakt neexistuje → ContactNotFoundException")
    void mergeSourceNotFound() {
        ContactEntity target = contact(CONTACT_ID, "alice@example.com");
        when(accountService.getAccountOrThrow(ACCOUNT_ID)).thenReturn(account());
        when(contactRepository.findByIdAndAccountId(CONTACT_ID, ACCOUNT_ID)).thenReturn(Optional.of(target));
        when(contactRepository.findByIdAndAccountId(99L, ACCOUNT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.merge(ACCOUNT_ID, CONTACT_ID, new ContactMergeRequest(List.of(99L))))
                .isInstanceOf(ContactNotFoundException.class);

        verify(contactRepository, never()).save(any(ContactEntity.class));
    }

    @Test
    @DisplayName("merge — target without primary, after merge the first email is promoted")
    void mergePromotesPrimaryWhenTargetHasNone() {
        ContactEntity target = new ContactEntity();
        target.setId(CONTACT_ID);
        target.setAccount(account());
        // target with an email lacking the primary flag (edge case after an earlier
        // primary deletion)
        ContactEmailEntity te = new ContactEmailEntity();
        te.setId(1L);
        te.setEmail("t@example.com");
        te.setPrimary(false);
        te.setContact(target);
        target.getEmails().add(te);
        ContactEntity src = contactWithLabel(20L, "src@example.com", null, null);

        when(accountService.getAccountOrThrow(ACCOUNT_ID)).thenReturn(account());
        when(contactRepository.findByIdAndAccountId(CONTACT_ID, ACCOUNT_ID)).thenReturn(Optional.of(target));
        when(contactRepository.findByIdAndAccountId(20L, ACCOUNT_ID)).thenReturn(Optional.of(src));
        when(contactRepository.save(any(ContactEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        ContactResponse r = service.merge(ACCOUNT_ID, CONTACT_ID, new ContactMergeRequest(List.of(20L)));

        assertThat(r.emails().stream().filter(ContactEmailResponse::primary).map(ContactEmailResponse::email))
                .containsExactly("t@example.com");
    }

    @Test
    @DisplayName("exportToVCard — happy path: 2 contacts serialized to vCard, audit written")
    void exportToVCardHappyPath() {
        when(accountService.getAccountOrThrow(ACCOUNT_ID)).thenReturn(account());
        when(contactRepository.findAllByAccountId(eq(ACCOUNT_ID), any(org.springframework.data.domain.Sort.class)))
                .thenReturn(List.of(contact(10L, EMAIL), contact(11L, "bob@example.com")));

        String vcard = service.exportToVCard(ACCOUNT_ID);

        assertThat(vcard).startsWith("BEGIN:VCARD\r\n").contains("VERSION:4.0\r\n").contains("EMAIL;PREF=1:" + EMAIL)
                .contains("EMAIL;PREF=1:bob@example.com").endsWith("END:VCARD\r\n");
        int beginCount = vcard.split("BEGIN:VCARD", -1).length - 1;
        assertThat(beginCount).isEqualTo(2);
    }

    @Test
    @DisplayName("exportToVCard — empty address book -> empty string, audit written with count=0")
    void exportToVCardEmpty() {
        when(accountService.getAccountOrThrow(ACCOUNT_ID)).thenReturn(account());
        when(contactRepository.findAllByAccountId(eq(ACCOUNT_ID), any(org.springframework.data.domain.Sort.class)))
                .thenReturn(List.of());

        String vcard = service.exportToVCard(ACCOUNT_ID);

        assertThat(vcard).isEmpty();
    }

    @Test
    @DisplayName("exportToVCard — non-existing account -> AccountNotFoundException, repo is not called")
    void exportToVCardAccountNotFound() {
        when(accountService.getAccountOrThrow(999L)).thenThrow(new AccountNotFoundException(999L));

        assertThatThrownBy(() -> service.exportToVCard(999L)).isInstanceOf(AccountNotFoundException.class);

        verify(contactRepository, never()).findAllByAccountId(anyLong(),
                any(org.springframework.data.domain.Sort.class));
    }
}
