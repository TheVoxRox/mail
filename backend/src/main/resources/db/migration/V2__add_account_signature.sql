-- Per-account outgoing-mail signature (RFC 3676 "-- " block, inserted by the
-- frontend into the compose body). Plain text, not secret -> ordinary column,
-- no DPAPI. Nullable: existing accounts have no signature until the user sets one.
ALTER TABLE accounts ADD COLUMN signature TEXT;
