package org.voxrox.mailbackend.feature.contact.service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.voxrox.mailbackend.exception.ContactLabelNotFoundException;
import org.voxrox.mailbackend.exception.ContactNotFoundException;
import org.voxrox.mailbackend.exception.DuplicateContactLabelException;
import org.voxrox.mailbackend.exception.ValidationException;
import org.voxrox.mailbackend.feature.account.entity.AccountEntity;
import org.voxrox.mailbackend.feature.account.service.AccountService;
import org.voxrox.mailbackend.feature.contact.dto.ContactLabelAssignmentRequest;
import org.voxrox.mailbackend.feature.contact.dto.ContactLabelAssignmentResponse;
import org.voxrox.mailbackend.feature.contact.dto.ContactLabelCreateRequest;
import org.voxrox.mailbackend.feature.contact.dto.ContactLabelResponse;
import org.voxrox.mailbackend.feature.contact.dto.ContactLabelUpdateRequest;
import org.voxrox.mailbackend.feature.contact.entity.ContactEntity;
import org.voxrox.mailbackend.feature.contact.entity.ContactLabelEntity;
import org.voxrox.mailbackend.feature.contact.repository.ContactLabelRepository;
import org.voxrox.mailbackend.feature.contact.repository.ContactRepository;
import org.voxrox.mailbackend.util.AuditLog;
import org.voxrox.mailbackend.util.LogCategory;
import org.voxrox.mailbackend.util.LogMasker;

/**
 * Manages the account's user-defined contact labels and their assignment to
 * contacts.
 * <p>
 * Deliberately separate from {@link ContactService}: labels have their own
 * lifecycle (they exist without any contact) and their own uniqueness rule,
 * while ContactService is already the largest class in the feature.
 * ContactService depends on this one for resolving label IDs, never the other
 * way round.
 */
@Service
public class ContactLabelService {

    private static final Logger log = LoggerFactory.getLogger(ContactLabelService.class);

    /**
     * Ceiling on labels per account. Not a storage concern — the sidebar lists all
     * of them, so past a few hundred the navigation stops being navigation. High
     * enough that no genuine address book hits it.
     */
    static final int MAX_LABELS_PER_ACCOUNT = 200;

    /** Ceiling on labels per contact; mirrors the DTO {@code @Size} bound. */
    static final int MAX_LABELS_PER_CONTACT = 50;

    private final ContactLabelRepository labelRepository;
    private final ContactRepository contactRepository;
    private final AccountService accountService;

    public ContactLabelService(ContactLabelRepository labelRepository, ContactRepository contactRepository,
            AccountService accountService) {
        this.labelRepository = labelRepository;
        this.contactRepository = contactRepository;
        this.accountService = accountService;
    }

    /**
     * Case-folded form used for the uniqueness check and for ordering. Locale.ROOT
     * rather than the default locale: the key is persisted, so it must not change
     * meaning when the user switches the app language (Turkish dotless-i being the
     * classic way that goes wrong).
     */
    static String toNameKey(String name) {
        return name.trim().toLowerCase(Locale.ROOT);
    }

    @Transactional(readOnly = true)
    public List<ContactLabelResponse> listLabels(Long accountId) {
        accountService.getAccountOrThrow(accountId);
        return labelRepository.findByAccountIdOrderByNameKeyAsc(accountId).stream().map(ContactLabelService::toResponse)
                .toList();
    }

    @Transactional
    public ContactLabelResponse createLabel(Long accountId, ContactLabelCreateRequest request) {
        AccountEntity account = accountService.getAccountOrThrow(accountId);
        String name = requireNonBlankName(request.name());
        String nameKey = toNameKey(name);

        labelRepository.findByAccountIdAndNameKey(accountId, nameKey).ifPresent(existing -> {
            throw new DuplicateContactLabelException(accountId, existing.getName());
        });
        long existingCount = labelRepository.countByAccountId(accountId);
        if (existingCount >= MAX_LABELS_PER_ACCOUNT) {
            throw new ValidationException(
                    "Account " + accountId + " already has " + existingCount + " contact labels; the maximum is "
                            + MAX_LABELS_PER_ACCOUNT + ".",
                    "validation.contactLabel.tooManyPerAccount", MAX_LABELS_PER_ACCOUNT);
        }

        ContactLabelEntity entity = new ContactLabelEntity();
        entity.setAccount(account);
        entity.setName(name);
        entity.setNameKey(nameKey);
        ContactLabelEntity saved = labelRepository.save(entity);

        log.info("{} Contact label created: id={} account={}", LogCategory.ACCOUNT, saved.getId(), accountId);
        AuditLog.success("contact_label_create", LogMasker.maskEmail(account.getEmail()), "label_id=" + saved.getId());
        return toResponse(saved);
    }

    @Transactional
    public ContactLabelResponse renameLabel(Long accountId, Long labelId, ContactLabelUpdateRequest request) {
        AccountEntity account = accountService.getAccountOrThrow(accountId);
        ContactLabelEntity entity = getLabelOrThrow(accountId, labelId);

        String name = requireNonBlankName(request.name());
        String nameKey = toNameKey(name);

        labelRepository.findByAccountIdAndNameKey(accountId, nameKey).filter(other -> !other.getId().equals(labelId))
                .ifPresent(other -> {
                    throw new DuplicateContactLabelException(accountId, other.getName());
                });

        entity.setName(name);
        entity.setNameKey(nameKey);
        ContactLabelEntity saved = labelRepository.save(entity);

        AuditLog.success("contact_label_rename", LogMasker.maskEmail(account.getEmail()), "label_id=" + labelId);
        return toResponse(saved);
    }

    /**
     * Deletes a label. The contacts carrying it survive and simply lose the label —
     * the join rows go away with the FK cascade. Hibernate does not know about the
     * DB-level cascade for the join table it owns from the contact side, so the
     * assignments are cleared explicitly first; otherwise the label row would be
     * deleted while stale link rows stayed in the persistence context of anything
     * loaded in the same transaction.
     */
    @Transactional
    public void deleteLabel(Long accountId, Long labelId) {
        AccountEntity account = accountService.getAccountOrThrow(accountId);
        ContactLabelEntity entity = getLabelOrThrow(accountId, labelId);

        List<ContactEntity> carriers = contactRepository.findByAccountIdAndLabelId(accountId, labelId);
        for (ContactEntity contact : carriers) {
            contact.getLabels().remove(entity);
        }
        contactRepository.saveAll(carriers);
        contactRepository.flush();

        labelRepository.delete(entity);

        log.info("{} Contact label deleted: id={} account={} detached_contacts={}", LogCategory.ACCOUNT, labelId,
                accountId, carriers.size());
        AuditLog.success("contact_label_delete", LogMasker.maskEmail(account.getEmail()),
                "label_id=" + labelId + " detached_contacts=" + carriers.size());
    }

    /**
     * Applies label additions and removals across a contact selection in one
     * transaction. Idempotent per contact: adding a label a contact already has (or
     * removing one it does not) is a no-op that does not count toward
     * {@code changed}.
     * <p>
     * A contact ID that does not belong to the account is a 404 for the whole
     * request rather than a per-item failure — unlike bulk delete, this originates
     * from a selection the client just rendered, so an unknown ID means the client
     * is out of sync and silently skipping it would misreport what happened.
     */
    @Transactional
    public ContactLabelAssignmentResponse assignLabels(Long accountId, ContactLabelAssignmentRequest request) {
        AccountEntity account = accountService.getAccountOrThrow(accountId);

        Set<Long> addIds = distinctIds(request.addLabelIds());
        Set<Long> removeIds = distinctIds(request.removeLabelIds());
        if (addIds.isEmpty() && removeIds.isEmpty()) {
            throw new ValidationException("Provide at least one label to add or remove.",
                    "validation.contactLabel.assignmentEmpty");
        }

        Set<ContactLabelEntity> toAdd = resolveLabels(accountId, addIds);
        Set<ContactLabelEntity> toRemove = resolveLabels(accountId, removeIds);

        Set<Long> contactIds = new LinkedHashSet<>(request.contactIds());
        int changed = 0;
        List<ContactEntity> dirty = new ArrayList<>();
        for (Long contactId : contactIds) {
            ContactEntity contact = contactRepository.findByIdAndAccountId(contactId, accountId)
                    .orElseThrow(() -> new ContactNotFoundException(accountId, contactId));

            Set<ContactLabelEntity> labels = contact.getLabels();
            boolean modified = labels.removeAll(toRemove);
            modified |= labels.addAll(toAdd);
            if (modified) {
                ensureLabelCountWithinLimit(contactId, labels.size());
                dirty.add(contact);
                changed++;
            }
        }
        contactRepository.saveAll(dirty);

        log.info("{} Contact label assignment account={} contacts={} add={} remove={} changed={}", LogCategory.ACCOUNT,
                accountId, contactIds.size(), addIds.size(), removeIds.size(), changed);
        AuditLog.success("contact_label_assign", LogMasker.maskEmail(account.getEmail()), "contacts="
                + contactIds.size() + " add=" + addIds.size() + " remove=" + removeIds.size() + " changed=" + changed);
        return new ContactLabelAssignmentResponse(contactIds.size(), changed);
    }

    /**
     * Resolves the label IDs of a contact create/update/patch to entities. Returns
     * an empty set for {@code null} or an empty list, so the caller can use
     * "replace with nothing" and "no labels given" interchangeably where its own
     * semantics say so.
     *
     * @throws ContactLabelNotFoundException
     *             if any ID is unknown within the account
     */
    @Transactional(readOnly = true)
    public Set<ContactLabelEntity> resolveLabels(Long accountId, @Nullable Collection<Long> labelIds) {
        Set<Long> distinct = distinctIds(labelIds);
        if (distinct.isEmpty()) {
            return Set.of();
        }
        ensureLabelCountWithinLimit(null, distinct.size());

        Map<Long, ContactLabelEntity> found = labelRepository.findByAccountIdAndIdIn(accountId, distinct).stream()
                .collect(Collectors.toMap(ContactLabelEntity::getId, l -> l));
        // Preserve request order so a caller inspecting the set sees what it sent.
        Set<ContactLabelEntity> resolved = new LinkedHashSet<>(distinct.size());
        for (Long id : distinct) {
            ContactLabelEntity label = found.get(id);
            if (label == null) {
                throw new ContactLabelNotFoundException(accountId, id);
            }
            resolved.add(label);
        }
        return resolved;
    }

    private void ensureLabelCountWithinLimit(@Nullable Long contactId, int count) {
        if (count > MAX_LABELS_PER_CONTACT) {
            String subject = contactId == null ? "The request" : "Contact " + contactId;
            throw new ValidationException(
                    subject + " would carry " + count + " labels; the maximum is " + MAX_LABELS_PER_CONTACT + ".",
                    "validation.contactLabel.tooManyPerContact", MAX_LABELS_PER_CONTACT);
        }
    }

    private static Set<Long> distinctIds(@Nullable Collection<Long> ids) {
        return ids == null ? Set.of() : new LinkedHashSet<>(ids);
    }

    private ContactLabelEntity getLabelOrThrow(Long accountId, Long labelId) {
        Optional<ContactLabelEntity> found = labelRepository.findByIdAndAccountId(labelId, accountId);
        return found.orElseThrow(() -> new ContactLabelNotFoundException(accountId, labelId));
    }

    private String requireNonBlankName(String raw) {
        String trimmed = raw == null ? "" : raw.trim();
        if (trimmed.isEmpty()) {
            // @NotBlank already rejects an empty string, but a name of only spaces
            // passes it and would land here as an empty name_key.
            throw new ValidationException("The contact label name must not be blank.",
                    "validation.contactLabel.nameRequired");
        }
        return trimmed;
    }

    static ContactLabelResponse toResponse(ContactLabelEntity entity) {
        return new ContactLabelResponse(entity.getId(), entity.getName());
    }
}
