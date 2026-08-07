package org.voxrox.mailbackend.feature.contact.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.voxrox.mailbackend.feature.account.entity.AccountEntity;
import org.voxrox.mailbackend.feature.contact.entity.CorrespondentEntity;
import org.voxrox.mailbackend.feature.contact.repository.CorrespondentRepository;
import org.voxrox.mailbackend.feature.mail.dto.FolderRole;
import org.voxrox.mailbackend.feature.mail.entity.MessageEntity;

/**
 * Harvests the addresses the account exchanges mail with, and serves them to the
 * compose typeahead.
 *
 * <p>
 * The point is a fresh install: the address book starts empty and only fills by
 * hand or by a vCard import, so without this the typeahead has nothing to offer
 * and the user retypes the same address for the fiftieth time. The data is
 * already there — the app syncs mail, so senders and recipients sit in
 * {@code messages}.
 *
 * <p>
 * Nothing here writes to the address book. {@code correspondent} is a separate,
 * droppable cache; contacts stay hand-curated so labels and merge keep operating
 * on a set the user chose.
 */
@Service
public class CorrespondentService {

    /**
     * Local parts that are machines rather than people. Filtered out of the
     * typeahead by {@code CorrespondentRepository.search} — but only for addresses
     * the user has never written to, because writing to one makes it a real
     * correspondent whatever its local part says.
     *
     * <p>
     * Harvesting stores them anyway. The filter is a display decision and this is a
     * rebuildable cache, so keeping the rows means changing the list later is a
     * one-line edit rather than a forced rebuild.
     */
    public static final Set<String> ROBOT_LOCAL_PARTS = Set.of("no-reply", "noreply", "donotreply", "do-not-reply",
            "bounce", "bounces", "mailer-daemon", "postmaster");

    /**
     * Folder roles the harvest ignores.
     *
     * <p>
     * Trash and Junk are the two places whose whole meaning is "the user did not
     * want this", and Newsletters is bulk mail by definition — collecting from any
     * of them would feed the typeahead exactly what the robot filter exists to keep
     * out. All three are synced, so this list is what actually keeps them out.
     *
     * <p>
     * In practice that leaves Inbox, Sent and Drafts: {@code MailSyncService} syncs
     * one folder per role and only those six roles, so Archive and user-created
     * folders never reach the database at all. They are not listed here because if
     * that ever changes, filed correspondence is exactly what the typeahead should
     * be learning from — the omission is deliberate, not an oversight.
     */
    private static final Set<FolderRole> SKIPPED_ROLES = Set.of(FolderRole.TRASH, FolderRole.JUNK,
            FolderRole.NEWSLETTERS);

    /** Matches the column widths; anything longer is dropped rather than truncated. */
    private static final int MAX_EMAIL_LENGTH = 255;
    private static final int MAX_DISPLAY_NAME_LENGTH = 255;

    private final CorrespondentRepository correspondentRepository;

    public CorrespondentService(CorrespondentRepository correspondentRepository) {
        this.correspondentRepository = correspondentRepository;
    }

    /**
     * Records the correspondents of one freshly persisted message.
     *
     * <p>
     * Direction comes from the folder role, which is what makes the sent/received
     * split possible at all: in the user's own Sent and Drafts copies the
     * <em>recipients</em> are the correspondents (the sender is the user), and
     * everywhere else it is the <em>sender</em>.
     *
     * <p>
     * Incoming mail deliberately contributes only its sender. Its To/Cc carry the
     * user's own address plus, on any bulk message, a crowd of strangers who happen
     * to have received the same mail — people the user has never corresponded with.
     * Sent copies have no such problem, so there To, Cc and Bcc all count.
     *
     * <p>
     * Runs inside the caller's transaction (the sync batch, or one backfill batch).
     * Failures are the caller's to handle: this is a cache, and a sync must not fail
     * because a header would not parse — which is why parsing itself never throws.
     */
    @Transactional
    public void harvestFromMessage(MessageEntity message, AccountEntity account, FolderRole role) {
        harvest(account, new HarvestInput(role, message.getReceivedAt(), message.getSender(),
                message.getRecipientsTo(), message.getRecipientsCc(), message.getRecipientsBcc()));
    }

    /**
     * The address headers of one message, decoupled from {@code MessageEntity} so
     * the backfill can feed the same logic from a projection that leaves the
     * {@code @Lob} body in the database.
     */
    public record HarvestInput(FolderRole role, LocalDateTime seenAt, @Nullable String sender,
            @Nullable String recipientsTo, @Nullable String recipientsCc, @Nullable String recipientsBcc) {
    }

    @Transactional
    public void harvest(AccountEntity account, HarvestInput input) {
        if (SKIPPED_ROLES.contains(input.role())) {
            return;
        }
        boolean outgoing = input.role() == FolderRole.SENT || input.role() == FolderRole.DRAFTS;
        List<HarvestedAddress> addresses = outgoing
                ? parseAll(input.recipientsTo(), input.recipientsCc(), input.recipientsBcc())
                : parseAll(input.sender());
        if (addresses.isEmpty()) {
            return;
        }

        String ownEmail = normalizeEmail(account.getEmail());
        for (HarvestedAddress address : addresses) {
            if (address.email().equals(ownEmail)) {
                // Self-addressed copies are noise in a typeahead: the one address
                // the user never needs suggested is their own.
                continue;
            }
            correspondentRepository.upsert(account.getId(), address.email(), address.displayName(), outgoing ? 1 : 0,
                    outgoing ? 0 : 1, input.seenAt());
        }
    }

    /**
     * Typeahead lookup. Returns at most {@code limit} harvested addresses ranked by
     * {@code CorrespondentRepository.search}; an empty or blank query yields nothing
     * rather than the whole table.
     */
    @Transactional(readOnly = true)
    public List<CorrespondentEntity> search(Long accountId, String q, int limit) {
        String normalized = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty() || limit <= 0) {
            return List.of();
        }
        String escaped = escapeLikeWildcards(normalized);
        return correspondentRepository.search(accountId, escaped + "%", "%" + escaped + "%", ROBOT_LOCAL_PARTS, limit);
    }

    /**
     * Neutralizes the LIKE wildcards in a user-typed query. Without this a query
     * containing {@code %} matches everything and {@code _} matches any character —
     * the pattern is built from whatever the user typed into the address field.
     * Pairs with the {@code ESCAPE '\'} clause in the query.
     */
    public static String escapeLikeWildcards(String raw) {
        return raw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    static String normalizeEmail(String raw) {
        return raw.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Parses several address header fields into one deduplicated list, first
     * sighting of an address winning. Deduplication matters because To and Cc of the
     * same message routinely repeat an address, and each duplicate would otherwise
     * count as a separate sighting and inflate the ranking.
     */
    private static List<HarvestedAddress> parseAll(@Nullable String... rawFields) {
        Map<String, HarvestedAddress> byEmail = new LinkedHashMap<>();
        for (String raw : rawFields) {
            for (HarvestedAddress parsed : parseField(raw)) {
                byEmail.putIfAbsent(parsed.email(), parsed);
            }
        }
        return List.copyOf(byEmail.values());
    }

    /**
     * Extracts the complete addresses from one raw header field.
     *
     * <p>
     * Header fields are raw text, not a comma-separated list of addresses: a display
     * name may itself contain a comma ({@code "Novak, Jan" <j@x.cz>}), which is
     * exactly what a naive split gets wrong. {@link InternetAddress#parseHeader}
     * tokenizes without validating and the per-token {@link InternetAddress#validate()}
     * decides — the same shape {@code MimeMessageBuilder.parseValidTokens} uses,
     * and for the same reason: one malformed token must not discard the rest of the
     * field. A field that will not tokenize at all yields nothing; this is a cache
     * and a sync must not fail over a bad header.
     */
    private static List<HarvestedAddress> parseField(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        InternetAddress[] tokens;
        try {
            tokens = InternetAddress.parseHeader(raw, false);
        } catch (AddressException e) {
            return List.of();
        }
        List<HarvestedAddress> parsed = new ArrayList<>(tokens.length);
        for (InternetAddress token : tokens) {
            try {
                token.validate();
            } catch (AddressException e) {
                continue;
            }
            String address = token.getAddress();
            if (address == null || address.isBlank()) {
                continue;
            }
            String email = normalizeEmail(address);
            if (email.length() > MAX_EMAIL_LENGTH) {
                continue;
            }
            parsed.add(new HarvestedAddress(email, cleanDisplayName(token.getPersonal(), email)));
        }
        return parsed;
    }

    /**
     * Normalizes the display name, or returns {@code null} when there is nothing
     * worth storing. A name that merely repeats the address adds nothing to a
     * suggestion that already shows the address, and an over-long one is dropped
     * rather than truncated mid-word.
     */
    private static @Nullable String cleanDisplayName(@Nullable String personal, String email) {
        if (personal == null) {
            return null;
        }
        String trimmed = personal.trim();
        if (trimmed.isEmpty() || trimmed.length() > MAX_DISPLAY_NAME_LENGTH) {
            return null;
        }
        return trimmed.equalsIgnoreCase(email) ? null : trimmed;
    }

    private record HarvestedAddress(String email, @Nullable String displayName) {
    }
}
