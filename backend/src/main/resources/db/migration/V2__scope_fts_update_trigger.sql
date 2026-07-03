-- =====================================================================
-- V2 — Scope the FTS5 reindex trigger to the indexed columns.
--
-- The V1 trigger fired AFTER UPDATE ON messages, i.e. on *every* UPDATE —
-- including flag flips (seen/flagged/answered) and threading-column
-- updates. Each firing deletes and re-inserts the FTS5 entry, which
-- re-tokenizes the full message body: pure write amplification on the
-- single-writer SQLite for columns the FTS index does not even cover.
-- A periodic flag sweep updating hundreds of rows re-tokenized hundreds
-- of bodies per cycle for nothing.
--
-- AFTER UPDATE OF <cols> fires only when one of the listed columns
-- appears as an assignment target of the UPDATE statement. Together with
-- @DynamicUpdate on MessageEntity (Hibernate now emits SET only for dirty
-- columns) the FTS index is rebuilt exactly when indexed content changes.
--
-- The index itself needs no repair: the old trigger kept it consistent,
-- it was just rebuilt more often than necessary.
-- =====================================================================

DROP TRIGGER messages_au;

CREATE TRIGGER messages_au AFTER UPDATE OF subject, sender, content, recipients_to, recipients_cc ON messages BEGIN
    INSERT INTO message_search(
        message_search, rowid, subject, sender, content, recipients_to, recipients_cc
    ) VALUES (
        'delete', old.id, old.subject, old.sender, old.content, old.recipients_to, old.recipients_cc
    );
    INSERT INTO message_search(
        rowid, subject, sender, content, recipients_to, recipients_cc
    ) VALUES (
        new.id, new.subject, new.sender, new.content, new.recipients_to, new.recipients_cc
    );
END;
