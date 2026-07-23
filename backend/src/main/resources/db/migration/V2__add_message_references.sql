-- Normalized References index for late-arriving-parent reconciliation.
--
-- ThreadingService's step-4 reconciliation (merge an orphan child thread into a
-- freshly-arrived parent) looked up orphans only by in_reply_to and
-- thread_root_message_id — both indexed columns on `messages`. A child that
-- links to its parent ONLY through the References header (no In-Reply-To) was
-- therefore never back-reconciled: a token match inside the free-text
-- reply_references column is unindexable and would turn bulk sync into an O(n^2)
-- scan (see backend/docs/THREADING_DESIGN.md, "Implementation note").
--
-- This table normalizes each message's References tokens into one indexed row
-- per (message, referenced Message-ID), so the reconciliation can find those
-- orphans with an indexed lookup on (account_id, referenced_message_id).
--
-- Write-once: a message's References header is immutable, so rows are inserted
-- when the message is threaded and never updated on re-threading. Deleted with
-- the message (ON DELETE CASCADE) and with the account (transitively).
CREATE TABLE message_reference (
    id                    INTEGER      PRIMARY KEY AUTOINCREMENT,
    -- FK to messages.id (the row), NOT the RFC 5322 Message-ID.
    message_id            INTEGER      NOT NULL,
    account_id            INTEGER      NOT NULL,
    -- One RFC 5322 Message-ID token from the message's References header.
    referenced_message_id VARCHAR(255) NOT NULL,
    FOREIGN KEY (message_id) REFERENCES messages(id) ON DELETE CASCADE,
    FOREIGN KEY (account_id) REFERENCES accounts(id) ON DELETE CASCADE
);

-- Reconciliation lookup: given a freshly-arrived Message-ID, find the orphan
-- rows that reference it. Account-scoped so cross-account threading is
-- impossible and the lookup stays inside the caller's data.
CREATE INDEX idx_message_reference_account_ref
    ON message_reference (account_id, referenced_message_id);

-- FK column index: cascade deletes and the write-once delete-then-insert on
-- re-indexing both filter by message_id.
CREATE INDEX idx_message_reference_message
    ON message_reference (message_id);
