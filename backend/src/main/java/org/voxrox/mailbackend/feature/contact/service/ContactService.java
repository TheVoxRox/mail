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
import org.voxrox.mailbackend.exception.ContactLabelNotFoundException;
import org.voxrox.mailbackend.exception.ContactNotFoundException;
import org.voxrox.mailbackend.exception.DuplicateContactException;
import org.voxrox.mailbackend.exception.ValidationException;
import org.voxrox.mailbackend.feature.contact.EmailLabel;
import org.voxrox.mailbackend.feature.contact.dto.ContactCountsResponse;
import org.voxrox.mailbackend.feature.contact.dto.ContactCreateRequest;
import org.voxrox.mailbackend.feature.contact.dto.ContactEmailRequest;
import org.voxrox.mailbackend.feature.contact.dto.ContactLabelCountResponse;
import org.voxrox.mailbackend.feature.contact.dto.ContactMergeRequest;
import org.voxrox.mailbackend.feature.contact.dto.ContactPatchRequest;
import org.voxrox.mailbackend.feature.contact.dto.ContactResponse;
import org.voxrox.mailbackend.feature.contact.dto.ContactUpdateRequest;
import org.voxrox.mailbackend.feature.contact.entity.ContactEmailEntity;
import org.voxrox.mailbackend.feature.contact.entity.ContactEntity;
import org.voxrox.mailbackend.feature.contact.entity.ContactLabelEntity;
import org.voxrox.mailbackend.feature.contact.mapper.ContactMapper;
import org.voxrox.mailbackend.feature.contact.repository.ContactLabelCount;
import org.voxrox.mailbackend.feature.contact.repository.ContactLabelRepository;
import org.voxrox.mailbackend.feature.contact.repository.ContactRepository;
import org.voxrox.mailbackend.util.AuditLog;
import org.voxrox.mailbackend.util.LogCategory;
import org.voxrox.mailbackend.util.LogMasker;
import org.voxrox.mailbackend.util.TextFold;

import module java.base;

@Service
public class ContactService {

    private static final Logger log = LoggerFactory.getLogger(ContactService.class);

    /**
     * Audit actor for address book changes. The book is application-wide, so there
     * is no mailbox identity to attribute the action to — and on a single-user
     * desktop install there is only ever one person doing it.
     */
    static final String AUDIT_ACTOR = "local-user";

    /**
     * The address book's own order, by surname. Package-private because
     * {@link ContactAutocompleteService} fetches its contact half in the same
     * order, so the contacts that make its limit are the ones the listing shows
     * first.
     * <p>
     * By the folded names ({@code TextFold}) first, so a surname starting with a C
     * with a caron sorts among the C names and one written in lower case among the
     * capitalized ones — by the stored names SQLite compares code points and put
     * both after Z. The names themselves only break a tie between two that fold the
     * same (they differ in case or diacritics alone), so those keep one stable
     * order.
     */
    static final Sort DEFAULT_SORT = Sort.by(Sort.Order.asc("surnameFold").nullsLast(),
            Sort.Order.asc("nameFold").nullsLast(), Sort.Order.asc("surname"), Sort.Order.asc("name"),
            Sort.Order.asc("id"));

    private static final Sort SORT_BY_NAME = Sort.by(Sort.Order.asc("nameFold").nullsLast(),
            Sort.Order.asc("surnameFold").nullsLast(), Sort.Order.asc("name"), Sort.Order.asc("surname"),
            Sort.Order.asc("id"));

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
    private final ContactLabelRepository contactLabelRepository;
    private final ContactLabelService contactLabelService;
    private final ContactMapper contactMapper;

    public ContactService(ContactRepository contactRepository, ContactLabelRepository contactLabelRepository,
            ContactLabelService contactLabelService, ContactMapper contactMapper) {
        this.contactRepository = contactRepository;
        this.contactLabelRepository = contactLabelRepository;
        this.contactLabelService = contactLabelService;
        this.contactMapper = contactMapper;
    }

    @Transactional(readOnly = true)
    public Page<ContactResponse> listContacts(int page, int size, String sort, @Nullable Long labelId) {
        ensureLabelExists(labelId);
        Pageable pageable = PageRequest.of(page, size, resolveSort(sort));
        Page<ContactEntity> contacts = contactRepository.findAllFiltered(labelId, pageable);
        return contacts.map(contactMapper::toResponse);
    }

    /**
     * Counts for the sidebar. Every label is listed, so a label created a second
     * ago shows up with a zero badge instead of being invisible until someone puts
     * a contact on it; the aggregate query only knows about labels that are in use.
     */
    @Transactional(readOnly = true)
    public ContactCountsResponse getCounts() {
        long total = contactRepository.count();
        Map<Long, Long> byLabel = contactRepository.countGroupedByLabel().stream()
                .collect(Collectors.toMap(ContactLabelCount::labelId, ContactLabelCount::contacts));
        List<ContactLabelCountResponse> labels = contactLabelRepository.findAllByOrderByNameFoldAscNameKeyAsc().stream()
                .map(l -> new ContactLabelCountResponse(l.getId(), l.getName(), byLabel.getOrDefault(l.getId(), 0L)))
                .toList();
        return new ContactCountsResponse(total, labels);
    }

    /**
     * Rejects an unknown label filter with a 404 instead of quietly returning an
     * empty page — a stale bookmark or a label someone deleted must not look like
     * "this label has no contacts".
     */
    private void ensureLabelExists(@Nullable Long labelId) {
        if (labelId == null) {
            return;
        }
        if (contactLabelRepository.findById(labelId).isEmpty()) {
            throw new ContactLabelNotFoundException(labelId);
        }
    }

    @Transactional(readOnly = true)
    public Page<ContactResponse> searchContacts(String q, int page, int size, String sort, @Nullable Long labelId) {
        if (q == null || q.isBlank()) {
            throw new ValidationException("Contact search query q must not be empty.",
                    "validation.contactQueryRequired");
        }
        ensureLabelExists(labelId);
        Pageable pageable = PageRequest.of(page, size, resolveSort(sort));
        Page<ContactEntity> contacts = contactRepository.search(containsPattern(q.strip().toLowerCase(Locale.ROOT)),
                foldedContainsPattern(q), labelId, pageable);
        return contacts.map(contactMapper::toResponse);
    }

    @Transactional(readOnly = true)
    public ContactResponse getContact(Long contactId) {
        ContactEntity contact = getContactOrThrow(contactId);
        return contactMapper.toResponse(contact);
    }

    /**
     * Exports the entire address book as vCard 4.0 (RFC 6350). Order of contacts
     * matches the listing (surname/name/id NULLS LAST). Returns a plain string —
     * address books are typically &lt;10k entries, streaming would be
     * over-engineering.
     */
    @Transactional(readOnly = true)
    public String exportToVCard() {
        List<ContactEntity> contacts = contactRepository.findAllBy(DEFAULT_SORT);
        AuditLog.success("contact_export", AUDIT_ACTOR, "format=vcard count=" + contacts.size());
        return VCardWriter.write(contacts);
    }

    @Transactional
    public ContactResponse createContact(ContactCreateRequest request) {
        List<String> normalizedEmails = normalizeEmailList(request.emails());
        checkNoDuplicates(null, normalizedEmails);

        Set<ContactLabelEntity> labels = contactLabelService.resolveLabels(request.labelIds());
        ContactEntity entity = contactMapper.toEntity(request, labels);
        ContactEntity saved = contactRepository.save(entity);

        String primaryEmail = primaryEmail(saved);
        log.info("{} Contact created: id={}", LogCategory.ACCOUNT, saved.getId());
        AuditLog.success("contact_create", AUDIT_ACTOR,
                "contact_id=" + saved.getId() + " email=" + LogMasker.maskEmail(primaryEmail));
        return contactMapper.toResponse(saved);
    }

    @Transactional
    public ContactResponse updateContact(Long contactId, ContactUpdateRequest request) {
        ContactEntity entity = getContactOrThrow(contactId);

        List<String> normalizedEmails = normalizeEmailList(request.emails());
        checkNoDuplicates(contactId, normalizedEmails);

        // PUT is replace semantics all the way down: no labelIds means no labels.
        Set<ContactLabelEntity> labels = contactLabelService.resolveLabels(request.labelIds());
        deleteEmailsBeforeReplacing(entity);
        contactMapper.applyUpdate(entity, request, labels);
        ContactEntity saved = contactRepository.save(entity);

        AuditLog.success("contact_update", AUDIT_ACTOR,
                "contact_id=" + saved.getId() + " email=" + LogMasker.maskEmail(primaryEmail(saved)));
        return contactMapper.toResponse(saved);
    }

    @Transactional
    public ContactResponse patchContact(Long contactId, ContactPatchRequest request) {
        ContactEntity entity = getContactOrThrow(contactId);

        if (request.emails() != null) {
            List<String> normalizedEmails = normalizeEmailList(request.emails());
            checkNoDuplicates(contactId, normalizedEmails);
        }

        // PATCH: an absent labelIds keeps the current labels, an empty list clears
        // them — so null has to survive all the way into the mapper.
        Set<ContactLabelEntity> labels = request.labelIds() == null
                ? null
                : contactLabelService.resolveLabels(request.labelIds());
        if (request.emails() != null)
            deleteEmailsBeforeReplacing(entity);
        contactMapper.applyPatch(entity, request, labels);
        ContactEntity saved = contactRepository.save(entity);

        AuditLog.success("contact_patch", AUDIT_ACTOR,
                "contact_id=" + saved.getId() + " email=" + LogMasker.maskEmail(primaryEmail(saved)));
        return contactMapper.toResponse(saved);
    }

    /**
     * Empties the address list and pushes the deletes out before the replacements
     * are added.
     *
     * <p>
     * Replacing addresses is delete-then-insert, and within one flush Hibernate
     * runs the inserts before the orphan deletes. Both indexes on
     * {@code contact_emails} then see a state that only exists mid-flush:
     * {@code ux_contact_emails_contact_primary} sees two primaries whenever the
     * incoming order promotes a different address, and
     * {@code ux_contact_emails_contact_email} sees a duplicate for every address
     * that survives the edit. Either one fails the save with
     * SQLITE_CONSTRAINT_UNIQUE, so <em>every</em> edit of an existing contact did,
     * not only one that moves the primary. The intermediate state is made legal
     * instead of relying on statement order. Covered by
     * {@code ContactRepositoryIT#updateContactReordersPrimary} and
     * {@code #updateContactKeepsOrder}.
     */
    private void deleteEmailsBeforeReplacing(ContactEntity entity) {
        if (entity.getEmails().isEmpty())
            return;
        entity.getEmails().clear();
        contactRepository.flush();
    }

    @Transactional
    public void deleteContact(Long contactId) {
        ContactEntity entity = getContactOrThrow(contactId);
        String maskedEmail = LogMasker.maskEmail(primaryEmail(entity));

        contactRepository.delete(entity);

        AuditLog.success("contact_delete", AUDIT_ACTOR, "contact_id=" + contactId + " email=" + maskedEmail);
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
    public ContactResponse merge(Long targetId, ContactMergeRequest request) {

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

        ContactEntity target = getContactOrThrow(targetId);
        List<ContactEntity> sources = new ArrayList<>(uniqueSources.size());
        for (Long sid : uniqueSources) {
            sources.add(getContactOrThrow(sid));
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

        /*
         * Labels merge as a union — the sources are about to be deleted, so a label
         * only they carried would otherwise be silently lost. Unlike e-mails there is
         * no dedup question: the Set and the entity's id-based equals handle it.
         */
        Set<ContactLabelEntity> mergedLabels = new LinkedHashSet<>(target.getLabels());
        for (ContactEntity src : sources) {
            mergedLabels.addAll(src.getLabels());
        }
        if (mergedLabels.size() > ContactLabelService.MAX_LABELS_PER_CONTACT) {
            throw new ValidationException(
                    "After merging, the contact would carry " + mergedLabels.size() + " labels; the maximum is "
                            + ContactLabelService.MAX_LABELS_PER_CONTACT + ".",
                    "validation.contactLabel.tooManyPerContact", ContactLabelService.MAX_LABELS_PER_CONTACT);
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
            target.getEmails().getFirst().setPrimary(true);
        }
        target.setNote(mergedNote);
        target.getLabels().addAll(mergedLabels);

        for (ContactEntity src : sources) {
            // The source's own label assignments need no explicit clearing —
            // Hibernate owns the join table from the contact side and deletes its
            // rows with the contact (ContactRepositoryIT#deletingContactKeepsLabel).
            contactRepository.delete(src);
        }
        ContactEntity saved = contactRepository.save(target);

        log.info("{} Contact merge: target={} sources={} final_emails={}", LogCategory.ACCOUNT, targetId,
                uniqueSources.size(), saved.getEmails().size());
        AuditLog.success("contact_merge", AUDIT_ACTOR, "target=" + targetId + " sources=" + uniqueSources.size()
                + " final_emails=" + saved.getEmails().size());

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

    static String containsPattern(String text) {
        return "%" + text + "%";
    }

    /**
     * The name half of {@link ContactRepository#search}: the query folded the way
     * the stored names are. A query that folds to nothing (combining marks alone)
     * falls back to the lower-cased form rather than to {@code %%}, which would
     * match every contact with a name.
     */
    static String foldedContainsPattern(String q) {
        String folded = TextFold.fold(q);
        return containsPattern(folded.isEmpty() ? q.toLowerCase(Locale.ROOT) : folded);
    }

    private ContactEntity getContactOrThrow(Long contactId) {
        return contactRepository.findById(contactId).orElseThrow(() -> new ContactNotFoundException(contactId));
    }

    /**
     * Verifies that no email in the list is used by another contact.
     * {@code excludeContactId} is the ID of the contact currently being edited (its
     * own emails are fine); pass {@code null} for create.
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
    private void checkNoDuplicates(@Nullable Long excludeContactId, List<String> emails) {
        Set<String> seen = new HashSet<>();
        for (String email : emails) {
            if (!seen.add(email)) {
                // Internal message is log-bound (GlobalExceptionHandler, bulk
                // results) -> masked; the localized client response carries the
                // raw argument.
                throw new ValidationException(
                        "E-mail " + LogMasker.maskEmail(email) + " appears more than once in the list.",
                        "validation.contact.emailDuplicateInRequest", email);
            }
        }

        List<ContactEntity> matches = contactRepository.findByAnyEmailIn(seen);
        for (String email : seen) {
            matches.stream().filter(other -> excludeContactId == null || !other.getId().equals(excludeContactId))
                    .filter(other -> other.getEmails().stream().anyMatch(e -> email.equals(e.getEmail()))).findFirst()
                    .ifPresent(_ -> {
                        throw new DuplicateContactException(email);
                    });
        }
    }

    private List<String> normalizeEmailList(List<ContactEmailRequest> emails) {
        // Each e.email() is @NotBlank-validated, so the normalized forms exist.
        return emails.stream().map(e -> Objects.requireNonNull(contactMapper.normalizeEmail(e.email()))).toList();
    }

    private static String primaryEmail(ContactEntity entity) {
        return entity.getEmails().stream().filter(e -> e.isPrimary()).findFirst().map(e -> e.getEmail())
                .orElseGet(() -> entity.getEmails().isEmpty() ? "unknown" : entity.getEmails().getFirst().getEmail());
    }
}
