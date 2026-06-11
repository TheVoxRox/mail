-- =====================================================================
-- V2 — conversation threading.
--
-- Adds three columns to `messages` that materialize thread membership at
-- sync time:
--
--   thread_id              UUID assigned to every message in the same
--                          conversation. Stable across syncs because new
--                          messages inherit it from their parent.
--   thread_root_message_id RFC 5322 Message-ID of the oldest message in
--                          the thread. Used by late-arriving-parent
--                          reconciliation to merge orphan chains when a
--                          new arrival turns out to be the missing root.
--   thread_position        Ordinal position within the thread (1..N) in
--                          ascending receivedAt order. Lets the detail
--                          endpoint stream a thread without an extra ORDER
--                          BY join.
--
-- All three columns are nullable: the column rollout precedes the
-- ThreadingService backfill that runs once on application startup after
-- this migration applies. During that short window any row can still be
-- read; the API marks `threadId` as nullable on the wire (see
-- MailSummaryResponse + MailDetailResponse). Once the backfill completes
-- every row in the account has a non-null thread_id and the wire field
-- behaves as a single thread of one (singleton) for messages without a
-- discoverable parent / referencing thread.
--
-- Scope is per-account: indexes are composite on (account_id, thread_*)
-- so all thread lookups stay inside the caller's account and FK ON DELETE
-- CASCADE on accounts removes them in one statement when the account is
-- deleted. No separate `threads` aggregate table — the index does the
-- aggregation in queries.
--
-- See backend/docs/THREADING_DESIGN.md for the algorithm sketch, edge
-- cases and the backfill plan.
-- =====================================================================

ALTER TABLE messages ADD COLUMN thread_id              TEXT;
ALTER TABLE messages ADD COLUMN thread_root_message_id TEXT;
ALTER TABLE messages ADD COLUMN thread_position        INTEGER;

-- Primary lookup: list the messages of a thread, or join a thread row
-- onto a summary list. Composite ensures cross-account thread IDs cannot
-- collide and that account_id is the leading filter on every query.
CREATE INDEX idx_messages_account_thread
    ON messages (account_id, thread_id);

-- Reconciliation lookup: when a new message arrives whose Message-ID
-- matches an existing message's In-Reply-To / References (i.e. it turns
-- out to be a parent that arrived after its children), find the orphan
-- thread to merge into the new root.
CREATE INDEX idx_messages_account_thread_root
    ON messages (account_id, thread_root_message_id);
