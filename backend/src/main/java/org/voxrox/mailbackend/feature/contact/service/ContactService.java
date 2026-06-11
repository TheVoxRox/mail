package org.voxrox.mailbackend.feature.contact.service;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.voxrox.mailbackend.exception.ContactNotFoundException;
import org.voxrox.mailbackend.exception.DuplicateContactException;
import org.voxrox.mailbackend.exception.ResourceNotFoundException;
import org.voxrox.mailbackend.exception.ValidationException;
import org.voxrox.mailbackend.feature.account.entity.AccountEntity;
import org.voxrox.mailbackend.feature.account.service.AccountService;
import org.voxrox.mailbackend.feature.contact.EmailLabel;
import org.voxrox.mailbackend.feature.contact.dto.ContactAutocompleteResponse;
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
import org.voxrox.mailbackend.feature.contact.repository.ContactRepository;
import org.voxrox.mailbackend.util.AuditLog;
import org.voxrox.mailbackend.util.LogCategory;
import org.voxrox.mailbackend.util.LogMasker;

import module java.base;

@Service
public class ContactService {

    private static final Logger log = LoggerFactory.getLogger(ContactService.class);

    private static final Sort DEFAULT_SORT = Sort.by(Sort.Order.asc("surname").nullsLast(),
            Sort.Order.asc("name").nullsLast(), Sort.Order.asc("id"));

    private static final Sort SORT_BY_NAME = Sort.by(Sort.Order.asc("name").nullsLast(),
            Sort.Order.asc("surname").nullsLast(), Sort.Order.asc("id"));

    private static final Sort SORT_BY_RECENT = Sort.by(Sort.Order.desc("updatedAt"), Sort.Order.asc("id"));

    private static Sort resolveSort(String sortKey) {
        if (sortKey == null || sortKey.isBlank()) {
            return DEFAULT_SORT;
        }
        return switch (sortKey.toLowerCase(Locale.ROOT)) {
            case "surname" -> DEFAULT_SORT;
            case "name" -> SORT_BY_NAME;
            case "recent" -> SORT_BY_RECENT;
            default -> throw new ValidationException(
                    "Invalid sort parameter value: " + sortKey + " (expected: name|surname|recent).",
                    "validation.contactSortInvalid", sortKey);
        };
    }

    private final ContactRepository contactRepository;
    private final AccountService accountService;
    private final ContactMapper contactMapper;

    public ContactService(ContactRepository contactRepository, AccountService accountService,
            ContactMapper contactMapper) {
        this.contactRepository = contactRepository;
        this.accountService = accountService;
        this.contactMapper = contactMapper;
    }

    @Transactional(readOnly = true)
    public Page<ContactResponse> listContacts(Long accountId, int page, int size, String sort, EmailLabel label) {
        accountService.getAccountOrThrow(accountId);
        Pageable pageable = PageRequest.of(page, size, resolveSort(sort));
        Page<ContactEntity> contacts = contactRepository.findByAccountId(accountId, label, pageable);
        return contacts.map(contactMapper::toResponse);
    }

    /**
     * Autocomplete for the compose-window typeahead. Returns a flat list of
     * addresses (contact × email) ranked by relevance to the {@code q} prefix:
     * <ol>
     * <li>email prefix match</li>
     * <li>surname prefix match</li>
     * <li>name prefix match</li>
     * <li>other substring matches</li>
     * </ol>
     * Within a rank, alphabetical by surname/name/email. Unlike
     * {@code searchContacts}, no pagination metadata is returned — the client just
     * receives an array.
     *
     * <p>
     * Hard-capped at limit 20; caller enforces {@code @Min(1)} on the controller.
     * For the flatten we fetch {@code limit} contacts — from below that covers
     * {@code limit} rows even when each contact has just 1 address, from above the
     * pool may overflow with foreign addresses of contacts that matched on name. We
     * finally trim to {@code limit}.
     * </p>
     */
    @Transactional(readOnly = true)
    public List<ContactAutocompleteResponse> autocomplete(Long accountId, String q, int limit) {
        if (q == null || q.isBlank()) {
            throw new ValidationException("Contact search query q must not be empty.",
                    "validation.contactQueryRequired");
        }
        accountService.getAccountOrThrow(accountId);

        int cappedLimit = Math.min(Math.max(limit, 1), AUTOCOMPLETE_MAX_LIMIT);
        String qLower = q.toLowerCase(Locale.ROOT);
        String pattern = "%" + qLower + "%";

        Pageable pageable = PageRequest.of(0, cappedLimit, DEFAULT_SORT);
        List<ContactEntity> matched = contactRepository.searchByAccountId(accountId, pattern, null, pageable)
                .getContent();

        return matched.stream().flatMap(c -> c.getEmails().stream().map(e -> toAutocompleteRow(c, e, qLower)))
                .sorted(AUTOCOMPLETE_RANKING).limit(cappedLimit).map(AutocompleteRow::response).toList();
    }

    private static final int AUTOCOMPLETE_MAX_LIMIT = 20;

    private static final java.util.Comparator<AutocompleteRow> AUTOCOMPLETE_RANKING = java.util.Comparator
            .<AutocompleteRow>comparingInt(r -> r.rank)
            .thenComparing(r -> r.response.surname() == null ? "" : r.response.surname().toLowerCase(Locale.ROOT))
            .thenComparing(r -> r.response.name() == null ? "" : r.response.name().toLowerCase(Locale.ROOT))
            .thenComparing(r -> r.response.email().toLowerCase(Locale.ROOT));

    private static AutocompleteRow toAutocompleteRow(ContactEntity contact, ContactEmailEntity email, String qLower) {
        int rank = 3;
        String emailLower = email.getEmail().toLowerCase(Locale.ROOT);
        String surnameLower = contact.getSurname() == null ? "" : contact.getSurname().toLowerCase(Locale.ROOT);
        String nameLower = contact.getName() == null ? "" : contact.getName().toLowerCase(Locale.ROOT);
        if (emailLower.startsWith(qLower)) {
            rank = 0;
        } else if (!surnameLower.isEmpty() && surnameLower.startsWith(qLower)) {
            rank = 1;
        } else if (!nameLower.isEmpty() && nameLower.startsWith(qLower)) {
            rank = 2;
        }
        ContactAutocompleteResponse resp = new ContactAutocompleteResponse(contact.getId(), email.getId(),
                email.getEmail(), email.getLabel(), email.isPrimary(), contact.getName(), contact.getSurname());
        return new AutocompleteRow(rank, resp);
    }

    private record AutocompleteRow(int rank, ContactAutocompleteResponse response) {
    }

    @Transactional(readOnly = true)
    public Page<ContactResponse> searchContacts(Long accountId, String q, int page, int size, String sort,
            EmailLabel label) {
        if (q == null || q.isBlank()) {
            throw new ValidationException("Contact search query q must not be empty.",
                    "validation.contactQueryRequired");
        }
        accountService.getAccountOrThrow(accountId);
        String pattern = "%" + q.toLowerCase(Locale.ROOT) + "%";
        Pageable pageable = PageRequest.of(page, size, resolveSort(sort));
        Page<ContactEntity> contacts = contactRepository.searchByAccountId(accountId, pattern, label, pageable);
        return contacts.map(contactMapper::toResponse);
    }

    @Transactional(readOnly = true)
    public ContactResponse getContact(Long accountId, Long contactId) {
        ContactEntity contact = getContactOrThrow(accountId, contactId);
        return contactMapper.toResponse(contact);
    }

    /**
     * Exports the entire account address book as vCard 4.0 (RFC 6350). Order of
     * contacts matches the listing (surname/name/id NULLS LAST). Returns a plain
     * string — address books are typically &lt;10k entries, streaming would be
     * over-engineering.
     */
    @Transactional(readOnly = true)
    public String exportToVCard(Long accountId) {
        AccountEntity account = accountService.getAccountOrThrow(accountId);
        List<ContactEntity> contacts = contactRepository.findAllByAccountId(accountId, DEFAULT_SORT);
        AuditLog.success("contact_export", LogMasker.maskEmail(account.getEmail()),
                "format=vcard count=" + contacts.size());
        return VCardWriter.write(contacts);
    }

    @Transactional
    public ContactResponse createContact(Long accountId, ContactCreateRequest request) {
        AccountEntity account = accountService.getAccountOrThrow(accountId);
        List<String> normalizedEmails = normalizeEmailList(request.emails());
        checkNoDuplicatesWithinAccount(accountId, null, normalizedEmails);

        ContactEntity entity = contactMapper.toEntity(request, account);
        ContactEntity saved = contactRepository.save(entity);

        String primaryEmail = primaryEmail(saved);
        log.info("{} Contact created: id={} account={}", LogCategory.ACCOUNT, saved.getId(), accountId);
        AuditLog.success("contact_create", LogMasker.maskEmail(account.getEmail()),
                "contact_id=" + saved.getId() + " email=" + LogMasker.maskEmail(primaryEmail));
        return contactMapper.toResponse(saved);
    }

    @Transactional
    public ContactResponse updateContact(Long accountId, Long contactId, ContactUpdateRequest request) {
        AccountEntity account = accountService.getAccountOrThrow(accountId);
        ContactEntity entity = getContactOrThrow(accountId, contactId);

        List<String> normalizedEmails = normalizeEmailList(request.emails());
        checkNoDuplicatesWithinAccount(accountId, contactId, normalizedEmails);

        contactMapper.applyUpdate(entity, request);
        ContactEntity saved = contactRepository.save(entity);

        AuditLog.success("contact_update", LogMasker.maskEmail(account.getEmail()),
                "contact_id=" + saved.getId() + " email=" + LogMasker.maskEmail(primaryEmail(saved)));
        return contactMapper.toResponse(saved);
    }

    @Transactional
    public ContactResponse patchContact(Long accountId, Long contactId, ContactPatchRequest request) {
        AccountEntity account = accountService.getAccountOrThrow(accountId);
        ContactEntity entity = getContactOrThrow(accountId, contactId);

        if (request.emails() != null) {
            List<String> normalizedEmails = normalizeEmailList(request.emails());
            checkNoDuplicatesWithinAccount(accountId, contactId, normalizedEmails);
        }

        contactMapper.applyPatch(entity, request);
        ContactEntity saved = contactRepository.save(entity);

        AuditLog.success("contact_patch", LogMasker.maskEmail(account.getEmail()),
                "contact_id=" + saved.getId() + " email=" + LogMasker.maskEmail(primaryEmail(saved)));
        return contactMapper.toResponse(saved);
    }

    @Transactional
    public void deleteContact(Long accountId, Long contactId) {
        AccountEntity account = accountService.getAccountOrThrow(accountId);
        ContactEntity entity = getContactOrThrow(accountId, contactId);
        String maskedEmail = LogMasker.maskEmail(primaryEmail(entity));

        contactRepository.delete(entity);

        AuditLog.success("contact_delete", LogMasker.maskEmail(account.getEmail()),
                "contact_id=" + contactId + " email=" + maskedEmail);
    }

    /**
     * Adds a new e-mail address to a contact. Does not change the primary flag of
     * existing addresses — the new email is added as non-primary. Follows the
     * {@link #checkNoDuplicatesWithinAccount} convention: a duplicate within this
     * contact's own list → {@link ValidationException} (400, the client fixes its
     * own form), a collision with another contact →
     * {@link DuplicateContactException} (409, conflict with an existing record).
     */
    @Transactional
    public ContactEmailResponse addEmail(Long accountId, Long contactId, ContactEmailRequest request) {
        AccountEntity account = accountService.getAccountOrThrow(accountId);
        ContactEntity entity = getContactOrThrow(accountId, contactId);

        // request.email() is @NotBlank-validated, so the normalized form exists.
        String normalized = Objects.requireNonNull(contactMapper.normalizeEmail(request.email()));
        if (entity.getEmails().stream().anyMatch(e -> normalized.equals(e.getEmail()))) {
            throw new ValidationException("The contact already has the e-mail address " + normalized + ".",
                    "validation.contact.emailAlreadyOnContact", normalized);
        }
        contactRepository.findByAccountIdAndAnyEmail(accountId, normalized).stream()
                .filter(other -> !other.getId().equals(contactId)).findFirst().ifPresent(other -> {
                    throw new DuplicateContactException(accountId, normalized);
                });

        ContactEmailEntity newEmail = new ContactEmailEntity();
        newEmail.setContact(entity);
        newEmail.setEmail(normalized);
        newEmail.setLabel(request.label());
        newEmail.setPrimary(entity.getEmails().isEmpty());
        entity.getEmails().add(newEmail);

        ContactEntity saved = contactRepository.save(entity);
        ContactEmailEntity persisted = saved.getEmails().stream().filter(e -> normalized.equals(e.getEmail()))
                .findFirst().orElseThrow(() -> new IllegalStateException("Added email was not found after save."));

        AuditLog.success("contact_email_add", LogMasker.maskEmail(account.getEmail()),
                "contact_id=" + contactId + " email=" + LogMasker.maskEmail(normalized));
        return new ContactEmailResponse(persisted.getId(), persisted.getEmail(), persisted.getLabel(),
                persisted.isPrimary());
    }

    /**
     * Removes an address from a contact. If it was primary, promotes the first
     * remaining one (deterministically by ID). 400 when it is the contact's last
     * email.
     */
    @Transactional
    public void deleteEmail(Long accountId, Long contactId, Long emailId) {
        AccountEntity account = accountService.getAccountOrThrow(accountId);
        ContactEntity entity = getContactOrThrow(accountId, contactId);

        ContactEmailEntity target = entity.getEmails().stream().filter(e -> emailId.equals(e.getId())).findFirst()
                .orElseThrow(() -> new ResourceNotFoundException(
                        "E-mail with id=" + emailId + " for contact " + contactId + " not found."));

        if (entity.getEmails().size() == 1) {
            throw new ValidationException("At least one e-mail address is required.",
                    "validation.contact.emailRequired");
        }

        String removedEmail = target.getEmail();
        boolean wasPrimary = target.isPrimary();
        entity.getEmails().remove(target);

        if (wasPrimary) {
            entity.getEmails().stream().min((a, b) -> Long.compare(a.getId(), b.getId()))
                    .ifPresent(promote -> promote.setPrimary(true));
        }

        contactRepository.save(entity);

        AuditLog.success("contact_email_delete", LogMasker.maskEmail(account.getEmail()),
                "contact_id=" + contactId + " email=" + LogMasker.maskEmail(removedEmail));
    }

    /**
     * Marks the chosen address as primary. The contact's other addresses get
     * {@code primary=false}. 404 when the address does not belong to the contact.
     */
    @Transactional
    public ContactResponse setPrimaryEmail(Long accountId, Long contactId, Long emailId) {
        AccountEntity account = accountService.getAccountOrThrow(accountId);
        ContactEntity entity = getContactOrThrow(accountId, contactId);

        ContactEmailEntity target = entity.getEmails().stream().filter(e -> emailId.equals(e.getId())).findFirst()
                .orElseThrow(() -> new ResourceNotFoundException(
                        "E-mail with id=" + emailId + " for contact " + contactId + " not found."));

        for (ContactEmailEntity e : entity.getEmails()) {
            e.setPrimary(emailId.equals(e.getId()));
        }

        ContactEntity saved = contactRepository.save(entity);

        AuditLog.success("contact_email_set_primary", LogMasker.maskEmail(account.getEmail()),
                "contact_id=" + contactId + " email=" + LogMasker.maskEmail(target.getEmail()));
        return contactMapper.toResponse(saved);
    }

    /**
     * Merges source contacts into the target in a single transaction. The target
     * stays canonical (name/surname/primary are preserved); emails from sources are
     * added deduplicated by lowercase variant (collisions drop the source version);
     * notes are concatenated with a separator. Source contacts are deleted. If the
     * email limit (10) is exceeded the whole operation is rejected with
     * {@link ValidationException} so the user can manually reduce the address count
     * and retry — we never drop addresses automatically.
     */
    @Transactional
    public ContactResponse merge(Long accountId, Long targetId, ContactMergeRequest request) {
        AccountEntity account = accountService.getAccountOrThrow(accountId);

        List<Long> rawSources = request.source();
        Set<Long> uniqueSources = new LinkedHashSet<>(rawSources);
        if (uniqueSources.size() != rawSources.size()) {
            throw new ValidationException("The source list contains duplicate IDs.",
                    "validation.contactMerge.sourceDuplicate");
        }
        if (uniqueSources.contains(targetId)) {
            throw new ValidationException("The target contact must not also be in the source list.",
                    "validation.contactMerge.targetInSource");
        }

        ContactEntity target = getContactOrThrow(accountId, targetId);
        List<ContactEntity> sources = new ArrayList<>(uniqueSources.size());
        for (Long sid : uniqueSources) {
            sources.add(getContactOrThrow(accountId, sid));
        }

        Set<String> alreadyAdded = target.getEmails().stream().map(e -> e.getEmail().toLowerCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));

        List<EmailToAdd> toAdd = new ArrayList<>();
        for (ContactEntity src : sources) {
            for (ContactEmailEntity e : src.getEmails()) {
                String key = e.getEmail().toLowerCase(Locale.ROOT);
                if (alreadyAdded.add(key)) {
                    toAdd.add(new EmailToAdd(e.getEmail(), e.getLabel()));
                }
            }
        }

        int finalCount = target.getEmails().size() + toAdd.size();
        if (finalCount > MAX_EMAILS_PER_CONTACT) {
            throw new ValidationException(
                    "After merging, the contact would have " + finalCount + " e-mail addresses; the maximum is "
                            + MAX_EMAILS_PER_CONTACT + ". Reduce the number of addresses before merging.",
                    "validation.contactMerge.tooManyEmails", finalCount, MAX_EMAILS_PER_CONTACT);
        }

        String mergedNote = mergeNotes(target.getNote(), sources);
        boolean targetHasPrimary = target.getEmails().stream().anyMatch(ContactEmailEntity::isPrimary);

        for (EmailToAdd add : toAdd) {
            ContactEmailEntity ne = new ContactEmailEntity();
            ne.setContact(target);
            ne.setEmail(add.email());
            ne.setLabel(add.label());
            ne.setPrimary(false);
            target.getEmails().add(ne);
        }
        if (!targetHasPrimary && !target.getEmails().isEmpty()) {
            target.getEmails().get(0).setPrimary(true);
        }
        target.setNote(mergedNote);

        for (ContactEntity src : sources) {
            contactRepository.delete(src);
        }
        ContactEntity saved = contactRepository.save(target);

        log.info("{} Contact merge account={}: target={} sources={} final_emails={}", LogCategory.ACCOUNT, accountId,
                targetId, uniqueSources.size(), saved.getEmails().size());
        AuditLog.success("contact_merge", LogMasker.maskEmail(account.getEmail()), "target=" + targetId + " sources="
                + uniqueSources.size() + " final_emails=" + saved.getEmails().size());

        return contactMapper.toResponse(saved);
    }

    private static final int MAX_EMAILS_PER_CONTACT = 10;
    /**
     * Neutral, language-agnostic separator inserted between concatenated notes when
     * merging contacts. Plain markdown-style horizontal rule reads the same in CZ
     * and EN UI and keeps the stored note portable if the user later switches the
     * app language.
     */
    private static final String NOTE_SEPARATOR = "\n\n---\n\n";

    private record EmailToAdd(String email, EmailLabel label) {
    }

    private static @Nullable String mergeNotes(@Nullable String targetNote, List<ContactEntity> sources) {
        List<String> parts = new ArrayList<>();
        if (targetNote != null && !targetNote.isBlank()) {
            parts.add(targetNote);
        }
        for (ContactEntity src : sources) {
            String n = src.getNote();
            if (n != null && !n.isBlank()) {
                parts.add(n);
            }
        }
        return parts.isEmpty() ? null : String.join(NOTE_SEPARATOR, parts);
    }

    private ContactEntity getContactOrThrow(Long accountId, Long contactId) {
        return contactRepository.findByIdAndAccountId(contactId, accountId)
                .orElseThrow(() -> new ContactNotFoundException(accountId, contactId));
    }

    /**
     * Verifies that no email in the list is used by another contact within the
     * given account. {@code excludeContactId} is the ID of the contact currently
     * being edited (its own emails are fine); pass {@code null} for create.
     * <p>
     * A duplicate inside the request (client sent the same email twice) and a
     * collision with a foreign contact are two different states:
     * <ul>
     * <li>within request → {@link ValidationException} (400) — the client is fixing
     * its own list</li>
     * <li>cross-contact → {@link DuplicateContactException} (409) — a conflict with
     * an existing record in the DB</li>
     * </ul>
     */
    private void checkNoDuplicatesWithinAccount(Long accountId, @Nullable Long excludeContactId, List<String> emails) {
        Set<String> seen = new HashSet<>();
        for (String email : emails) {
            if (!seen.add(email)) {
                throw new ValidationException("E-mail " + email + " appears more than once in the list.",
                        "validation.contact.emailDuplicateInRequest", email);
            }
        }

        List<ContactEntity> matches = contactRepository.findByAccountIdAndAnyEmailIn(accountId, seen);
        for (String email : seen) {
            matches.stream().filter(other -> excludeContactId == null || !other.getId().equals(excludeContactId))
                    .filter(other -> other.getEmails().stream().anyMatch(e -> email.equals(e.getEmail()))).findFirst()
                    .ifPresent(other -> {
                        throw new DuplicateContactException(accountId, email);
                    });
        }
    }

    private List<String> normalizeEmailList(List<ContactEmailRequest> emails) {
        // Each e.email() is @NotBlank-validated, so the normalized forms exist.
        return emails.stream().map(e -> Objects.requireNonNull(contactMapper.normalizeEmail(e.email()))).toList();
    }

    private static String primaryEmail(ContactEntity entity) {
        return entity.getEmails().stream().filter(e -> e.isPrimary()).findFirst().map(e -> e.getEmail())
                .orElseGet(() -> entity.getEmails().isEmpty() ? "unknown" : entity.getEmails().get(0).getEmail());
    }
}
