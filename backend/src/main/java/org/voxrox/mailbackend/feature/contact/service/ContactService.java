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
import org.voxrox.mailbackend.exception.ResourceNotFoundException;
import org.voxrox.mailbackend.exception.ValidationException;
import org.voxrox.mailbackend.feature.account.service.AccountService;
import org.voxrox.mailbackend.feature.contact.EmailLabel;
import org.voxrox.mailbackend.feature.contact.dto.ContactAutocompleteResponse;
import org.voxrox.mailbackend.feature.contact.dto.ContactCountsResponse;
import org.voxrox.mailbackend.feature.contact.dto.ContactCreateRequest;
import org.voxrox.mailbackend.feature.contact.dto.ContactEmailRequest;
import org.voxrox.mailbackend.feature.contact.dto.ContactEmailResponse;
import org.voxrox.mailbackend.feature.contact.dto.ContactLabelCountResponse;
import org.voxrox.mailbackend.feature.contact.dto.ContactMergeRequest;
import org.voxrox.mailbackend.feature.contact.dto.ContactPatchRequest;
import org.voxrox.mailbackend.feature.contact.dto.ContactResponse;
import org.voxrox.mailbackend.feature.contact.dto.ContactUpdateRequest;
import org.voxrox.mailbackend.feature.contact.entity.ContactEmailEntity;
import org.voxrox.mailbackend.feature.contact.entity.ContactEntity;
import org.voxrox.mailbackend.feature.contact.entity.ContactLabelEntity;
import org.voxrox.mailbackend.feature.contact.entity.CorrespondentEntity;
import org.voxrox.mailbackend.feature.contact.mapper.ContactMapper;
import org.voxrox.mailbackend.feature.contact.repository.ContactLabelCount;
import org.voxrox.mailbackend.feature.contact.repository.ContactLabelRepository;
import org.voxrox.mailbackend.feature.contact.repository.ContactRepository;
import org.voxrox.mailbackend.util.AuditLog;
import org.voxrox.mailbackend.util.LogCategory;
import org.voxrox.mailbackend.util.LogMasker;

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
    private final ContactLabelRepository contactLabelRepository;
    private final ContactLabelService contactLabelService;
    private final CorrespondentService correspondentService;
    private final AccountService accountService;
    private final ContactMapper contactMapper;

    public ContactService(ContactRepository contactRepository, ContactLabelRepository contactLabelRepository,
            ContactLabelService contactLabelService, CorrespondentService correspondentService,
            AccountService accountService, ContactMapper contactMapper) {
        this.contactRepository = contactRepository;
        this.contactLabelRepository = contactLabelRepository;
        this.contactLabelService = contactLabelService;
        this.correspondentService = correspondentService;
        this.accountService = accountService;
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
        List<ContactLabelCountResponse> labels = contactLabelRepository.findAllByOrderByNameKeyAsc().stream()
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

    /**
     * Autocomplete for the compose-window typeahead. Returns a flat list of
     * addresses ranked by relevance to the {@code q} prefix:
     * <ol>
     * <li>email prefix match</li>
     * <li>surname prefix match</li>
     * <li>name prefix match</li>
     * <li>other substring matches</li>
     * </ol>
     *
     * <p>
     * Two sources feed it: the address book, and the addresses harvested from
     * message headers ({@link CorrespondentService}). The second exists because the
     * first starts empty — a new install has nothing to suggest until the user
     * types every address by hand at least once, while the mail it has already
     * synced is full of the people they actually write to.
     *
     * <p>
     * <b>Merging belongs here, not in the client.</b> An address in both sources
     * may appear only once, and the contact has to win because it is what the user
     * curated; and {@code limit} has to apply to the merged list, otherwise a
     * client asking for 10 from each source and stitching them together shows 13
     * and the limit means nothing.
     *
     * <p>
     * The two are interleaved by rank rather than concatenated as blocks: a history
     * row matching the address prefix outranks a contact that only matched on a
     * substring of its name. Within one rank contacts come first, and each source
     * keeps its own order — alphabetical for contacts, and for history the ranking
     * the query already applied (written-to first, then recency).
     *
     * <p>
     * Hard-capped at limit 20; caller enforces {@code @Min(1)} on the controller.
     * Each source is queried for {@code limit} rows: from below that covers
     * {@code limit} results even when every contact has one address, from above the
     * pool may overflow with foreign addresses of contacts that matched on name. We
     * finally trim to {@code limit}.
     */
    @Transactional(readOnly = true)
    public List<ContactAutocompleteResponse> autocomplete(Long accountId, String q, int limit) {
        if (q == null || q.isBlank()) {
            throw new ValidationException("Contact search query q must not be empty.",
                    "validation.contactQueryRequired");
        }
        // Still validated even though the address book half ignores it: the history
        // half is that mailbox's own correspondence, so an unknown account is a bad
        // request, not an empty history.
        accountService.getAccountOrThrow(accountId);

        int cappedLimit = Math.min(Math.max(limit, 1), AUTOCOMPLETE_MAX_LIMIT);
        // Trimmed as well as folded: CorrespondentService.search trims internally,
        // so without it a query with leading whitespace searches the two sources
        // for different strings — and the rank functions, comparing against the
        // untrimmed form, would then score a genuine prefix hit from history as a
        // substring match and sort it below every weak contact match.
        String qLower = q.trim().toLowerCase(Locale.ROOT);

        List<AutocompleteRow> rows = new ArrayList<>(contactRows(qLower, cappedLimit));
        Set<String> offered = rows.stream().map(r -> r.response().email().toLowerCase(Locale.ROOT))
                .collect(Collectors.toCollection(HashSet::new));
        rows.addAll(historyRows(accountId, qLower, cappedLimit, offered));

        rows.sort(AUTOCOMPLETE_RANKING);
        return rows.stream().limit(cappedLimit).map(AutocompleteRow::response).toList();
    }

    /**
     * The address book half. Not account-scoped — the whole point of a single
     * address book is that composing from any mailbox can reach every contact the
     * user saved.
     */
    private List<AutocompleteRow> contactRows(String qLower, int cappedLimit) {
        Pageable pageable = PageRequest.of(0, cappedLimit, DEFAULT_SORT);
        List<ContactEntity> matched = contactRepository.search("%" + qLower + "%", (Long) null, pageable).getContent();

        List<AutocompleteRow> ranked = matched.stream()
                .flatMap(c -> c.getEmails().stream().map(e -> toContactRow(c, e, qLower))).sorted(CONTACT_ORDER)
                .limit(cappedLimit).toList();

        // Stamp each row with the position it landed on, so the merge preserves this
        // alphabetical order. The rank travels with the row rather than being
        // recomputed: deriving the sort key and the merge key separately would let a
        // change to one ranking rule produce a list sorted by one definition and
        // merged by another.
        List<AutocompleteRow> rows = new ArrayList<>(ranked.size());
        for (int i = 0; i < ranked.size(); i++) {
            AutocompleteRow row = ranked.get(i);
            rows.add(new AutocompleteRow(row.rank(), CONTACT_FIRST, i, row.response()));
        }
        return rows;
    }

    /**
     * Harvested addresses that the contact half did not already offer.
     * {@code offered} is mutated as we go: the same address can be reached twice
     * (once by address prefix, once by display name) and a duplicate suggestion is
     * worse than a missing one.
     */
    private List<AutocompleteRow> historyRows(Long accountId, String qLower, int cappedLimit, Set<String> offered) {
        List<CorrespondentEntity> harvested = correspondentService.search(accountId, qLower, cappedLimit);
        List<AutocompleteRow> rows = new ArrayList<>(harvested.size());
        for (CorrespondentEntity c : harvested) {
            // Stored already normalized (trimmed, lower-cased) by the harvest.
            if (!offered.add(c.getEmail())) {
                continue;
            }
            ContactAutocompleteResponse resp = ContactAutocompleteResponse.ofHistory(c.getEmail(), c.getDisplayName());
            rows.add(new AutocompleteRow(rankOfHistory(c, qLower), HISTORY_SECOND, rows.size(), resp));
        }
        return rows;
    }

    private static final int AUTOCOMPLETE_MAX_LIMIT = 20;

    /** Source precedence within one rank — the curated entry wins a tie. */
    private static final int CONTACT_FIRST = 0;
    private static final int HISTORY_SECOND = 1;

    /**
     * Alphabetical order used inside the contact half before the merge. The
     * nullable name fields are read into a local before use — reading the accessor
     * twice (once to null-check, once to fold) is what SpotBugs flags, since
     * nothing tells it the second call returns the same value.
     */
    private static final java.util.Comparator<AutocompleteRow> CONTACT_ORDER = java.util.Comparator
            .<AutocompleteRow>comparingInt(AutocompleteRow::rank)
            .thenComparing(r -> lowerOrEmpty(r.response().surname()))
            .thenComparing(r -> lowerOrEmpty(r.response().name()))
            .thenComparing(r -> r.response().email().toLowerCase(Locale.ROOT));

    private static String lowerOrEmpty(@Nullable String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private static final java.util.Comparator<AutocompleteRow> AUTOCOMPLETE_RANKING = java.util.Comparator
            .<AutocompleteRow>comparingInt(AutocompleteRow::rank).thenComparingInt(AutocompleteRow::sourceOrder)
            .thenComparingInt(AutocompleteRow::orderInSource);

    private static int rankOfContact(ContactAutocompleteResponse resp, String qLower) {
        String emailLower = resp.email().toLowerCase(Locale.ROOT);
        String surnameLower = lowerOrEmpty(resp.surname());
        String nameLower = lowerOrEmpty(resp.name());
        if (emailLower.startsWith(qLower)) {
            return 0;
        }
        if (!surnameLower.isEmpty() && surnameLower.startsWith(qLower)) {
            return 1;
        }
        if (!nameLower.isEmpty() && nameLower.startsWith(qLower)) {
            return 2;
        }
        return 3;
    }

    /**
     * Same scale as {@link #rankOfContact} so the two sources interleave
     * meaningfully — a harvested address whose local part starts with the query is
     * as good a match as a contact's would be. Rank 1 (surname) is unreachable
     * here: a harvested display name is one undivided string, so a name hit is
     * scored as rank 2 when it starts with the query and 3 when it merely contains
     * it.
     */
    private static int rankOfHistory(CorrespondentEntity correspondent, String qLower) {
        if (correspondent.getEmail().startsWith(qLower)) {
            return 0;
        }
        String nameLower = lowerOrEmpty(correspondent.getDisplayName());
        return !nameLower.isEmpty() && nameLower.startsWith(qLower) ? 2 : 3;
    }

    private static AutocompleteRow toContactRow(ContactEntity contact, ContactEmailEntity email, String qLower) {
        ContactAutocompleteResponse resp = ContactAutocompleteResponse.ofContact(contact.getId(), email.getId(),
                email.getEmail(), email.getLabel(), email.isPrimary(), contact.getName(), contact.getSurname());
        return new AutocompleteRow(rankOfContact(resp, qLower), CONTACT_FIRST, 0, resp);
    }

    /**
     * @param sourceOrder
     *            {@link #CONTACT_FIRST} or {@link #HISTORY_SECOND} — breaks a rank
     *            tie in favour of the address book
     * @param orderInSource
     *            position within that source's own ordering, which the merge
     *            preserves instead of flattening both sources into one sort key
     */
    private record AutocompleteRow(int rank, int sourceOrder, int orderInSource, ContactAutocompleteResponse response) {
    }

    @Transactional(readOnly = true)
    public Page<ContactResponse> searchContacts(String q, int page, int size, String sort, @Nullable Long labelId) {
        if (q == null || q.isBlank()) {
            throw new ValidationException("Contact search query q must not be empty.",
                    "validation.contactQueryRequired");
        }
        ensureLabelExists(labelId);
        String pattern = "%" + q.toLowerCase(Locale.ROOT) + "%";
        Pageable pageable = PageRequest.of(page, size, resolveSort(sort));
        Page<ContactEntity> contacts = contactRepository.search(pattern, labelId, pageable);
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
     * not only one that moves the primary. Same shape as the demote-flush-promote
     * in {@link #setPrimaryEmail}: make the intermediate state legal instead of
     * relying on statement order. Covered by
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
     * Adds a new e-mail address to a contact. Does not change the primary flag of
     * existing addresses — the new email is added as non-primary. Follows the
     * {@link #checkNoDuplicates} convention: a duplicate within this contact's own
     * list → {@link ValidationException} (400, the client fixes its own form), a
     * collision with another contact → {@link DuplicateContactException} (409,
     * conflict with an existing record).
     */
    @Transactional
    public ContactEmailResponse addEmail(Long contactId, ContactEmailRequest request) {
        ContactEntity entity = getContactOrThrow(contactId);

        // request.email() is @NotBlank-validated, so the normalized form exists.
        String normalized = Objects.requireNonNull(contactMapper.normalizeEmail(request.email()));
        if (entity.getEmails().stream().anyMatch(e -> normalized.equals(e.getEmail()))) {
            // Internal message is log-bound (GlobalExceptionHandler) -> masked;
            // the localized client response carries the raw argument.
            throw new ValidationException(
                    "The contact already has the e-mail address " + LogMasker.maskEmail(normalized) + ".",
                    "validation.contact.emailAlreadyOnContact", normalized);
        }
        contactRepository.findByAnyEmail(normalized).stream().filter(other -> !other.getId().equals(contactId))
                .findFirst().ifPresent(other -> {
                    throw new DuplicateContactException(normalized);
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

        AuditLog.success("contact_email_add", AUDIT_ACTOR,
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
    public void deleteEmail(Long contactId, Long emailId) {
        ContactEntity entity = getContactOrThrow(contactId);

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

        AuditLog.success("contact_email_delete", AUDIT_ACTOR,
                "contact_id=" + contactId + " email=" + LogMasker.maskEmail(removedEmail));
    }

    /**
     * Marks the chosen address as primary. The contact's other addresses get
     * {@code primary=false}. 404 when the address does not belong to the contact.
     */
    @Transactional
    public ContactResponse setPrimaryEmail(Long contactId, Long emailId) {
        ContactEntity entity = getContactOrThrow(contactId);

        ContactEmailEntity target = entity.getEmails().stream().filter(e -> emailId.equals(e.getId())).findFirst()
                .orElseThrow(() -> new ResourceNotFoundException(
                        "E-mail with id=" + emailId + " for contact " + contactId + " not found."));

        // Demote the current primary and flush first, so the DB briefly holds zero
        // primaries, before promoting the target. A single-pass flag swap lets
        // Hibernate flush the promote (->primary) UPDATE ahead of the demote
        // (->non-primary) UPDATE when the promoted row sorts first by id, producing a
        // transient two-primaries state that trips the partial unique index
        // ux_contact_emails_contact_primary (see
        // ContactRepositoryIT#promoteLowerIdEmailToPrimary).
        boolean demotedAny = false;
        for (ContactEmailEntity e : entity.getEmails()) {
            if (e.isPrimary() && !emailId.equals(e.getId())) {
                e.setPrimary(false);
                demotedAny = true;
            }
        }
        if (demotedAny) {
            contactRepository.saveAndFlush(entity);
        }
        target.setPrimary(true);

        ContactEntity saved = contactRepository.save(entity);

        AuditLog.success("contact_email_set_primary", AUDIT_ACTOR,
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
            target.getEmails().get(0).setPrimary(true);
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
                    .ifPresent(other -> {
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
                .orElseGet(() -> entity.getEmails().isEmpty() ? "unknown" : entity.getEmails().get(0).getEmail());
    }
}
