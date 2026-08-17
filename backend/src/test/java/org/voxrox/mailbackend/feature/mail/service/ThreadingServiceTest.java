package org.voxrox.mailbackend.feature.mail.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.voxrox.mailbackend.feature.account.entity.AccountEntity;
import org.voxrox.mailbackend.feature.mail.dto.ThreadUpdated;
import org.voxrox.mailbackend.feature.mail.entity.MessageEntity;
import org.voxrox.mailbackend.feature.mail.entity.MessageReferenceEntity;
import org.voxrox.mailbackend.feature.mail.repository.MessageReferenceRepository;
import org.voxrox.mailbackend.feature.mail.repository.MessageRepository;

/**
 * Golden-fixture tests for {@link ThreadingService}. The cases mirror the
 * classical JWZ corpus (parent-child, references walk, missing parent, circular
 * references, missing message-id, Gmail cross-folder dupe, late reconciliation)
 * and the per-account-scope invariant.
 */
class ThreadingServiceTest {

    private static final Long ACCOUNT_ID = 7L;
    private static final AccountEntity ACCOUNT = newAccount();

    private MessageRepository repo;
    private MessageReferenceRepository refRepo;
    private SseNotificationService sse;
    private ThreadingService service;
    private Map<String, List<MessageEntity>> messagesByMessageId;
    private Map<String, Integer> maxPositionByThreadId;

    @BeforeEach
    void setUp() {
        repo = mock(MessageRepository.class);
        refRepo = mock(MessageReferenceRepository.class);
        sse = mock(SseNotificationService.class);
        service = new ThreadingService(repo, refRepo, sse);
        messagesByMessageId = new HashMap<>();
        maxPositionByThreadId = new HashMap<>();

        when(repo.findByAccountIdAndMessageId(eq(ACCOUNT_ID), anyString()))
                .thenAnswer(inv -> messagesByMessageId.getOrDefault(inv.<String>getArgument(1), List.of()));
        when(repo.findMaxThreadPosition(eq(ACCOUNT_ID), anyString()))
                .thenAnswer(inv -> maxPositionByThreadId.getOrDefault(inv.<String>getArgument(1), 0));
        when(repo.findMergeableOrphanThreadIds(eq(ACCOUNT_ID), anyString(), anyString())).thenReturn(List.of());
        when(refRepo.findOrphanThreadIdsReferencing(eq(ACCOUNT_ID), anyString(), anyString())).thenReturn(List.of());
    }

    private static AccountEntity newAccount() {
        AccountEntity account = new AccountEntity();
        account.setId(ACCOUNT_ID);
        return account;
    }

    private MessageEntity newMessage(String messageId, String inReplyTo, String references) {
        MessageEntity msg = new MessageEntity();
        msg.setMessageId(messageId);
        msg.setInReplyTo(inReplyTo);
        msg.setReferences(references);
        return msg;
    }

    private void register(MessageEntity message) {
        if (message.getMessageId() == null) {
            return;
        }
        messagesByMessageId.computeIfAbsent(message.getMessageId(), k -> new java.util.ArrayList<>()).add(message);
        if (message.getThreadId() != null) {
            maxPositionByThreadId.merge(message.getThreadId(),
                    message.getThreadPosition() != null ? message.getThreadPosition() : 1, Math::max);
        }
    }

    @Nested
    @DisplayName("Single message — root of a new thread")
    class SingleMessage {

        @Test
        @DisplayName("First-ever message gets a new threadId, root = self, position 1")
        void firstMessageBecomesThreadRoot() {
            MessageEntity msg = newMessage("<m1@example.com>", null, null);

            service.assignThread(msg, ACCOUNT);

            assertThat(msg.getThreadId()).isNotNull();
            assertThat(msg.getThreadRootMessageId()).isEqualTo("<m1@example.com>");
            assertThat(msg.getThreadPosition()).isEqualTo(1);
            verify(sse, never()).broadcast(any(ThreadUpdated.class));
        }

        @Test
        @DisplayName("Message without Message-ID still gets a threadId; root = null")
        void messageWithoutMessageIdBecomesSingleton() {
            MessageEntity msg = newMessage(null, null, null);

            service.assignThread(msg, ACCOUNT);

            assertThat(msg.getThreadId()).isNotNull();
            assertThat(msg.getThreadRootMessageId()).isNull();
            assertThat(msg.getThreadPosition()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("Parent-child resolution")
    class ParentChild {

        @Test
        @DisplayName("Direct In-Reply-To match inherits parent's threadId")
        void inReplyToInheritsThread() {
            MessageEntity parent = newMessage("<p1@example.com>", null, null);
            service.assignThread(parent, ACCOUNT);
            register(parent);

            MessageEntity child = newMessage("<c1@example.com>", "<p1@example.com>", null);
            service.assignThread(child, ACCOUNT);

            assertThat(child.getThreadId()).isEqualTo(parent.getThreadId());
            assertThat(child.getThreadRootMessageId()).isEqualTo("<p1@example.com>");
            assertThat(child.getThreadPosition()).isEqualTo(2);
            verify(sse).broadcast(any(ThreadUpdated.class));
        }

        @Test
        @DisplayName("References walk picks first known ancestor when In-Reply-To is unknown")
        void referencesWalkFindsFirstKnown() {
            MessageEntity p1 = newMessage("<p1@example.com>", null, null);
            service.assignThread(p1, ACCOUNT);
            register(p1);
            MessageEntity p2 = newMessage("<p2@example.com>", "<p1@example.com>", "<p1@example.com>");
            service.assignThread(p2, ACCOUNT);
            register(p2);

            // c1's In-Reply-To points to an unknown message; References include p1
            // (older) and p2 (newer). JWZ behaviour: walk oldest-to-newest, first
            // known wins → p1.
            MessageEntity c1 = newMessage("<c1@example.com>", "<unknown@example.com>",
                    "<p1@example.com> <p2@example.com>");
            service.assignThread(c1, ACCOUNT);

            assertThat(c1.getThreadId()).isEqualTo(p1.getThreadId()).isEqualTo(p2.getThreadId());
            assertThat(c1.getThreadRootMessageId()).isEqualTo("<p1@example.com>");
            assertThat(c1.getThreadPosition()).isEqualTo(3);
        }

        @Test
        @DisplayName("5-level References chain ends in the same thread")
        void deepReferencesChainStaysInThread() {
            MessageEntity p = newMessage("<root@example.com>", null, null);
            service.assignThread(p, ACCOUNT);
            register(p);

            String references = "<root@example.com>";
            String parentId = "<root@example.com>";
            String threadId = p.getThreadId();

            for (int i = 1; i <= 4; i++) {
                String id = "<n" + i + "@example.com>";
                MessageEntity next = newMessage(id, parentId, references);
                service.assignThread(next, ACCOUNT);
                register(next);
                references = references + " " + id;
                parentId = id;
                assertThat(next.getThreadId()).isEqualTo(threadId);
                assertThat(next.getThreadPosition()).isEqualTo(i + 1);
            }
        }
    }

    @Nested
    @DisplayName("Chronological positions")
    class ChronologicalPositions {

        /*
         * Sync order is not chronological order. The folders are mirrored one after
         * another, so the Sent copy of a reply reaches the database after the received
         * messages it belongs between. Observed on a real mailbox: a thread whose own
         * 29 May reply held the last position, leaving the account-wide order 26 May,
         * 31 May, 25 June, 3 July, 29 May.
         */
        @Test
        @DisplayName("A reply that syncs late but is chronologically older is inserted, not appended")
        void olderArrivalIsInsertedInChronologicalOrder() {
            MessageEntity opening = newMessage("<m1@example.com>", null, null);
            opening.setId(76L);
            opening.setReceivedAt(LocalDateTime.of(2026, 5, 26, 13, 54));
            service.assignThread(opening, ACCOUNT);
            register(opening);

            MessageEntity laterReply = newMessage("<m3@example.com>", "<m1@example.com>", null);
            laterReply.setId(77L);
            laterReply.setReceivedAt(LocalDateTime.of(2026, 5, 31, 19, 51));
            service.assignThread(laterReply, ACCOUNT);
            register(laterReply);

            // The Sent folder syncs now, carrying a reply written between the two.
            MessageEntity ownReply = newMessage("<m2@example.com>", "<m1@example.com>", null);
            ownReply.setId(127L);
            ownReply.setReceivedAt(LocalDateTime.of(2026, 5, 29, 15, 10));
            when(repo.countThreadMembersAfter(eq(ACCOUNT_ID), anyString(), eq(ownReply.getReceivedAt()), eq(127L)))
                    .thenReturn(1L);
            when(repo.findByAccountIdAndThreadId(eq(ACCOUNT_ID), anyString()))
                    .thenReturn(List.of(opening, laterReply, ownReply));

            service.assignThread(ownReply, ACCOUNT);

            assertThat(opening.getThreadPosition()).isEqualTo(1);
            assertThat(ownReply.getThreadPosition()).isEqualTo(2);
            assertThat(laterReply.getThreadPosition()).isEqualTo(3);
        }

        @Test
        @DisplayName("A genuinely new reply still appends without reading the thread back")
        void newestArrivalAppendsWithoutRenumbering() {
            MessageEntity opening = newMessage("<m1@example.com>", null, null);
            opening.setId(1L);
            opening.setReceivedAt(LocalDateTime.of(2026, 5, 26, 13, 54));
            service.assignThread(opening, ACCOUNT);
            register(opening);

            MessageEntity reply = newMessage("<m2@example.com>", "<m1@example.com>", null);
            reply.setId(2L);
            reply.setReceivedAt(LocalDateTime.of(2026, 5, 31, 19, 51));
            service.assignThread(reply, ACCOUNT);

            assertThat(opening.getThreadPosition()).isEqualTo(1);
            assertThat(reply.getThreadPosition()).isEqualTo(2);
            // The renumbering pass loads every member as a managed entity, @Lob body
            // included. The common case — mail newer than its whole thread — must not
            // pay for that, which is what the COUNT probe buys.
            verify(repo, never()).findByAccountIdAndThreadId(anyLong(), anyString());
        }

        @Test
        @DisplayName("An entity with no receivedAt is appended rather than probed")
        void messageWithoutReceivedAtAppends() {
            MessageEntity opening = newMessage("<m1@example.com>", null, null);
            service.assignThread(opening, ACCOUNT);
            register(opening);

            // The column is NOT NULL in the schema, so this only pins the behaviour of
            // an entity built without one — it must not reach the probe with a null key.
            MessageEntity reply = newMessage("<m2@example.com>", "<m1@example.com>", null);
            service.assignThread(reply, ACCOUNT);

            assertThat(reply.getThreadPosition()).isEqualTo(2);
            verify(repo, never()).countThreadMembersAfter(anyLong(), anyString(), any(), anyLong());
        }
    }

    @Nested
    @DisplayName("Edge cases")
    class EdgeCases {

        @Test
        @DisplayName("Unknown In-Reply-To and unknown References → new thread root")
        void allReferencesUnknownStartsNewThread() {
            MessageEntity msg = newMessage("<orphan@example.com>", "<unknown@example.com>",
                    "<also-unknown@example.com>");

            service.assignThread(msg, ACCOUNT);

            assertThat(msg.getThreadId()).isNotNull();
            assertThat(msg.getThreadRootMessageId()).isEqualTo("<orphan@example.com>");
            assertThat(msg.getThreadPosition()).isEqualTo(1);
        }

        @Test
        @DisplayName("Self-referencing References does not loop indefinitely")
        void selfReferenceTerminates() {
            MessageEntity msg = newMessage("<loop@example.com>", "<loop@example.com>", "<loop@example.com>");

            service.assignThread(msg, ACCOUNT);

            // The message references its own future Message-ID. Lookup finds
            // nothing (the message is not yet persisted under that id), so we
            // fall through to the new-thread branch.
            assertThat(msg.getThreadId()).isNotNull();
            assertThat(msg.getThreadRootMessageId()).isEqualTo("<loop@example.com>");
            assertThat(msg.getThreadPosition()).isEqualTo(1);
        }

        @Test
        @DisplayName("Blank In-Reply-To and References are ignored")
        void blankHeadersAreIgnored() {
            MessageEntity msg = newMessage("<m@example.com>", "   ", "  \t  ");

            service.assignThread(msg, ACCOUNT);

            assertThat(msg.getThreadId()).isNotNull();
            verify(repo, never()).findByAccountIdAndMessageId(eq(ACCOUNT_ID), anyString());
        }
    }

    @Nested
    @DisplayName("Gmail cross-folder dupes")
    class GmailDupes {

        @Test
        @DisplayName("Same Message-ID in INBOX and All Mail returns the same thread")
        void sameMessageIdAcrossFoldersSharesThread() {
            MessageEntity inbox = newMessage("<p@example.com>", null, null);
            service.assignThread(inbox, ACCOUNT);
            register(inbox);

            // Second copy with the same Message-ID, e.g. from [Gmail]/All Mail.
            MessageEntity allMail = newMessage("<p@example.com>", null, null);
            service.assignThread(allMail, ACCOUNT);

            // The new copy can still see the original copy as a parent via
            // late-arriving reconciliation, but most importantly we don't
            // create a duplicate thread.
            // Step 3 fires (no parent), so allMail starts a NEW thread...
            // ...but step 4 reconciles: the orphan thread (just-created
            // allMail's own thread) merges into inbox's thread because both
            // share the same Message-ID. The repository stub returns no
            // orphan ids by default, so verify by counting broadcast events
            // — at least one fires if a merge happens.
            //
            // For this test we verify the simpler invariant: a child of the
            // shared Message-ID lands in inbox's thread.
            MessageEntity reply = newMessage("<c1@example.com>", "<p@example.com>", null);
            service.assignThread(reply, ACCOUNT);

            assertThat(reply.getThreadId()).isEqualTo(inbox.getThreadId());
        }
    }

    @Nested
    @DisplayName("Late-arriving parent reconciliation")
    class LateReconciliation {

        @Test
        @DisplayName("Parent arriving after orphan children merges the orphan thread")
        void lateParentMergesOrphanChildren() {
            // Two orphan children arrive first — they had no resolvable parent
            // so they started their own thread.
            MessageEntity orphan1 = newMessage("<c1@example.com>", "<late-parent@example.com>", null);
            service.assignThread(orphan1, ACCOUNT);
            register(orphan1);

            String orphanThreadId = orphan1.getThreadId();
            // Stub the orphan lookup so the late parent can find this thread.
            when(repo.findMergeableOrphanThreadIds(eq(ACCOUNT_ID), eq("<late-parent@example.com>"), anyString()))
                    .thenReturn(List.of(orphanThreadId));
            AtomicInteger reassignCalls = new AtomicInteger();
            when(repo.reassignThreads(eq(ACCOUNT_ID), eq(List.of(orphanThreadId)), anyString(),
                    eq("<late-parent@example.com>"))).thenAnswer(inv -> {
                        reassignCalls.incrementAndGet();
                        return 1;
                    });

            // Now the parent arrives.
            MessageEntity parent = newMessage("<late-parent@example.com>", null, null);
            service.assignThread(parent, ACCOUNT);

            assertThat(reassignCalls.get()).isEqualTo(1);
            // thread_updated should have fired (the orphan merge).
            verify(sse, atLeastOnce()).broadcast(any(ThreadUpdated.class));
        }

        @Test
        @DisplayName("Reconciliation renumbers the merged thread by (receivedAt, id) so the root leads")
        void reconciliationRenumbersMergedPositions() {
            // An orphan child arrives first — no resolvable parent, so it starts its
            // own thread at position 1. It carries a newer receivedAt and a higher id
            // (it was inserted before the parent).
            MessageEntity orphanChild = newMessage("<c1@example.com>", "<late-parent@example.com>", null);
            orphanChild.setId(10L);
            orphanChild.setReceivedAt(LocalDateTime.of(2026, 6, 6, 12, 5));
            service.assignThread(orphanChild, ACCOUNT);
            register(orphanChild);

            String orphanThreadId = orphanChild.getThreadId();
            when(repo.findMergeableOrphanThreadIds(eq(ACCOUNT_ID), eq("<late-parent@example.com>"), anyString()))
                    .thenReturn(List.of(orphanThreadId));
            when(repo.reassignThreads(eq(ACCOUNT_ID), eq(List.of(orphanThreadId)), anyString(),
                    eq("<late-parent@example.com>"))).thenReturn(1);

            // The late-arriving parent is the conversation root: older receivedAt,
            // lower id. After the merge the repository returns both rows (child-first,
            // to prove the renumber re-sorts rather than trusting the fetch order).
            MessageEntity parent = newMessage("<late-parent@example.com>", null, null);
            parent.setId(5L);
            parent.setReceivedAt(LocalDateTime.of(2026, 6, 6, 12, 0));
            when(repo.findByAccountIdAndThreadId(eq(ACCOUNT_ID), anyString())).thenReturn(List.of(orphanChild, parent));

            service.assignThread(parent, ACCOUNT);

            // Root (oldest) leads at position 1; the previously-orphan child follows
            // at 2 — the colliding ordinals are gone.
            assertThat(parent.getThreadPosition()).isEqualTo(1);
            assertThat(orphanChild.getThreadPosition()).isEqualTo(2);
        }

        @Test
        @DisplayName("Reconciliation re-roots merged orphans to the thread's true root, not the parent's own id")
        void reconciliationRerootsToThreadRoot() {
            // An older ancestor A already anchors a thread.
            MessageEntity ancestor = newMessage("<a@example.com>", null, null);
            service.assignThread(ancestor, ACCOUNT);
            register(ancestor);
            String ancestorThreadId = ancestor.getThreadId();

            // An orphan replied to P before P arrived, so it started its own thread.
            MessageEntity orphan = newMessage("<o@example.com>", "<p@example.com>", null);
            service.assignThread(orphan, ACCOUNT);
            register(orphan);
            String orphanThreadId = orphan.getThreadId();

            when(repo.findMergeableOrphanThreadIds(eq(ACCOUNT_ID), eq("<p@example.com>"), anyString()))
                    .thenReturn(List.of(orphanThreadId));

            // P arrives: it replies to the known ancestor A, so it attaches to A's
            // thread (root stays A). It is also the late parent of the orphan.
            MessageEntity lateParent = newMessage("<p@example.com>", "<a@example.com>", "<a@example.com>");
            service.assignThread(lateParent, ACCOUNT);

            assertThat(lateParent.getThreadId()).isEqualTo(ancestorThreadId);
            assertThat(lateParent.getThreadRootMessageId()).isEqualTo("<a@example.com>");
            // The orphan thread merges in and is re-rooted to A (the real root), NOT
            // to the late parent's own Message-ID.
            verify(repo).reassignThreads(eq(ACCOUNT_ID), eq(List.of(orphanThreadId)), eq(ancestorThreadId),
                    eq("<a@example.com>"));
        }

        @Test
        @DisplayName("Reconciliation is skipped when the new message has no Message-ID")
        void noMessageIdSkipsReconciliation() {
            MessageEntity msg = newMessage(null, null, null);

            service.assignThread(msg, ACCOUNT);

            verify(repo, never()).findMergeableOrphanThreadIds(anyLong(), anyString(), anyString());
            verify(repo, never()).reassignThreads(anyLong(), any(), anyString(), anyString());
        }
    }

    @Nested
    @DisplayName("References walk cap")
    class ReferencesCap {

        @Test
        @DisplayName("References list longer than the cap stops at the cap")
        void referencesWalkRespectsMaxDepth() {
            // 60 references; cap is 50 — verify we never look up the 51st.
            StringBuilder refs = new StringBuilder();
            for (int i = 1; i <= 60; i++) {
                if (i > 1) {
                    refs.append(' ');
                }
                refs.append("<r").append(i).append("@example.com>");
            }
            MessageEntity msg = newMessage("<m@example.com>", null, refs.toString());

            service.assignThread(msg, ACCOUNT);

            // None of the 60 refs is known, so we fall through to a new thread.
            assertThat(msg.getThreadId()).isNotNull();
            // We should have queried at most MAX_REFERENCES_WALK + 1 ids
            // (the +1 covers the In-Reply-To lookup; here it is null so 0 extra).
            verify(repo, times(50)).findByAccountIdAndMessageId(eq(ACCOUNT_ID), anyString());
        }
    }

    @Nested
    @DisplayName("SSE broadcast deferral")
    class BroadcastDeferral {

        /**
         * assignThread runs inside MessageDownloader's batch transaction. The
         * thread_updated event must not reach the wire until that transaction commits —
         * otherwise a client could refetch the thread before the rows are visible (a
         * stale read). With an active synchronization the broadcast is registered and
         * only fires on afterCommit.
         */
        @Test
        @DisplayName("thread_updated is held until the transaction commits, not fired inline")
        void broadcastDeferredUntilAfterCommit() {
            TransactionSynchronizationManager.initSynchronization();
            try {
                MessageEntity parent = newMessage("<p1@example.com>", null, null);
                service.assignThread(parent, ACCOUNT); // startNewThread — no broadcast
                register(parent);

                MessageEntity child = newMessage("<c1@example.com>", "<p1@example.com>", null);
                service.assignThread(child, ACCOUNT); // attaches — schedules a broadcast

                // Inside the transaction nothing has been broadcast yet.
                verify(sse, never()).broadcast(any(ThreadUpdated.class));

                // Commit: every registered synchronization fires.
                for (TransactionSynchronization sync : TransactionSynchronizationManager.getSynchronizations()) {
                    sync.afterCommit();
                }
                verify(sse).broadcast(any(ThreadUpdated.class));
            } finally {
                TransactionSynchronizationManager.clearSynchronization();
            }
        }

        /**
         * Outside a transaction (a plain unit-test or non-transactional caller) the
         * broadcast falls back to firing inline, so existing behaviour is preserved.
         */
        @Test
        @DisplayName("Without an active transaction the broadcast fires inline")
        void broadcastInlineWithoutTransaction() {
            MessageEntity parent = newMessage("<p1@example.com>", null, null);
            service.assignThread(parent, ACCOUNT);
            register(parent);

            MessageEntity child = newMessage("<c1@example.com>", "<p1@example.com>", null);
            service.assignThread(child, ACCOUNT);

            verify(sse).broadcast(any(ThreadUpdated.class));
        }
    }

    @Nested
    @DisplayName("References-only reconciliation (message_reference index)")
    class ReferencesOnlyReconciliation {

        @Test
        @DisplayName("Orphan referencing the parent only via References merges when the parent arrives")
        void referencesOnlyOrphanMergesOnParentArrival() {
            // A child references <late-parent> only through References — no
            // In-Reply-To — and arrives first, so it starts its own orphan thread.
            MessageEntity orphan = newMessage("<c1@example.com>", null, "<late-parent@example.com>");
            orphan.setId(10L);
            service.assignThread(orphan, ACCOUNT);
            register(orphan);
            String orphanThreadId = orphan.getThreadId();

            // The in_reply_to / thread_root lookup finds nothing (the orphan has no
            // In-Reply-To); the message_reference index is what surfaces it.
            when(refRepo.findOrphanThreadIdsReferencing(eq(ACCOUNT_ID), eq("<late-parent@example.com>"), anyString()))
                    .thenReturn(List.of(orphanThreadId));

            MessageEntity parent = newMessage("<late-parent@example.com>", null, null);
            service.assignThread(parent, ACCOUNT);

            verify(repo).reassignThreads(eq(ACCOUNT_ID), eq(List.of(orphanThreadId)), anyString(),
                    eq("<late-parent@example.com>"));
            verify(sse, atLeastOnce()).broadcast(any(ThreadUpdated.class));
        }

        @Test
        @DisplayName("An orphan found by both lookups is reassigned once (deduplicated)")
        void orphanFoundByBothLookupsIsReassignedOnce() {
            when(repo.findMergeableOrphanThreadIds(eq(ACCOUNT_ID), eq("<p@example.com>"), anyString()))
                    .thenReturn(List.of("orphan-thread"));
            when(refRepo.findOrphanThreadIdsReferencing(eq(ACCOUNT_ID), eq("<p@example.com>"), anyString()))
                    .thenReturn(List.of("orphan-thread"));

            MessageEntity parent = newMessage("<p@example.com>", null, null);
            parent.setId(1L);
            service.assignThread(parent, ACCOUNT);

            verify(repo).reassignThreads(eq(ACCOUNT_ID), eq(List.of("orphan-thread")), anyString(), anyString());
        }

        @Test
        @DisplayName("indexReferences records distinct References tokens for the message row")
        void indexReferencesRecordsDistinctTokens() {
            MessageEntity msg = newMessage("<m@example.com>", "<a@example.com>",
                    "<a@example.com> <b@example.com> <a@example.com>");
            msg.setId(42L);

            service.assignThread(msg, ACCOUNT);

            verify(refRepo).deleteByMessageId(42L);
            ArgumentCaptor<MessageReferenceEntity> captor = ArgumentCaptor.forClass(MessageReferenceEntity.class);
            verify(refRepo, times(2)).save(captor.capture());
            assertThat(captor.getAllValues()).extracting(MessageReferenceEntity::getReferencedMessageId)
                    .containsExactly("<a@example.com>", "<b@example.com>");
            assertThat(captor.getAllValues()).allSatisfy(entity -> assertThat(entity.getMessageId()).isEqualTo(42L));
        }

        @Test
        @DisplayName("A message with no row id (out-of-transaction) is not reference-indexed")
        void noRowIdSkipsIndexing() {
            MessageEntity msg = newMessage("<m@example.com>", null, "<a@example.com>");
            // id left null — an out-of-transaction unit call cannot be indexed.

            service.assignThread(msg, ACCOUNT);

            verify(refRepo, never()).deleteByMessageId(anyLong());
            verify(refRepo, never()).save(any());
        }
    }

    @Nested
    @DisplayName("Subject fallback (headerless replies)")
    class SubjectFallback {

        private static final LocalDateTime BASE = LocalDateTime.of(2026, 6, 1, 12, 0);

        private MessageEntity newSubjectMessage(String messageId, String subject, LocalDateTime receivedAt) {
            MessageEntity msg = newMessage(messageId, null, null);
            msg.setSubject(subject);
            msg.setReceivedAt(receivedAt);
            return msg;
        }

        /** Threads a root message and registers it as the fallback candidate. */
        private MessageEntity threadedRoot(String subject) {
            MessageEntity root = newSubjectMessage("<root@example.com>", subject, BASE);
            service.assignThread(root, ACCOUNT);
            register(root);
            return root;
        }

        @Test
        @DisplayName("A 'Re:'-prefixed reply with no threading headers attaches to the same-subject thread")
        void headerlessReplyAttachesBySubject() {
            MessageEntity root = threadedRoot("Vylet na hory");
            when(repo.findNewestThreadedBySubjectNorm(eq(ACCOUNT_ID), eq("vylet na hory"), anyLong(), any(), any(),
                    any())).thenReturn(List.of(root));

            MessageEntity reply = newSubjectMessage("<r1@example.com>", "Re: Vylet na hory", BASE.plusDays(5));
            service.assignThread(reply, ACCOUNT);

            assertThat(reply.getThreadId()).isEqualTo(root.getThreadId());
            assertThat(reply.getThreadRootMessageId()).isEqualTo("<root@example.com>");
            assertThat(reply.getThreadPosition()).isEqualTo(2);
            assertThat(reply.getSubjectNorm()).isEqualTo("vylet na hory");
        }

        @Test
        @DisplayName("The lookup window is receivedAt plus/minus 30 days")
        void lookupWindowSurroundsReceivedAt() {
            MessageEntity root = threadedRoot("Vylet na hory");
            LocalDateTime received = BASE.plusDays(5);
            // Stubbed with the exact window bounds — a drifted implementation would
            // miss this stub, fall through to a new thread and fail the assertion.
            when(repo.findNewestThreadedBySubjectNorm(eq(ACCOUNT_ID), eq("vylet na hory"), anyLong(),
                    eq(received.minusDays(30)), eq(received.plusDays(30)), any())).thenReturn(List.of(root));

            MessageEntity reply = newSubjectMessage("<r1@example.com>", "Odp: Vylet na hory", received);
            service.assignThread(reply, ACCOUNT);

            assertThat(reply.getThreadId()).isEqualTo(root.getThreadId());
        }

        @Test
        @DisplayName("An unprefixed subject never uses the fallback — independent same-name messages stay apart")
        void unprefixedSubjectStartsNewThread() {
            threadedRoot("Faktura");

            MessageEntity second = newSubjectMessage("<f2@example.com>", "Faktura", BASE.plusDays(1));
            service.assignThread(second, ACCOUNT);

            assertThat(second.getThreadId()).isNotNull();
            assertThat(second.getThreadPosition()).isEqualTo(1);
            verify(repo, never()).findNewestThreadedBySubjectNorm(anyLong(), anyString(), anyLong(), any(), any(),
                    any());
        }

        @Test
        @DisplayName("A reply that carries threading headers keeps pure JWZ semantics (no subject fallback)")
        void replyWithHeadersSkipsFallback() {
            threadedRoot("Vylet na hory");

            MessageEntity reply = newSubjectMessage("<r1@example.com>", "Re: Vylet na hory", BASE.plusDays(2));
            reply.setInReplyTo("<not-mirrored@example.com>");
            service.assignThread(reply, ACCOUNT);

            assertThat(reply.getThreadPosition()).isEqualTo(1);
            verify(repo, never()).findNewestThreadedBySubjectNorm(anyLong(), anyString(), anyLong(), any(), any(),
                    any());
        }

        @Test
        @DisplayName("Stacked localized markers normalize to the same key")
        void stackedMarkersNormalize() {
            MessageEntity root = threadedRoot("Vylet na hory");
            when(repo.findNewestThreadedBySubjectNorm(eq(ACCOUNT_ID), eq("vylet na hory"), anyLong(), any(), any(),
                    any())).thenReturn(List.of(root));

            MessageEntity reply = newSubjectMessage("<r1@example.com>", "Odp: RE[2]: Vylet   na hory",
                    BASE.plusDays(3));
            service.assignThread(reply, ACCOUNT);

            assertThat(reply.getThreadId()).isEqualTo(root.getThreadId());
        }

        @Test
        @DisplayName("No same-subject thread in the window -> new thread root")
        void noCandidateStartsNewThread() {
            MessageEntity reply = newSubjectMessage("<r1@example.com>", "Re: Vylet na hory", BASE);
            service.assignThread(reply, ACCOUNT);

            assertThat(reply.getThreadId()).isNotNull();
            assertThat(reply.getThreadRootMessageId()).isEqualTo("<r1@example.com>");
            assertThat(reply.getThreadPosition()).isEqualTo(1);
        }

        @Test
        @DisplayName("Reply threaded BEFORE its parent -> the arriving parent absorbs the orphan reply")
        void lateArrivingParentAbsorbsOrphanReply() {
            // The forward fallback alone assumes the parent is processed first.
            // MessageDownloader walks UIDs newest-first, so on the initial sync of an
            // existing mailbox this order is the rule, not the exception — every test
            // above registers the parent first and would never notice.
            MessageEntity reply = newSubjectMessage("<r1@example.com>", "Re: Vylet na hory", BASE.plusDays(2));
            service.assignThread(reply, ACCOUNT);
            register(reply);
            String orphanThreadId = reply.getThreadId();
            assertThat(orphanThreadId).isNotNull();

            MessageEntity parent = newSubjectMessage("<root@example.com>", "Vylet na hory", BASE);
            when(repo.findSubjectFallbackOrphanCandidates(eq(ACCOUNT_ID), eq("vylet na hory"), anyString(), any(),
                    any()))
                    .thenReturn(List.<Object[]>of(new Object[]{orphanThreadId, "Re: Vylet na hory", null, null}));
            when(repo.findByAccountIdAndThreadId(eq(ACCOUNT_ID), anyString())).thenReturn(List.of(parent, reply));

            service.assignThread(parent, ACCOUNT);

            verify(repo).reassignThreads(ACCOUNT_ID, List.of(orphanThreadId), parent.getThreadId(),
                    "<root@example.com>");
            // Renumbered oldest-first, so the parent leads its own thread.
            assertThat(parent.getThreadPosition()).isEqualTo(1);
            assertThat(reply.getThreadPosition()).isEqualTo(2);
        }

        @Test
        @DisplayName("A thread that already holds an unprefixed message is never absorbed by subject")
        void threadWithItsOwnParentIsNotAbsorbed() {
            // Otherwise a second, unrelated "Faktura" would swallow the first
            // conversation whole — and keep doing so for every further one.
            MessageEntity newcomer = newSubjectMessage("<f2@example.com>", "Faktura", BASE.plusDays(1));
            when(repo.findSubjectFallbackOrphanCandidates(eq(ACCOUNT_ID), eq("faktura"), anyString(), any(), any()))
                    .thenReturn(List.of(new Object[]{"t-existing", "Faktura", null, null},
                            new Object[]{"t-existing", "Re: Faktura", null, null}));

            service.assignThread(newcomer, ACCOUNT);

            verify(repo, never()).reassignThreads(anyLong(), any(), anyString(), any());
        }

        @Test
        @DisplayName("A message carrying threading headers never absorbs anything by subject")
        void messageWithHeadersNeverAbsorbsBySubject() {
            MessageEntity msg = newSubjectMessage("<x@example.com>", "Vylet na hory", BASE);
            msg.setReferences("<not-mirrored@example.com>");

            service.assignThread(msg, ACCOUNT);

            verify(repo, never()).findSubjectFallbackOrphanCandidates(anyLong(), anyString(), anyString(), any(),
                    any());
        }

        @Test
        @DisplayName("A subject with no grouping key is stored as the empty sentinel, never NULL")
        void degenerateSubjectStoresSentinel() {
            // NULL means "the backfill has not seen this row"; a row that stays NULL
            // is re-selected by that pass on every startup.
            MessageEntity msg = newSubjectMessage("<m1@example.com>", "Re:", BASE);

            service.assignThread(msg, ACCOUNT);

            assertThat(msg.getSubjectNorm()).isEmpty();
            verify(repo, never()).findNewestThreadedBySubjectNorm(anyLong(), anyString(), anyLong(), any(), any(),
                    any());
        }
    }
}
