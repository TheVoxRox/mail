package org.voxrox.mailbackend.feature.contact.service;

import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.voxrox.mailbackend.exception.ValidationException;
import org.voxrox.mailbackend.feature.account.service.AccountService;
import org.voxrox.mailbackend.feature.contact.dto.ContactAutocompleteResponse;
import org.voxrox.mailbackend.feature.contact.entity.ContactEmailEntity;
import org.voxrox.mailbackend.feature.contact.entity.ContactEntity;
import org.voxrox.mailbackend.feature.contact.entity.CorrespondentEntity;
import org.voxrox.mailbackend.feature.contact.repository.ContactRepository;
import org.voxrox.mailbackend.util.TextFold;

import module java.base;

/**
 * The compose-window typeahead: the address book and the addresses harvested
 * from synced mail, merged into one ranked list. It is the one part of the
 * contacts feature that reads a mailbox, for the history it blends in, which is
 * why it lives apart from {@link ContactService}: the address book itself is
 * application-wide and needs neither the account nor the harvest.
 */
@Service
public class ContactAutocompleteService {

    private static final int AUTOCOMPLETE_MAX_LIMIT = 20;

    /** Source precedence within one rank — the curated entry wins a tie. */
    private static final int CONTACT_FIRST = 0;
    private static final int HISTORY_SECOND = 1;

    /**
     * Alphabetical order used inside the contact half before the merge — the
     * address book's own (ContactService.DEFAULT_SORT): folded surname, then folded
     * given name, a missing one last.
     */
    private static final Comparator<AutocompleteRow> CONTACT_ORDER = Comparator
            .<AutocompleteRow>comparingInt(AutocompleteRow::rank)
            .thenComparing(r -> TextFold.key(r.response().surname()), Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(r -> TextFold.key(r.response().name()), Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(r -> r.response().email().toLowerCase(Locale.ROOT));

    private static final Comparator<AutocompleteRow> AUTOCOMPLETE_RANKING = Comparator
            .<AutocompleteRow>comparingInt(AutocompleteRow::rank).thenComparingInt(AutocompleteRow::sourceOrder)
            .thenComparingInt(AutocompleteRow::orderInSource);

    private final ContactRepository contactRepository;
    private final CorrespondentService correspondentService;
    private final AccountService accountService;

    public ContactAutocompleteService(ContactRepository contactRepository, CorrespondentService correspondentService,
            AccountService accountService) {
        this.contactRepository = contactRepository;
        this.correspondentService = correspondentService;
        this.accountService = accountService;
    }

    /**
     * Autocomplete for the compose-window typeahead. Returns a flat list of
     * addresses ranked by relevance to the {@code q} prefix:
     * <ol>
     * <li>email prefix match</li>
     * <li>surname prefix match ("surname name", so "novak j" counts)</li>
     * <li>name prefix match ("name surname", so "jan no" counts)</li>
     * <li>other matches: every word of the query somewhere in the contact</li>
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

        int cappedLimit = Math.clamp(limit, 1, AUTOCOMPLETE_MAX_LIMIT);
        // Trimmed as well as folded: CorrespondentService.search trims internally,
        // so without it a query with leading whitespace searches the two sources
        // for different strings — and the rank functions, comparing against the
        // untrimmed form, would then score a genuine prefix hit from history as a
        // substring match and sort it below every weak contact match.
        String qLower = q.trim().toLowerCase(Locale.ROOT);
        // The names are compared folded (TextFold), so "cer" ranks a surname that
        // starts with a C with a caron as the surname prefix match it is. The
        // history half keeps the whole folded query, the form its own SQL matches.
        String qFold = TextFold.fold(qLower);

        List<AutocompleteRow> rows = new ArrayList<>(contactRows(ContactSearchTerms.of(qLower), cappedLimit));
        Set<String> offered = rows.stream().map(r -> r.response().email().toLowerCase(Locale.ROOT))
                .collect(Collectors.toCollection(HashSet::new));
        rows.addAll(historyRows(accountId, qLower, qFold, cappedLimit, offered));

        rows.sort(AUTOCOMPLETE_RANKING);
        return rows.stream().limit(cappedLimit).map(AutocompleteRow::response).toList();
    }

    /**
     * The address book half. Not account-scoped — the whole point of a single
     * address book is that composing from any mailbox can reach every contact the
     * user saved. Fetched best match first
     * ({@code ContactRepository.findForTypeahead}), so the limit keeps the
     * strongest contacts rather than the alphabetically first ones, and ranked here
     * per address by the same rules.
     */
    private List<AutocompleteRow> contactRows(ContactSearchTerms terms, int cappedLimit) {
        if (terms.isEmpty()) {
            return List.of();
        }
        List<ContactEntity> matched = contactRepository.findForTypeahead(Objects.requireNonNull(terms.word(0)),
                terms.word(1), terms.word(2), terms.word(3), terms.phrasePrefix(), PageRequest.of(0, cappedLimit));

        String phrase = terms.phrase();
        List<AutocompleteRow> ranked = matched.stream()
                .flatMap(c -> c.getEmails().stream().map(e -> toContactRow(c, e, phrase))).sorted(CONTACT_ORDER)
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
    private List<AutocompleteRow> historyRows(Long accountId, String qLower, String qFold, int cappedLimit,
            Set<String> offered) {
        List<CorrespondentEntity> harvested = correspondentService.search(accountId, qLower, cappedLimit);
        List<AutocompleteRow> rows = new ArrayList<>(harvested.size());
        for (CorrespondentEntity c : harvested) {
            // Stored already normalized (trimmed, lower-cased) by the harvest.
            if (!offered.add(c.getEmail())) {
                continue;
            }
            ContactAutocompleteResponse resp = ContactAutocompleteResponse.ofHistory(c.getId(), c.getEmail(),
                    c.getDisplayName());
            rows.add(new AutocompleteRow(rankOfHistory(c, qLower, qFold), HISTORY_SECOND, rows.size(), resp));
        }
        return rows;
    }

    /**
     * Ranks one address of a contact against {@code phrase}, the query's folded
     * words joined by single spaces ({@code ContactSearchTerms.phrase}). The SQL
     * that picked the contact ranks it by the same three prefixes, so the contacts
     * the limit keeps are the ones that rank highest here. The names are compared
     * in their stored folded form: a name the SQL found by "cer" has to rank by the
     * same rule, or every surname with a diacritic would score as a mere substring
     * match.
     */
    private static int rankOfContact(ContactEntity contact, ContactEmailEntity email, String phrase) {
        if (email.getEmail().toLowerCase(Locale.ROOT).startsWith(phrase)) {
            return 0;
        }
        if (startsWith(joined(contact.getSurnameFold(), contact.getNameFold()), phrase)) {
            return 1;
        }
        if (startsWith(joined(contact.getNameFold(), contact.getSurnameFold()), phrase)) {
            return 2;
        }
        return 3;
    }

    /**
     * {@code first}, then {@code second} after a space when there is one; null when
     * {@code first} is — the CONCAT and COALESCE of {@code findForTypeahead}'s
     * rank, written in Java.
     */
    private static @Nullable String joined(@Nullable String first, @Nullable String second) {
        if (first == null) {
            return null;
        }
        return second == null ? first : first + " " + second;
    }

    private static boolean startsWith(@Nullable String folded, String qFold) {
        return folded != null && !qFold.isEmpty() && folded.startsWith(qFold);
    }

    /**
     * Same scale as {@link #rankOfContact} so the two sources interleave
     * meaningfully — a harvested address whose local part starts with the query is
     * as good a match as a contact's would be. Rank 1 (surname) is unreachable
     * here: a harvested display name is one undivided string, so a name hit is
     * scored as rank 2 when it starts with the query and 3 when it merely contains
     * it.
     */
    private static int rankOfHistory(CorrespondentEntity correspondent, String qLower, String qFold) {
        if (correspondent.getEmail().startsWith(qLower)) {
            return 0;
        }
        return startsWith(TextFold.key(correspondent.getDisplayName()), qFold) ? 2 : 3;
    }

    private static AutocompleteRow toContactRow(ContactEntity contact, ContactEmailEntity email, String phrase) {
        ContactAutocompleteResponse resp = ContactAutocompleteResponse.ofContact(contact.getId(), email.getId(),
                email.getEmail(), email.getLabel(), email.isPrimary(), contact.getName(), contact.getSurname());
        return new AutocompleteRow(rankOfContact(contact, email, phrase), CONTACT_FIRST, 0, resp);
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
}
