package org.voxrox.mailbackend.feature.mail.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.voxrox.mailbackend.feature.account.entity.AccountEntity;
import org.voxrox.mailbackend.feature.mail.dto.ThreadUpdated;
import org.voxrox.mailbackend.feature.mail.entity.MessageEntity;
import org.voxrox.mailbackend.feature.mail.entity.MessageReferenceEntity;
import org.voxrox.mailbackend.feature.mail.repository.MessageReferenceRepository;
import org.voxrox.mailbackend.feature.mail.repository.MessageRepository;
import org.voxrox.mailbackend.util.LogCategory;
import org.voxrox.mailbackend.util.SubjectNormalizer;
import org.voxrox.mailbackend.util.TransactionCallbacks;

/**
 * Materializes conversation membership at sync time using a JWZ-light algorithm
 * (see {@code backend/docs/THREADING_DESIGN.md}). Scope is per-account.
 *
 * <p>
 * The service expects to be called from {@code
 * MessageDownloader.saveMessagesBatchAtomic} after each {@link MessageEntity}
 * has been persisted by the mapper (so it has a database-generated {@code id})
 * but before the transaction commits.
 *
 * <p>
 * The algorithm has four steps:
 *
 * <ol>
 * <li><b>Direct parent</b> via {@code In-Reply-To} — if the message points at a
 * known {@code Message-ID} in the same account, inherit its thread.</li>
 * <li><b>References walk</b> oldest-to-newest — the first reference that
 * matches a known message wins (matches Gmail's behaviour and JWZ §3.4).</li>
 * <li><b>Subject fallback</b> for a reply with no threading headers at all —
 * some clients emit a {@code "Re:"}-prefixed reply without {@code In-Reply-To}
 * or {@code References}; such a message attaches to the newest thread with the
 * same normalized subject inside a {@value #SUBJECT_FALLBACK_WINDOW_DAYS}-day
 * window (see {@link #resolveSubjectFallbackParent}).</li>
 * <li><b>New thread</b> if no step found a parent — generate a fresh UUID and
 * use the message's own {@code Message-ID} as the root.</li>
 * <li><b>Late-arriving parent reconciliation</b> — if an earlier orphan thread
 * directly replies to this message's own {@code Message-ID} (its children
 * arrived first), is a cross-folder duplicate rooted at it, or is a cluster of
 * headerless replies this message is the subject-fallback parent of, merge those
 * threads into this message's thread, re-root and renumber the result, and
 * broadcast a {@code thread_updated} SSE event per affected thread. The subject
 * branch matters because the downloader walks UIDs newest-first, so a reply is
 * normally threaded <em>before</em> its parent.</li>
 * </ol>
 *
 * Unconditional subject-based clustering (JWZ §5) is still skipped — false
 * positives on common subjects ({@code "Re: Hi"}) outweigh the recall gain
 * given that modern providers populate the threading headers. The narrow
 * fallback above fires only when those headers are entirely absent AND the
 * subject carries an explicit reply/forward marker, which is the observed
 * real-world gap (Outlook groups these; pure JWZ splits them).
 */
@Service
public class ThreadingService {

    private static final Logger log = LoggerFactory.getLogger(ThreadingService.class);

    /**
     * Hard cap on the References chain walk — defense against a malicious /
     * malformed References header. The published JWZ algorithm has no upper bound;
     * {@code MAX_DEPTH = 20} is the precedent set by {@code MimePartExtractor}. We
     * use {@code 50} here because References lists are linear (no fan-out) and 50
     * is well above the practical length of any real conversation chain.
     */
    private static final int MAX_REFERENCES_WALK = 50;

    /**
     * Half-width of the subject-fallback time window: a headerless reply may attach
     * only to a same-subject thread whose candidate message arrived within this
     * many days. Bounds the false-positive risk of recurring subjects (a monthly
     * "Re: Faktura" exchange with a different counterparty never reaches back a
     * year). Gmail uses 7 days for its subject heuristic, Outlook none; 30 is the
     * middle ground chosen for the desktop mirror where sync gaps of days are
     * normal.
     */
    private static final int SUBJECT_FALLBACK_WINDOW_DAYS = 30;

    private final MessageRepository messageRepository;
    private final MessageReferenceRepository messageReferenceRepository;
    private final SseNotificationService sseNotificationService;

    public ThreadingService(MessageRepository messageRepository, MessageReferenceRepository messageReferenceRepository,
            SseNotificationService sseNotificationService) {
        this.messageRepository = messageRepository;
        this.messageReferenceRepository = messageReferenceRepository;
        this.sseNotificationService = sseNotificationService;
    }

    /**
     * Assigns thread membership to {@code msg}. Mutates the entity (sets
     * {@code threadId}, {@code threadRootMessageId}, {@code threadPosition}) and
     * may broadcast {@code thread_updated} SSE events as a side effect if
     * late-arriving-parent reconciliation merges orphan threads. The broadcasts are
     * deferred until after the surrounding transaction commits (see
     * {@link #broadcastThreadUpdatedAfterCommit}).
     *
     * <p>
     * Callers must invoke this within a JPA transaction so the orphan
     * reconciliation update sees the just-persisted message and the UPDATE
     * statement is part of the same unit of work.
     *
     * @param msg
     *            the newly persisted message (must have a non-null {@code id})
     * @param account
     *            the owning account
     */
    @Transactional
    public void assignThread(MessageEntity msg, AccountEntity account) {
        // Normalize the subject first — the fallback lookup below and every future
        // arrival's lookup key on this row depend on it being set.
        msg.setSubjectNorm(SubjectNormalizer.storedNorm(msg.getSubject()));
        MessageEntity parent = resolveParent(msg, account);
        if (parent == null) {
            parent = resolveSubjectFallbackParent(msg, account);
        }
        if (parent != null) {
            attachToExistingThread(msg, account, parent);
        } else {
            startNewThread(msg);
        }
        reconcileLateArrivingParent(msg, account);
        // Record this message's References tokens so a future arrival of one of its
        // ancestors can reconcile it via the indexed message_reference lookup — the
        // References-only case the in_reply_to / thread_root lookup cannot reach.
        indexReferences(msg.getId(), account.getId(), msg.getReferences());
    }

    /**
     * Populates the normalized {@code message_reference} index for a message row:
     * one row per distinct RFC 5322 Message-ID token in its References header,
     * capped at {@value #MAX_REFERENCES_WALK}. Delete-then-insert so the call is
     * idempotent (a re-thread or a backfill re-run replaces cleanly rather than
     * duplicating). Also invoked directly by the References backfill for rows that
     * predate the index. A message with no row id yet (an out-of-transaction unit
     * call) cannot be indexed and is skipped.
     */
    public void indexReferences(@Nullable Long messageRowId, Long accountId, @Nullable String referencesHeader) {
        if (messageRowId == null) {
            return;
        }
        messageReferenceRepository.deleteByMessageId(messageRowId);
        String references = trimToNull(referencesHeader);
        if (references == null) {
            return;
        }
        Set<String> distinct = new LinkedHashSet<>();
        for (String token : references.split("\\s+", -1)) {
            String trimmed = trimToNull(token);
            if (trimmed != null) {
                distinct.add(trimmed);
            }
            if (distinct.size() >= MAX_REFERENCES_WALK) {
                break;
            }
        }
        for (String reference : distinct) {
            MessageReferenceEntity entity = new MessageReferenceEntity();
            entity.setMessageId(messageRowId);
            entity.setAccountId(accountId);
            entity.setReferencedMessageId(reference);
            messageReferenceRepository.save(entity);
        }
    }

    /**
     * Broadcasts a {@code thread_updated} event, but only after the surrounding
     * transaction commits. {@link #assignThread} runs inside
     * {@code MessageDownloader}'s batch transaction; firing the SSE event inline
     * would (1) let a client refetch the thread before the rows are committed
     * (pre-commit visibility — a stale read) and (2) hold the single-writer SQLite
     * write transaction open across the blocking {@code emitter.send}. Deferring to
     * {@code afterCommit} fixes both. With no active transaction (a plain unit-test
     * invocation) we broadcast inline so behaviour is unchanged for callers outside
     * a transaction.
     */
    private void broadcastThreadUpdatedAfterCommit(ThreadUpdated event) {
        TransactionCallbacks.runAfterCommit(() -> sseNotificationService.broadcast(event));
    }

    /**
     * Step 1 + 2 of the algorithm — find a parent message in the same account that
     * this message links to.
     */
    private @Nullable MessageEntity resolveParent(MessageEntity msg, AccountEntity account) {
        // Step 1 — direct In-Reply-To match
        String inReplyTo = trimToNull(msg.getInReplyTo());
        if (inReplyTo != null) {
            MessageEntity hit = findFirstThreadedByMessageId(account.getId(), inReplyTo);
            if (hit != null) {
                return hit;
            }
        }

        // Step 2 — walk References oldest-to-newest. The references header
        // is a whitespace-separated list of Message-IDs ordered from the
        // chain root to the immediate predecessor; the first known match
        // is the closest ancestor we can reach.
        String references = trimToNull(msg.getReferences());
        if (references != null) {
            String[] refs = references.split("\\s+", -1);
            int limit = Math.min(refs.length, MAX_REFERENCES_WALK);
            for (int i = 0; i < limit; i++) {
                String ref = trimToNull(refs[i]);
                if (ref == null) {
                    continue;
                }
                MessageEntity hit = findFirstThreadedByMessageId(account.getId(), ref);
                if (hit != null) {
                    return hit;
                }
            }
        }
        return null;
    }

    /**
     * Step 3 of the algorithm — subject fallback for a reply with no threading
     * headers. Fires only when <em>all</em> of the following hold:
     *
     * <ul>
     * <li>the message carries neither {@code In-Reply-To} nor {@code References} (a
     * message with headers pointing at not-yet-mirrored ancestors keeps pure JWZ
     * semantics — attaching it by subject could poison a later late-arriving-parent
     * merge, which re-points whole threads by those very headers);</li>
     * <li>the subject starts with an explicit reply/forward marker ({@code Re:},
     * {@code Odp:}, …) — an unmarked subject is no evidence of a conversation and
     * would merge independent messages that merely share a name;</li>
     * <li>a thread with the same normalized subject has a message within
     * {@value #SUBJECT_FALLBACK_WINDOW_DAYS} days of this one.</li>
     * </ul>
     *
     * The newest qualifying message wins, mirroring Outlook's conversation-topic
     * behaviour on the least information available.
     */
    private @Nullable MessageEntity resolveSubjectFallbackParent(MessageEntity msg, AccountEntity account) {
        if (trimToNull(msg.getInReplyTo()) != null || trimToNull(msg.getReferences()) != null) {
            return null;
        }
        String norm = msg.getSubjectNorm();
        if (norm == null || norm.isEmpty() || msg.getReceivedAt() == null
                || !SubjectNormalizer.hasConversationMarker(msg.getSubject())) {
            return null;
        }
        long selfId = msg.getId() != null ? msg.getId() : -1L;
        List<MessageEntity> hits = messageRepository.findNewestThreadedBySubjectNorm(account.getId(), norm, selfId,
                msg.getReceivedAt().minusDays(SUBJECT_FALLBACK_WINDOW_DAYS),
                msg.getReceivedAt().plusDays(SUBJECT_FALLBACK_WINDOW_DAYS), PageRequest.of(0, 1));
        if (hits.isEmpty()) {
            return null;
        }
        log.debug("{} Subject fallback attached headerless reply (id {}) to thread {} of account {}.", LogCategory.SYNC,
                msg.getId(), hits.get(0).getThreadId(), account.getId());
        return hits.get(0);
    }

    /**
     * Looks up a candidate parent by Message-ID. Gmail and similar providers can
     * store the same Message-ID across multiple folders (e.g. INBOX + All Mail) —
     * every copy carries the same {@code threadId} by construction, so the first
     * row is authoritative.
     */
    private @Nullable MessageEntity findFirstThreadedByMessageId(Long accountId, String messageId) {
        List<MessageEntity> hits = messageRepository.findByAccountIdAndMessageId(accountId, messageId);
        for (MessageEntity hit : hits) {
            if (hit.getThreadId() != null) {
                return hit;
            }
        }
        return null;
    }

    /**
     * Step 1 / Step 2 success — inherit thread membership from the parent and
     * append to the end of the thread.
     */
    private void attachToExistingThread(MessageEntity msg, AccountEntity account, MessageEntity parent) {
        msg.setThreadId(parent.getThreadId());
        msg.setThreadRootMessageId(parent.getThreadRootMessageId());
        int maxPosition = messageRepository.findMaxThreadPosition(account.getId(), parent.getThreadId());
        msg.setThreadPosition(maxPosition + 1);

        // Notify subscribers that the thread gained a message. Deferred to
        // after commit so the client refetch sees the persisted row. This may
        // fire many events during a bulk initial sync; SSE clients are expected
        // to coalesce.
        broadcastThreadUpdatedAfterCommit(ThreadUpdated.of(msg.getThreadId(), account.getId()));
    }

    /**
     * Step 3 — this message is the first member of its conversation. Generate a
     * stable identifier and root the thread at our own Message-ID (or null if the
     * message has no Message-ID at all).
     */
    private void startNewThread(MessageEntity msg) {
        msg.setThreadId(UUID.randomUUID().toString());
        msg.setThreadRootMessageId(msg.getMessageId());
        msg.setThreadPosition(1);
    }

    /**
     * Step 4 — orphan chain reconciliation. When an earlier orphan thread links to
     * this message's own Message-ID — a child replied to it via {@code In-Reply-To}
     * before it arrived, a cross-folder duplicate is rooted at it, or a child
     * references it only through {@code References} (the indexed
     * {@code message_reference} lookup) — merge those orphan threads into the
     * thread this message belongs to.
     *
     * <p>
     * The absorbed rows are re-pointed to this message's {@code threadId} and
     * re-rooted to the thread's true root ({@code msg.threadRootMessageId} — which
     * equals this message's id when it started a fresh thread, or its inherited
     * ancestor root when it attached to an older one), then the whole merged thread
     * is renumbered.
     *
     * <p>
     * A fourth source has no Message-ID link at all: an orphan cluster of headerless
     * {@code "Re:"} replies that this message is the missing subject-fallback parent
     * of — see {@link #findAbsorbableSubjectOrphanThreadIds}.
     *
     * <p>
     * The merge is bounded: at most one inbound message can collapse all its orphan
     * children in a single call. Subsequent late arrivals are handled by their own
     * invocation.
     */
    private void reconcileLateArrivingParent(MessageEntity msg, AccountEntity account) {
        Set<String> merged = new LinkedHashSet<>();
        String selfMessageId = trimToNull(msg.getMessageId());
        if (selfMessageId != null) {
            // Two indexed lookups, unioned: the in_reply_to / thread_root match on
            // `messages`, plus the References-only match on the normalized
            // `message_reference` index (children that referenced this id but never set
            // In-Reply-To). Distinct so a child found by both is reassigned once.
            // Skipped without a Message-ID — nothing can point at us.
            merged.addAll(
                    messageRepository.findMergeableOrphanThreadIds(account.getId(), selfMessageId, msg.getThreadId()));
            merged.addAll(messageReferenceRepository.findOrphanThreadIdsReferencing(account.getId(), selfMessageId,
                    msg.getThreadId()));
        }
        merged.addAll(findAbsorbableSubjectOrphanThreadIds(msg, account));
        if (merged.isEmpty()) {
            return;
        }
        List<String> orphanThreadIds = new ArrayList<>(merged);
        // Re-root the absorbed rows to the thread's true root, not blindly to this
        // message: when this message itself attached to an older thread, that older
        // thread's root is the real conversation root, so a flat selfMessageId would
        // split thread_root_message_id across the merged thread.
        String mergedRoot = msg.getThreadRootMessageId();
        int moved = messageRepository.reassignThreads(account.getId(), orphanThreadIds, msg.getThreadId(), mergedRoot);
        // The absorbed rows keep their original intra-orphan thread_position, so the
        // merged thread now has colliding ordinals (every former orphan root sat at
        // position 1, alongside this message). Renumber the whole thread so
        // thread_position stays the dense, ascending ordinal the detail endpoint and
        // THREADING_DESIGN.md promise — otherwise getThread() would order the root
        // behind its own children (they have lower ids because they arrived first).
        renumberThreadPositions(account.getId(), msg.getThreadId());
        log.info("{} Reconciled {} orphan thread(s) ({} message rows) onto thread {} (root {}) for account {}.",
                LogCategory.SYNC, orphanThreadIds.size(), moved, msg.getThreadId(), mergedRoot, account.getId());
        // One thread_updated event per affected thread keeps the wire format
        // simple — the merged thread inherits the inbound message's id, the
        // orphans no longer exist as standalone aggregates. Deferred to after
        // commit so the client refetch sees the reassigned rows.
        broadcastThreadUpdatedAfterCommit(ThreadUpdated.of(msg.getThreadId(), account.getId()));
    }

    /**
     * Reverse direction of the subject fallback — the orphan threads this message
     * is the missing parent of, discovered by subject alone.
     *
     * <p>
     * {@link #resolveSubjectFallbackParent} only fires when the parent is already
     * threaded, which silently assumes the parent is processed first. It is not:
     * {@code MessageDownloader} walks UIDs newest-first, so on the initial sync of
     * an existing mailbox the reply is nearly always threaded before its parent. The
     * reply then finds no candidate, starts its own thread, and nothing ever brings
     * the two together — exactly the split the fallback exists to prevent. This runs
     * on every arrival and closes that gap from the other side.
     *
     * <p>
     * Absorption is deliberately narrower than the forward direction, because it
     * moves whole existing threads rather than attaching one new message. It fires
     * only when:
     *
     * <ul>
     * <li>this message carries no threading headers either — the same rule the
     * forward direction applies, for the same reason (a message with headers
     * pointing at not-yet-mirrored ancestors keeps pure JWZ semantics);</li>
     * <li>the candidate thread's members are <em>all</em> headerless <em>and</em>
     * all carry a reply/forward marker — a pure parentless reply cluster. This is
     * what keeps the merge one-way: once absorbed, the thread contains this
     * unprefixed parent, so a second unrelated message with the same subject can
     * never absorb it in turn. Without that guard two independent "Faktura"
     * conversations would collapse into one.</li>
     * </ul>
     *
     * @return thread ids safe to merge into this message's thread; empty when the
     *         gates above reject every candidate
     */
    private Set<String> findAbsorbableSubjectOrphanThreadIds(MessageEntity msg, AccountEntity account) {
        if (trimToNull(msg.getInReplyTo()) != null || trimToNull(msg.getReferences()) != null) {
            return Set.of();
        }
        String norm = msg.getSubjectNorm();
        if (norm == null || norm.isEmpty() || msg.getReceivedAt() == null || msg.getThreadId() == null) {
            return Set.of();
        }
        List<Object[]> rows = messageRepository.findSubjectFallbackOrphanCandidates(account.getId(), norm,
                msg.getThreadId(), msg.getReceivedAt().minusDays(SUBJECT_FALLBACK_WINDOW_DAYS),
                msg.getReceivedAt().plusDays(SUBJECT_FALLBACK_WINDOW_DAYS));
        if (rows.isEmpty()) {
            return Set.of();
        }
        // Every member has to qualify, so one disqualifying member vetoes its whole
        // thread regardless of the order the rows arrive in.
        Set<String> qualifying = new LinkedHashSet<>();
        Set<String> vetoed = new LinkedHashSet<>();
        for (Object[] row : rows) {
            String threadId = (String) row[0];
            boolean headerless = trimToNull((String) row[2]) == null && trimToNull((String) row[3]) == null;
            if (headerless && SubjectNormalizer.hasConversationMarker((String) row[1])) {
                qualifying.add(threadId);
            } else {
                vetoed.add(threadId);
            }
        }
        qualifying.removeAll(vetoed);
        if (!qualifying.isEmpty()) {
            log.debug("{} Subject fallback found {} parentless reply thread(s) for late-arriving parent id {} "
                    + "of account {}.", LogCategory.SYNC, qualifying.size(), msg.getId(), account.getId());
        }
        return qualifying;
    }

    /**
     * Recomputes dense, 1-based {@code thread_position} ordinals for every member
     * of {@code threadId}, ordered by {@code (receivedAt, id)}. Called after an
     * orphan merge, where the absorbed rows would otherwise keep their original
     * per-orphan positions and collide. The members are managed entities loaded
     * inside the active transaction, so the position writes flush on commit. The
     * fetched list is copied into a mutable list because the repository may hand
     * back an immutable view.
     */
    private void renumberThreadPositions(Long accountId, String threadId) {
        List<MessageEntity> members = new ArrayList<>(
                messageRepository.findByAccountIdAndThreadId(accountId, threadId));
        members.sort(Comparator.comparing(MessageEntity::getReceivedAt, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(MessageEntity::getId, Comparator.nullsLast(Comparator.naturalOrder())));
        int position = 1;
        for (MessageEntity member : members) {
            member.setThreadPosition(position++);
        }
    }

    private static @Nullable String trimToNull(@Nullable String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
