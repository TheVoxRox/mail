-- Per-account toggle controlling whether the frontend auto-inserts the account
-- signature (V2 column) into a brand-new message / mailto compose. Replies and
-- forwards are never auto-filled regardless of this flag — the user inserts the
-- signature there manually from the compose toolbar. NOT NULL DEFAULT 1 so
-- existing accounts keep today's always-on behaviour.
ALTER TABLE accounts ADD COLUMN signature_auto_insert BOOLEAN NOT NULL DEFAULT 1;
