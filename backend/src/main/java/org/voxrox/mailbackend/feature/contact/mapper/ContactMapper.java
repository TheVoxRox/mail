package org.voxrox.mailbackend.feature.contact.mapper;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.voxrox.mailbackend.feature.account.entity.AccountEntity;
import org.voxrox.mailbackend.feature.contact.dto.ContactCreateRequest;
import org.voxrox.mailbackend.feature.contact.dto.ContactEmailRequest;
import org.voxrox.mailbackend.feature.contact.dto.ContactEmailResponse;
import org.voxrox.mailbackend.feature.contact.dto.ContactLabelResponse;
import org.voxrox.mailbackend.feature.contact.dto.ContactPatchRequest;
import org.voxrox.mailbackend.feature.contact.dto.ContactResponse;
import org.voxrox.mailbackend.feature.contact.dto.ContactUpdateRequest;
import org.voxrox.mailbackend.feature.contact.entity.ContactEmailEntity;
import org.voxrox.mailbackend.feature.contact.entity.ContactEntity;
import org.voxrox.mailbackend.feature.contact.entity.ContactLabelEntity;

/**
 * Maps contact DTOs to entities and back.
 * <p>
 * Labels arrive already resolved to entities — the mapper has no repository and
 * turning IDs into rows is a lookup with its own failure mode (unknown ID →
 * 404), which belongs in the service. {@code null} labels mean "leave them
 * alone", matching the PATCH semantics of the other fields.
 */
@Component
public class ContactMapper {

    public ContactEntity toEntity(ContactCreateRequest req, AccountEntity account,
            @Nullable Set<ContactLabelEntity> labels) {
        ContactEntity e = new ContactEntity();
        e.setAccount(account);
        e.setName(req.name());
        e.setSurname(req.surname());
        e.setNote(req.note());
        addEmails(e, req.emails());
        if (labels != null)
            e.setLabels(new LinkedHashSet<>(labels));
        return e;
    }

    public ContactResponse toResponse(ContactEntity e) {
        List<ContactEmailResponse> emails = e.getEmails().stream()
                .map(em -> new ContactEmailResponse(em.getId(), em.getEmail(), em.getLabel(), em.isPrimary())).toList();
        List<ContactLabelResponse> labels = e.getLabels().stream()
                .map(l -> new ContactLabelResponse(l.getId(), l.getName())).toList();
        return new ContactResponse(e.getId(), emails, labels, e.getName(), e.getSurname(), e.getNote(),
                e.getCreatedAt(), e.getUpdatedAt());
    }

    public void applyUpdate(ContactEntity entity, ContactUpdateRequest req, @Nullable Set<ContactLabelEntity> labels) {
        entity.setName(req.name());
        entity.setSurname(req.surname());
        entity.setNote(req.note());
        replaceEmails(entity, req.emails());
        // PUT replaces everything, so an absent list clears the labels rather than
        // preserving them; the service passes an empty set for that case.
        if (labels != null)
            replaceLabels(entity, labels);
    }

    public void applyPatch(ContactEntity entity, ContactPatchRequest req, @Nullable Set<ContactLabelEntity> labels) {
        if (req.emails() != null)
            replaceEmails(entity, req.emails());
        if (labels != null)
            replaceLabels(entity, labels);
        if (req.name() != null)
            entity.setName(req.name());
        if (req.surname() != null)
            entity.setSurname(req.surname());
        if (req.note() != null)
            entity.setNote(req.note());
    }

    /*
     * Mutate the managed collection in place instead of swapping the field:
     * Hibernate tracks the join table through the collection instance it handed
     * out, and replacing it makes it delete every link row and re-insert the ones
     * that survived.
     */
    private void replaceLabels(ContactEntity entity, Set<ContactLabelEntity> labels) {
        Set<ContactLabelEntity> current = entity.getLabels();
        current.retainAll(labels);
        current.addAll(labels);
    }

    private void addEmails(ContactEntity entity, List<ContactEmailRequest> emailRequests) {
        for (int i = 0; i < emailRequests.size(); i++) {
            ContactEmailRequest req = emailRequests.get(i);
            ContactEmailEntity emailEntity = new ContactEmailEntity();
            emailEntity.setContact(entity);
            // req.email() is @NotBlank-validated, so the normalized form exists.
            emailEntity.setEmail(Objects.requireNonNull(normalizeEmail(req.email())));
            emailEntity.setLabel(req.label());
            emailEntity.setPrimary(i == 0);
            entity.getEmails().add(emailEntity);
        }
    }

    private void replaceEmails(ContactEntity entity, List<ContactEmailRequest> emailRequests) {
        entity.getEmails().clear();
        addEmails(entity, emailRequests);
    }

    /**
     * Null-tolerant canonical form: pass-through {@code null}, else trim +
     * lowercase.
     */
    public @Nullable String normalizeEmail(@Nullable String email) {
        if (email == null)
            return null;
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
