package org.voxrox.mailbackend.feature.contact.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.voxrox.mailbackend.exception.AccountNotFoundException;
import org.voxrox.mailbackend.exception.ContactNotFoundException;
import org.voxrox.mailbackend.exception.DuplicateContactException;
import org.voxrox.mailbackend.exception.ValidationException;
import org.voxrox.mailbackend.feature.account.entity.AccountEntity;
import org.voxrox.mailbackend.feature.account.service.AccountService;
import org.voxrox.mailbackend.feature.contact.dto.BulkContactCreateRequest;
import org.voxrox.mailbackend.feature.contact.dto.BulkContactCreateResponse;
import org.voxrox.mailbackend.feature.contact.dto.BulkContactCreateResponse.BulkContactCreateResult;
import org.voxrox.mailbackend.feature.contact.dto.BulkContactDeleteRequest;
import org.voxrox.mailbackend.feature.contact.dto.BulkContactDeleteResponse;
import org.voxrox.mailbackend.feature.contact.dto.BulkContactDeleteResponse.BulkContactDeleteResult;
import org.voxrox.mailbackend.feature.contact.dto.ContactCreateRequest;
import org.voxrox.mailbackend.feature.contact.dto.ContactEmailRequest;
import org.voxrox.mailbackend.feature.contact.dto.ContactResponse;
import org.voxrox.mailbackend.feature.contact.entity.ContactEmailEntity;
import org.voxrox.mailbackend.feature.contact.entity.ContactEntity;
import org.voxrox.mailbackend.feature.contact.mapper.ContactMapper;

/**
 * Unit tests for {@link ContactBulkService}.
 * <p>
 * Bulk operations run as an iteration of single-item calls over
 * {@link ContactService} — tests verify that:
 * <ul>
 * <li>account existence is verified once upfront (fail-fast)</li>
 * <li>per-item failures ({@link DuplicateContactException},
 * {@link ContactNotFoundException}, {@link ValidationException}) do not stop
 * the iteration</li>
 * <li>the response consistently summarizes total / created (deleted) /
 * failed</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class ContactBulkServiceTest {

    private static final Long ACCOUNT_ID = 1L;

    @Mock
    private AccountService accountService;
    @Mock
    private ContactService contactService;

    private final ContactMapper contactMapper = new ContactMapper();
    private ContactBulkService bulkService;

    @BeforeEach
    void setUp() {
        bulkService = new ContactBulkService(accountService, contactService);
    }

    private AccountEntity account() {
        AccountEntity a = new AccountEntity();
        a.setId(ACCOUNT_ID);
        a.setEmail("owner@example.com");
        return a;
    }

    private ContactEntity contact(Long id, String email) {
        ContactEntity c = new ContactEntity();
        c.setId(id);
        c.setName("Alice");
        c.setSurname("Liddell");
        c.setAccount(account());
        ContactEmailEntity em = new ContactEmailEntity();
        em.setId(id * 10);
        em.setEmail(email);
        em.setPrimary(true);
        em.setContact(c);
        c.getEmails().add(em);
        return c;
    }

    private ContactEmailRequest emailReq(String email) {
        return new ContactEmailRequest(email, null);
    }

    @Test
    @DisplayName("bulkCreate — all items successful -> response summarizes total=3, created=3")
    void bulkCreateAllSuccess() {
        when(accountService.getAccountOrThrow(ACCOUNT_ID)).thenReturn(account());

        ContactCreateRequest r1 = new ContactCreateRequest(List.of(emailReq("a@x.cz")), "A", null, null);
        ContactCreateRequest r2 = new ContactCreateRequest(List.of(emailReq("b@x.cz")), "B", null, null);
        ContactCreateRequest r3 = new ContactCreateRequest(List.of(emailReq("c@x.cz")), "C", null, null);

        ContactResponse c1 = contactMapper.toResponse(contact(1L, "a@x.cz"));
        ContactResponse c2 = contactMapper.toResponse(contact(2L, "b@x.cz"));
        ContactResponse c3 = contactMapper.toResponse(contact(3L, "c@x.cz"));

        when(contactService.createContact(ACCOUNT_ID, r1)).thenReturn(c1);
        when(contactService.createContact(ACCOUNT_ID, r2)).thenReturn(c2);
        when(contactService.createContact(ACCOUNT_ID, r3)).thenReturn(c3);

        BulkContactCreateResponse response = bulkService.bulkCreate(ACCOUNT_ID,
                new BulkContactCreateRequest(List.of(r1, r2, r3)));

        assertThat(response.total()).isEqualTo(3);
        assertThat(response.created()).isEqualTo(3);
        assertThat(response.failed()).isZero();
        assertThat(response.results()).allMatch(r -> r.status() == BulkContactCreateResult.Status.CREATED);
        assertThat(response.results().get(0).index()).isZero();
        assertThat(response.results().get(0).contact()).isEqualTo(c1);
    }

    @Test
    @DisplayName("bulkCreate — mixed results: a duplicate in the middle does not interrupt the rest")
    void bulkCreatePartialFailure() {
        when(accountService.getAccountOrThrow(ACCOUNT_ID)).thenReturn(account());

        ContactCreateRequest r1 = new ContactCreateRequest(List.of(emailReq("a@x.cz")), "A", null, null);
        ContactCreateRequest r2 = new ContactCreateRequest(List.of(emailReq("dup@x.cz")), "B", null, null);
        ContactCreateRequest r3 = new ContactCreateRequest(List.of(emailReq("c@x.cz")), "C", null, null);

        ContactResponse c1 = contactMapper.toResponse(contact(1L, "a@x.cz"));
        ContactResponse c3 = contactMapper.toResponse(contact(3L, "c@x.cz"));

        when(contactService.createContact(ACCOUNT_ID, r1)).thenReturn(c1);
        when(contactService.createContact(ACCOUNT_ID, r2))
                .thenThrow(new DuplicateContactException(ACCOUNT_ID, "dup@x.cz"));
        when(contactService.createContact(ACCOUNT_ID, r3)).thenReturn(c3);

        BulkContactCreateResponse response = bulkService.bulkCreate(ACCOUNT_ID,
                new BulkContactCreateRequest(List.of(r1, r2, r3)));

        assertThat(response.total()).isEqualTo(3);
        assertThat(response.created()).isEqualTo(2);
        assertThat(response.failed()).isEqualTo(1);

        BulkContactCreateResult failed = response.results().get(1);
        assertThat(failed.status()).isEqualTo(BulkContactCreateResult.Status.FAILED);
        assertThat(failed.errorCode()).isEqualTo("CONTACT_DUPLICATE");
        assertThat(failed.contact()).isNull();
        // The per-item message is the exception's internal (log-bound) text, so
        // the address is masked (log hygiene re-audit 2026-07-10); the item is
        // identified by index + errorCode.
        assertThat(failed.errorMessage()).contains("d***p@x.cz").doesNotContain("dup@x.cz");
    }

    @Test
    @DisplayName("bulkCreate — ValidationException (e.g. in-request duplicate) propagates as FAILED")
    void bulkCreateValidationFailure() {
        when(accountService.getAccountOrThrow(ACCOUNT_ID)).thenReturn(account());

        ContactCreateRequest r1 = new ContactCreateRequest(List.of(emailReq("a@x.cz"), emailReq("a@x.cz")), "A", null,
                null);

        when(contactService.createContact(ACCOUNT_ID, r1))
                .thenThrow(new ValidationException("Email a@x.cz is listed multiple times."));

        BulkContactCreateResponse response = bulkService.bulkCreate(ACCOUNT_ID,
                new BulkContactCreateRequest(List.of(r1)));

        assertThat(response.failed()).isEqualTo(1);
        assertThat(response.results().get(0).errorCode()).isEqualTo("VALIDATION_ERROR");
    }

    @Test
    @DisplayName("bulkCreate — non-existing account -> AccountNotFoundException fail-fast without iteration")
    void bulkCreateAccountNotFound() {
        when(accountService.getAccountOrThrow(999L)).thenThrow(new AccountNotFoundException(999L));

        assertThatThrownBy(() -> bulkService.bulkCreate(999L,
                new BulkContactCreateRequest(
                        List.of(new ContactCreateRequest(List.of(emailReq("a@x.cz")), "A", null, null)))))
                .isInstanceOf(AccountNotFoundException.class);

        verify(contactService, never()).createContact(anyLong(), any());
    }

    @Test
    @DisplayName("bulkDelete — all IDs successfully deleted")
    void bulkDeleteAllSuccess() {
        when(accountService.getAccountOrThrow(ACCOUNT_ID)).thenReturn(account());

        BulkContactDeleteResponse response = bulkService.bulkDelete(ACCOUNT_ID,
                new BulkContactDeleteRequest(List.of(10L, 11L, 12L)));

        assertThat(response.total()).isEqualTo(3);
        assertThat(response.deleted()).isEqualTo(3);
        assertThat(response.failed()).isZero();
        assertThat(response.results()).allMatch(r -> r.status() == BulkContactDeleteResult.Status.DELETED);

        verify(contactService).deleteContact(ACCOUNT_ID, 10L);
        verify(contactService).deleteContact(ACCOUNT_ID, 11L);
        verify(contactService).deleteContact(ACCOUNT_ID, 12L);
    }

    @Test
    @DisplayName("bulkDelete — mixed results: missing IDs are returned as FAILED / CONTACT_NOT_FOUND")
    void bulkDeletePartialFailure() {
        when(accountService.getAccountOrThrow(ACCOUNT_ID)).thenReturn(account());

        org.mockito.Mockito.doNothing().when(contactService).deleteContact(ACCOUNT_ID, 10L);
        org.mockito.Mockito.doThrow(new ContactNotFoundException(ACCOUNT_ID, 999L)).when(contactService)
                .deleteContact(ACCOUNT_ID, 999L);
        org.mockito.Mockito.doNothing().when(contactService).deleteContact(ACCOUNT_ID, 12L);

        BulkContactDeleteResponse response = bulkService.bulkDelete(ACCOUNT_ID,
                new BulkContactDeleteRequest(List.of(10L, 999L, 12L)));

        assertThat(response.total()).isEqualTo(3);
        assertThat(response.deleted()).isEqualTo(2);
        assertThat(response.failed()).isEqualTo(1);

        BulkContactDeleteResult failed = response.results().get(1);
        assertThat(failed.id()).isEqualTo(999L);
        assertThat(failed.status()).isEqualTo(BulkContactDeleteResult.Status.FAILED);
        assertThat(failed.errorCode()).isEqualTo("CONTACT_NOT_FOUND");
    }

    @Test
    @DisplayName("bulkDelete — non-existing account -> fail-fast without iteration")
    void bulkDeleteAccountNotFound() {
        when(accountService.getAccountOrThrow(999L)).thenThrow(new AccountNotFoundException(999L));

        assertThatThrownBy(() -> bulkService.bulkDelete(999L, new BulkContactDeleteRequest(List.of(1L, 2L))))
                .isInstanceOf(AccountNotFoundException.class);

        verify(contactService, never()).deleteContact(anyLong(), anyLong());
    }
}
