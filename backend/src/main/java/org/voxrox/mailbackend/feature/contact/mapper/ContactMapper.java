package org.voxrox.mailbackend.feature.contact.mapper;

import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Component;
import org.voxrox.mailbackend.feature.account.entity.AccountEntity;
import org.voxrox.mailbackend.feature.contact.dto.ContactCreateRequest;
import org.voxrox.mailbackend.feature.contact.dto.ContactEmailRequest;
import org.voxrox.mailbackend.feature.contact.dto.ContactEmailResponse;
import org.voxrox.mailbackend.feature.contact.dto.ContactPatchRequest;
import org.voxrox.mailbackend.feature.contact.dto.ContactResponse;
import org.voxrox.mailbackend.feature.contact.dto.ContactUpdateRequest;
import org.voxrox.mailbackend.feature.contact.entity.ContactEmailEntity;
import org.voxrox.mailbackend.feature.contact.entity.ContactEntity;

@Component
public class ContactMapper {

    public ContactEntity toEntity(ContactCreateRequest req, AccountEntity account) {
        ContactEntity e = new ContactEntity();
        e.setAccount(account);
        e.setName(req.name());
        e.setSurname(req.surname());
        e.setNote(req.note());
        addEmails(e, req.emails());
        return e;
    }

    public ContactResponse toResponse(ContactEntity e) {
        List<ContactEmailResponse> emails = e.getEmails().stream()
                .map(em -> new ContactEmailResponse(em.getId(), em.getEmail(), em.getLabel(), em.isPrimary())).toList();
        return new ContactResponse(e.getId(), emails, e.getName(), e.getSurname(), e.getNote(), e.getCreatedAt(),
                e.getUpdatedAt());
    }

    public void applyUpdate(ContactEntity entity, ContactUpdateRequest req) {
        entity.setName(req.name());
        entity.setSurname(req.surname());
        entity.setNote(req.note());
        replaceEmails(entity, req.emails());
    }

    public void applyPatch(ContactEntity entity, ContactPatchRequest req) {
        if (req.emails() != null)
            replaceEmails(entity, req.emails());
        if (req.name() != null)
            entity.setName(req.name());
        if (req.surname() != null)
            entity.setSurname(req.surname());
        if (req.note() != null)
            entity.setNote(req.note());
    }

    private void addEmails(ContactEntity entity, List<ContactEmailRequest> emailRequests) {
        for (int i = 0; i < emailRequests.size(); i++) {
            ContactEmailRequest req = emailRequests.get(i);
            ContactEmailEntity emailEntity = new ContactEmailEntity();
            emailEntity.setContact(entity);
            emailEntity.setEmail(normalizeEmail(req.email()));
            emailEntity.setLabel(req.label());
            emailEntity.setPrimary(i == 0);
            entity.getEmails().add(emailEntity);
        }
    }

    private void replaceEmails(ContactEntity entity, List<ContactEmailRequest> emailRequests) {
        entity.getEmails().clear();
        addEmails(entity, emailRequests);
    }

    public String normalizeEmail(String email) {
        if (email == null)
            return null;
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
