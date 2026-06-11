-- =====================================================================
-- V3 — index supporting late-arriving-parent reconciliation by In-Reply-To.
--
-- ThreadingService.reconcileLateArrivingParent merges orphan threads when a
-- parent arrives after its children. The decisive match is the orphan
-- child's In-Reply-To pointing at the new arrival's Message-ID (the
-- canonical "reply arrived before original" case). V2 only indexed
-- thread_root_message_id, which catches cross-folder duplicates of the same
-- root but never a genuine late parent — so that lookup needs an index on
-- in_reply_to too.
--
-- Without this index the reconciliation lookup is a full table scan on every
-- message during a bulk sync (O(n^2)); the composite (account_id,
-- in_reply_to) keeps it O(log n) and scoped to the caller's account, mirror-
-- ing idx_messages_account_thread_root from V2.
-- =====================================================================

CREATE INDEX idx_messages_account_in_reply_to
    ON messages (account_id, in_reply_to);
