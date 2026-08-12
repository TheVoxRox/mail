package org.voxrox.mailbackend.feature.contact.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Locale;
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
import org.voxrox.mailbackend.exception.ContactLabelNotFoundException;
import org.voxrox.mailbackend.exception.ContactNotFoundException;
import org.voxrox.mailbackend.exception.DuplicateContactLabelException;
import org.voxrox.mailbackend.exception.ValidationException;
import org.voxrox.mailbackend.feature.contact.dto.ContactLabelAssignmentRequest;
import org.voxrox.mailbackend.feature.contact.dto.ContactLabelAssignmentResponse;
import org.voxrox.mailbackend.feature.contact.dto.ContactLabelCreateRequest;
import org.voxrox.mailbackend.feature.contact.dto.ContactLabelResponse;
import org.voxrox.mailbackend.feature.contact.dto.ContactLabelUpdateRequest;
import org.voxrox.mailbackend.feature.contact.entity.ContactEntity;
import org.voxrox.mailbackend.feature.contact.entity.ContactLabelEntity;
import org.voxrox.mailbackend.feature.contact.repository.ContactLabelRepository;
import org.voxrox.mailbackend.feature.contact.repository.ContactRepository;

@ExtendWith(MockitoExtension.class)
class ContactLabelServiceTest {

    private static final Long ACCOUNT_ID = 1L;

    @Mock
    private ContactLabelRepository labelRepository;
    @Mock
    private ContactRepository contactRepository;
    private ContactLabelService service;

    @BeforeEach
    void setUp() {
        service = new ContactLabelService(labelRepository, contactRepository);
    }

    private ContactLabelEntity label(Long id, String name) {
        ContactLabelEntity l = new ContactLabelEntity();
        l.setId(id);
        l.setName(name);
        l.setNameKey(name.toLowerCase(Locale.ROOT));
        return l;
    }

    private ContactEntity contact(Long id) {
        ContactEntity c = new ContactEntity();
        c.setId(id);
        return c;
    }

    @Nested
    @DisplayName("createLabel")
    class Create {

        @Test
        @DisplayName("Trims the name and derives the case-folded key from it")
        void trimsAndDerivesKey() {
            when(labelRepository.findByNameKey("family")).thenReturn(Optional.empty());
            when(labelRepository.count()).thenReturn(0L);
            when(labelRepository.save(any(ContactLabelEntity.class))).thenAnswer(inv -> {
                ContactLabelEntity e = inv.getArgument(0);
                e.setId(7L);
                return e;
            });

            ContactLabelResponse created = service.createLabel(new ContactLabelCreateRequest("  Family  "));

            ArgumentCaptor<ContactLabelEntity> captor = ArgumentCaptor.forClass(ContactLabelEntity.class);
            verify(labelRepository).save(captor.capture());
            assertThat(captor.getValue().getName()).isEqualTo("Family");
            assertThat(captor.getValue().getNameKey()).isEqualTo("family");
            assertThat(created).isEqualTo(new ContactLabelResponse(7L, "Family"));
        }

        @Test
        @DisplayName("A name differing only in case collides -> 409")
        void caseInsensitiveDuplicate() {
            when(labelRepository.findByNameKey("family")).thenReturn(Optional.of(label(3L, "Family")));

            assertThatThrownBy(() -> service.createLabel(new ContactLabelCreateRequest("FAMILY")))
                    .isInstanceOf(DuplicateContactLabelException.class);
            verify(labelRepository, never()).save(any());
        }

        @Test
        @DisplayName("Czech diacritics fold too — the key is not ASCII-only")
        void foldsDiacritics() {
            when(labelRepository.findByNameKey("škola")).thenReturn(Optional.of(label(4L, "Škola")));

            assertThatThrownBy(() -> service.createLabel(new ContactLabelCreateRequest("škola")))
                    .isInstanceOf(DuplicateContactLabelException.class);
        }

        @Test
        @DisplayName("A whitespace-only name is rejected before it becomes an empty key")
        void blankNameRejected() {

            assertThatThrownBy(() -> service.createLabel(new ContactLabelCreateRequest("   ")))
                    .isInstanceOf(ValidationException.class);
            verify(labelRepository, never()).save(any());
        }

        @Test
        @DisplayName("At the per-account ceiling -> 400, nothing is saved")
        void accountLimitEnforced() {
            when(labelRepository.findByNameKey("extra")).thenReturn(Optional.empty());
            when(labelRepository.count()).thenReturn((long) ContactLabelService.MAX_LABELS);

            assertThatThrownBy(() -> service.createLabel(new ContactLabelCreateRequest("Extra")))
                    .isInstanceOf(ValidationException.class);
            verify(labelRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("renameLabel")
    class Rename {

        @Test
        @DisplayName("Renaming to a name held by another label -> 409")
        void collidesWithOther() {
            when(labelRepository.findById(1L)).thenReturn(Optional.of(label(1L, "Family")));
            when(labelRepository.findByNameKey("clients")).thenReturn(Optional.of(label(2L, "Clients")));

            assertThatThrownBy(() -> service.renameLabel(1L, new ContactLabelUpdateRequest("Clients")))
                    .isInstanceOf(DuplicateContactLabelException.class);
        }

        @Test
        @DisplayName("Re-casing a label's own name is allowed — the match is itself")
        void ownNameIsNotACollision() {
            ContactLabelEntity existing = label(1L, "family");
            when(labelRepository.findById(1L)).thenReturn(Optional.of(existing));
            when(labelRepository.findByNameKey("family")).thenReturn(Optional.of(existing));
            when(labelRepository.save(existing)).thenReturn(existing);

            ContactLabelResponse renamed = service.renameLabel(1L, new ContactLabelUpdateRequest("Family"));

            assertThat(renamed.name()).isEqualTo("Family");
            assertThat(existing.getNameKey()).isEqualTo("family");
        }

        @Test
        @DisplayName("Unknown label -> 404")
        void unknownLabel() {
            when(labelRepository.findById(99L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.renameLabel(99L, new ContactLabelUpdateRequest("X")))
                    .isInstanceOf(ContactLabelNotFoundException.class);
        }
    }

    @Nested
    @DisplayName("deleteLabel")
    class Delete {

        @Test
        @DisplayName("Unassigns from every carrier, then deletes — the contacts survive")
        void unassignsBeforeDeleting() {
            ContactLabelEntity family = label(1L, "Family");
            when(labelRepository.findById(1L)).thenReturn(Optional.of(family));

            ContactEntity a = contact(10L);
            a.getLabels().add(family);
            ContactEntity b = contact(11L);
            b.getLabels().add(family);
            b.getLabels().add(label(2L, "Clients"));
            when(contactRepository.findByLabelId(1L)).thenReturn(List.of(a, b));

            service.deleteLabel(1L);

            assertThat(a.getLabels()).isEmpty();
            assertThat(b.getLabels()).extracting(ContactLabelEntity::getName).containsExactly("Clients");
            verify(contactRepository).saveAll(List.of(a, b));
            verify(labelRepository).delete(family);
        }

        @Test
        @DisplayName("Unknown label -> 404, nothing is touched")
        void unknownLabel() {
            when(labelRepository.findById(99L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.deleteLabel(99L)).isInstanceOf(ContactLabelNotFoundException.class);
            verify(contactRepository, never()).findByLabelId(anyLong());
            verify(labelRepository, never()).delete(any());
        }
    }

    @Nested
    @DisplayName("resolveLabels")
    class Resolve {

        @Test
        @DisplayName("null and an empty list both mean no labels, with no query")
        void emptyMeansNothing() {
            assertThat(service.resolveLabels(null)).isEmpty();
            assertThat(service.resolveLabels(List.of())).isEmpty();
            verify(labelRepository, never()).findByIdIn(any());
        }

        @Test
        @DisplayName("Duplicate IDs collapse to one label")
        void deduplicates() {
            when(labelRepository.findByIdIn(Set.of(1L))).thenReturn(List.of(label(1L, "Family")));

            assertThat(service.resolveLabels(List.of(1L, 1L, 1L))).hasSize(1);
        }

        @Test
        @DisplayName("An ID the account does not own -> 404 (a foreign label is never attached)")
        void unknownIdRejected() {
            when(labelRepository.findByIdIn(Set.of(1L, 99L))).thenReturn(List.of(label(1L, "Family")));

            assertThatThrownBy(() -> service.resolveLabels(List.of(1L, 99L)))
                    .isInstanceOf(ContactLabelNotFoundException.class);
        }
    }

    @Nested
    @DisplayName("assignLabels")
    class Assign {

        @Test
        @DisplayName("Adds and removes in one pass; only genuinely changed contacts are counted")
        void addsAndRemovesIdempotently() {
            ContactLabelEntity family = label(1L, "Family");
            ContactLabelEntity clients = label(2L, "Clients");
            when(labelRepository.findByIdIn(Set.of(1L))).thenReturn(List.of(family));
            when(labelRepository.findByIdIn(Set.of(2L))).thenReturn(List.of(clients));

            // Already has exactly the target state -> not a change.
            ContactEntity noop = contact(10L);
            noop.getLabels().add(family);
            // Gains "Family" and loses "Clients".
            ContactEntity changes = contact(11L);
            changes.getLabels().add(clients);
            when(contactRepository.findById(10L)).thenReturn(Optional.of(noop));
            when(contactRepository.findById(11L)).thenReturn(Optional.of(changes));

            ContactLabelAssignmentResponse result = service
                    .assignLabels(new ContactLabelAssignmentRequest(List.of(10L, 11L), List.of(1L), List.of(2L)));

            assertThat(result).isEqualTo(new ContactLabelAssignmentResponse(2, 1));
            assertThat(noop.getLabels()).containsExactly(family);
            assertThat(changes.getLabels()).containsExactly(family);
            verify(contactRepository).saveAll(List.of(changes));
        }

        @Test
        @DisplayName("Neither add nor remove -> 400, the request would be a no-op")
        void emptyAssignmentRejected() {

            assertThatThrownBy(
                    () -> service.assignLabels(new ContactLabelAssignmentRequest(List.of(10L), List.of(), null)))
                    .isInstanceOf(ValidationException.class);
            verify(contactRepository, never()).findById(anyLong());
        }

        @Test
        @DisplayName("A contact outside the account -> 404 for the whole request")
        void unknownContactFailsWholeRequest() {
            when(labelRepository.findByIdIn(Set.of(1L))).thenReturn(List.of(label(1L, "Family")));
            when(contactRepository.findById(99L)).thenReturn(Optional.empty());

            assertThatThrownBy(
                    () -> service.assignLabels(new ContactLabelAssignmentRequest(List.of(99L), List.of(1L), null)))
                    .isInstanceOf(ContactNotFoundException.class);
        }

        @Test
        @DisplayName("Removing a label a contact never had is a no-op, not a failure")
        void removingAbsentLabelIsNoop() {
            when(labelRepository.findByIdIn(Set.of(2L))).thenReturn(List.of(label(2L, "Clients")));
            ContactEntity c = contact(10L);
            when(contactRepository.findById(10L)).thenReturn(Optional.of(c));

            ContactLabelAssignmentResponse result = service
                    .assignLabels(new ContactLabelAssignmentRequest(List.of(10L), null, List.of(2L)));

            assertThat(result).isEqualTo(new ContactLabelAssignmentResponse(1, 0));
            assertThat(c.getLabels()).isEmpty();
        }

        @Test
        @DisplayName("A repeated contact ID is processed once")
        void duplicateContactIdsCollapse() {
            when(labelRepository.findByIdIn(Set.of(1L))).thenReturn(List.of(label(1L, "Family")));
            ContactEntity c = contact(10L);
            when(contactRepository.findById(10L)).thenReturn(Optional.of(c));

            ContactLabelAssignmentResponse result = service
                    .assignLabels(new ContactLabelAssignmentRequest(List.of(10L, 10L), List.of(1L), null));

            assertThat(result).isEqualTo(new ContactLabelAssignmentResponse(1, 1));
        }
    }
}
