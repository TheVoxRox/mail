-- =====================================================================
-- V1 — Initial schema (baseline).
--
-- Defines the full database schema matching the JPA entities 1:1.
-- After this migration Hibernate runs with ddl-auto=none, so JPA never
-- mutates the schema again — Flyway is the single source of truth.
--
-- Notes:
--  * SQLite uses type affinity (lax typing). BOOLEAN/DATETIME/VARCHAR are
--    syntactic sugar over TEXT/INTEGER. The Hibernate community dialect
--    handles these types correctly.
--  * AUTOINCREMENT guarantees monotonically growing PKs (no rowid recycling).
--  * FK constraints are enabled via PRAGMA foreign_keys = ON (set in the
--    connection URL, so it applies on every Hikari connection).
--  * The FTS5 virtual table only indexes content for full-text search; the
--    actual rows live in the messages table (content='messages').
-- =====================================================================


-- =====================================================================
-- 1) MAIL PROVIDERS — built-in templates for Google (Gmail), Seznam,
--    Microsoft (Outlook/Office 365). The 'name' is the user-facing brand;
--    OAuth flow routing keys on oauth2_registration_id, not on the name.
--
-- The rows are NOT seeded here. MailProviderCatalogReconciler projects
-- MailProviderCatalog into this table on every boot, so adding a provider
-- or following one that moved a hostname/port is an ordinary code change
-- instead of a Flyway migration against an installed user's database. The
-- table is reference data — no endpoint writes to it.
--
-- supports_oauth2 + oauth2_registration_id:
--   The frontend bootstrap loads providers and uses these fields to decide
--   whether to offer an OAuth button (Microsoft / Google) or a password
--   form. The oauth2_registration_id value must exactly match the Spring
--   Security ClientRegistration ID in application.properties (e.g.
--   "google", "microsoft") — backend and frontend then use the same
--   identifier for OAuth flow routing.
--
-- The BOOLEAN columns are NOT NULL because the entity maps them to
-- primitive booleans (MailProviderEntity.systemTemplate,
-- MailServerConfig.useSsl); a NULL would fail on read, and only the
-- reconciler ever writes here.
-- =====================================================================
CREATE TABLE mail_providers (
    id                     INTEGER       PRIMARY KEY AUTOINCREMENT,
    name                   VARCHAR(255)  NOT NULL UNIQUE,
    domains                VARCHAR(1000) NOT NULL,
    imap_host              VARCHAR(255)  NOT NULL,
    imap_port              INTEGER       NOT NULL,
    imap_ssl               BOOLEAN       NOT NULL DEFAULT 1,
    smtp_host              VARCHAR(255)  NOT NULL,
    smtp_port              INTEGER       NOT NULL,
    smtp_ssl               BOOLEAN       NOT NULL DEFAULT 1,
    is_system_template     BOOLEAN       NOT NULL DEFAULT 0,
    supports_oauth2        BOOLEAN       NOT NULL DEFAULT 0,
    oauth2_registration_id VARCHAR(50)
);


-- =====================================================================
-- 2) ACCOUNTS — user email accounts.
--
-- requires_reauth: set to true when an OAuth2 provider rejects the refresh
-- token (revoke / expiry / scope change). The account is then excluded from
-- the scheduled sync until a successful re-login resets the flag. This
-- prevents hammering the provider's /token endpoint with valid but
-- server-rejected tokens.
--
-- oauth2_provider: registrationId of the OAuth2 provider ("google",
-- "microsoft", ...) or NULL for PASSWORD accounts. The value must exactly
-- match the Spring Security ClientRegistration ID and the key in
-- OAuth2TokenServiceRegistry — the single source of truth for provider
-- routing across backend and frontend. For PASSWORD accounts it stays NULL.
--
-- external_id: stable identifier of the user at the OAuth2 provider
-- (Google "sub", Microsoft "oid", ...). Uniqueness is enforced by the
-- composite index (oauth2_provider, external_id) — Google "sub" and
-- Microsoft "oid" live in different namespaces, so they could
-- hypothetically collide without the composite key.
-- =====================================================================
-- Server config (imap_*/smtp_*) is NOT NULL: the account holds its own
-- effective configuration (denormalized from the template) and the runtime
-- reads it directly from accounts. provider_id stays nullable and only
-- acts as a loose reference to the template in mail_providers (UI label,
-- audit). ON DELETE SET NULL ensures that deleting the template only
-- breaks the label — the runtime config stays valid.
--
-- signature / signature_auto_insert: per-account outgoing signature (RFC 3676
-- "-- " block) that the frontend inserts into the compose body. `signature` is
-- plain text and not secret (ordinary column, no DPAPI), NULL until the user
-- sets one. `signature_auto_insert` controls whether a new-message / mailto
-- compose auto-inserts it; replies and forwards are never auto-filled (the user
-- inserts it from the compose toolbar). NOT NULL DEFAULT 1 = historical
-- always-on behaviour for accounts created before the toggle existed.
CREATE TABLE accounts (
    id                    INTEGER       PRIMARY KEY AUTOINCREMENT,
    account_name          VARCHAR(255),
    email                 VARCHAR(255),
    display_name          VARCHAR(255),
    provider_id           INTEGER,
    imap_host             VARCHAR(255)  NOT NULL,
    imap_port             INTEGER       NOT NULL,
    imap_ssl              BOOLEAN       NOT NULL DEFAULT 1,
    smtp_host             VARCHAR(255)  NOT NULL,
    smtp_port             INTEGER       NOT NULL,
    smtp_ssl              BOOLEAN       NOT NULL DEFAULT 1,
    active                BOOLEAN       NOT NULL DEFAULT 1,
    requires_reauth       BOOLEAN       NOT NULL DEFAULT 0,
    oauth2_provider       VARCHAR(50),
    external_id           VARCHAR(255),
    last_sync_at          DATETIME,
    last_error            VARCHAR(1000),
    last_error_code       VARCHAR(100),
    last_error_args       TEXT,
    signature             TEXT,
    signature_auto_insert BOOLEAN       NOT NULL DEFAULT 1,
    FOREIGN KEY (provider_id) REFERENCES mail_providers(id) ON DELETE SET NULL
);

-- Account identity uniqueness. Partial indexes (WHERE ... IS NOT NULL) because:
--  - email may be briefly NULL during half-built test fixtures,
--  - external_id is NULL for every PASSWORD account and must not collide,
--  - the composite (oauth2_provider, external_id) isolates the namespace
--    per provider (Google "sub" vs. Microsoft "oid" in separate spaces).
-- SQLite already treats NULL as distinct inside a UNIQUE index, but the
-- partial index makes it explicit and consistent with
-- ux_contact_emails_contact_primary.
CREATE UNIQUE INDEX ux_accounts_email
    ON accounts (email)
    WHERE email IS NOT NULL;

CREATE UNIQUE INDEX ux_accounts_oauth2_external_id
    ON accounts (oauth2_provider, external_id)
    WHERE oauth2_provider IS NOT NULL AND external_id IS NOT NULL;


-- =====================================================================
-- 3) ACCOUNT CREDENTIALS — sensitive data (encrypted passwords / tokens).
--    Uses @MapsId — the primary key is also the foreign key to accounts.id.
-- =====================================================================
CREATE TABLE account_credentials (
    account_id INTEGER       PRIMARY KEY,
    auth_type  VARCHAR(50)   NOT NULL,
    username   VARCHAR(255)  NOT NULL,
    password   VARCHAR(2000) NOT NULL,
    FOREIGN KEY (account_id) REFERENCES accounts(id) ON DELETE CASCADE
);


-- =====================================================================
-- 4) FOLDER SYNC STATE — sync state per folder per account.
--
-- last_known_modseq powers CONDSTORE / QRESYNC (RFC 7162) incremental
-- sync. The server bumps MODSEQ on every flag change; the client stores
-- the folder's last-seen HIGHESTMODSEQ and the next sync runs
-- `UID FETCH 1:* (FLAGS) CHANGEDSINCE <modseq>` — the server returns
-- only messages whose flags changed since then. QRESYNC additionally
-- sends an untagged VANISHED response on SELECT listing UIDs deleted
-- since last_known_modseq, eliminating the cleanup sweep.
-- NULL means the folder has not been synced yet (or the server does not
-- advertise CONDSTORE) → the first sync falls back to a full sweep, sets
-- modseq, subsequent cycles are then fast.
-- =====================================================================
CREATE TABLE folder_sync_state (
    id                INTEGER       PRIMARY KEY AUTOINCREMENT,
    account_id        INTEGER       NOT NULL,
    folder_name       VARCHAR(255)  NOT NULL,
    role              VARCHAR(50)   NOT NULL DEFAULT 'USER',
    last_known_uid    INTEGER,
    uid_validity      INTEGER,
    last_sync_at      DATETIME,
    -- JPA @Version optimistic locking. NOT NULL because Hibernate always
    -- writes a version on insert and a NULL one would be read back as a
    -- detached/transient marker.
    version           INTEGER       NOT NULL DEFAULT 0,
    last_known_modseq INTEGER,
    CONSTRAINT uk_account_folder UNIQUE (account_id, folder_name),
    FOREIGN KEY (account_id) REFERENCES accounts(id) ON DELETE CASCADE
);


-- =====================================================================
-- 5) MESSAGES — individual messages (metadata + body cache).
-- =====================================================================
CREATE TABLE messages (
    id               INTEGER       PRIMARY KEY AUTOINCREMENT,
    stable_id        VARCHAR(32)   NOT NULL UNIQUE,
    account_id       INTEGER       NOT NULL,
    folder_name      VARCHAR(255)  NOT NULL,
    uid              INTEGER       NOT NULL,
    uid_validity     INTEGER       NOT NULL,
    subject          VARCHAR(500),
    sender           VARCHAR(255),
    recipients_to    TEXT,
    recipients_cc    TEXT,
    -- Bcc exists only on the user's own draft/sent copies (received mail never
    -- carries the header). Deliberately NOT indexed in message_search.
    recipients_bcc   TEXT,
    content          TEXT,
    -- Set when the body exceeded the extractor's byte cap (audit B1-1):
    -- content stays NULL, the API serves a localized placeholder and never
    -- re-fetches the oversized body from IMAP.
    body_oversize    BOOLEAN       NOT NULL DEFAULT 0,
    received_at      DATETIME      NOT NULL,
    seen             BOOLEAN       NOT NULL DEFAULT 0,
    flagged          BOOLEAN       NOT NULL DEFAULT 0,
    answered         BOOLEAN       NOT NULL DEFAULT 0,
    message_id       VARCHAR(255),
    in_reply_to      VARCHAR(255),
    reply_references TEXT,
    -- Normal form of `subject` (reply/forward markers stripped, whitespace
    -- collapsed, lowercased) — the key for the threading subject-fallback.
    -- Assigned by ThreadingService together with thread_id; empty string when
    -- the subject carries no grouping key, NULL only while the row predates
    -- the column (repaired by the startup backfill, which keys off exactly
    -- that NULL). See util/SubjectNormalizer.
    subject_norm     VARCHAR(500),
    has_attachments  BOOLEAN       NOT NULL DEFAULT 0,
    -- Conversation threading, materialized at sync time (assigned inline by
    -- ThreadingService when the message is persisted; rows missing it are
    -- repaired by the startup backfill / the internal /threading/recompute
    -- endpoint, hence nullable):
    --   thread_id              UUID shared by every message of a conversation.
    --                          Stable across syncs — new messages inherit it
    --                          from their parent.
    --   thread_root_message_id RFC 5322 Message-ID of the oldest message in
    --                          the thread. Used by late-arriving-parent
    --                          reconciliation to merge orphan chains.
    --   thread_position        Ordinal position within the thread (1..N) in
    --                          ascending receivedAt order.
    -- No separate `threads` aggregate table — the composite indexes below do
    -- the aggregation in queries. See backend/docs/THREADING_DESIGN.md.
    thread_id              TEXT,
    thread_root_message_id TEXT,
    thread_position        INTEGER,
    FOREIGN KEY (account_id) REFERENCES accounts(id) ON DELETE CASCADE
);

CREATE UNIQUE INDEX idx_messages_unique_uid
    ON messages (account_id, folder_name, uid);

CREATE INDEX idx_messages_lookup_desc
    ON messages (account_id, folder_name, received_at DESC);

-- No explicit index on stable_id: the column's UNIQUE constraint already
-- makes SQLite build sqlite_autoindex_messages_1 over exactly (stable_id),
-- which serves every findByStableId lookup. A second CREATE INDEX on the
-- same column is a duplicate B-tree maintained on every insert — measured at
-- +6 % database file size over 40k messages, for nothing.

-- Threading indexes. Composite on (account_id, ...) so every thread lookup
-- stays inside the caller's account and cross-account thread IDs cannot
-- collide.

-- Primary lookup: list the messages of a thread, or join a thread row onto
-- a summary list.
CREATE INDEX idx_messages_account_thread
    ON messages (account_id, thread_id);

-- Reconciliation lookup: when a new message arrives whose Message-ID
-- matches an existing message's In-Reply-To / References (i.e. it turns
-- out to be a parent that arrived after its children), find the orphan
-- thread to merge into the new root.
CREATE INDEX idx_messages_account_thread_root
    ON messages (account_id, thread_root_message_id);

-- Parent lookup: the most frequent threading query of all — every newly
-- persisted message resolves its parent by Message-ID (1x In-Reply-To plus
-- up to MAX_REFERENCES_WALK walks over References), inside the sync write
-- transaction. Without this index each lookup scans the whole account.
CREATE INDEX idx_messages_account_message_id
    ON messages (account_id, message_id);

-- Orphan lookup by In-Reply-To: children that arrived before their parent
-- reference it by Message-ID in in_reply_to; reconciliation queries them
-- on every arrival during a bulk sync, so the lookup must stay cheap.
CREATE INDEX idx_messages_account_in_reply_to
    ON messages (account_id, in_reply_to);

-- Subject-fallback lookup: a reply whose threading headers are entirely
-- missing attaches to the newest thread with the same normalized subject
-- inside a bounded time window (ThreadingService). Runs once per arriving
-- headerless reply during sync, so it must be indexed.
CREATE INDEX idx_messages_account_subject_norm
    ON messages (account_id, subject_norm);

-- Normalized References index for late-arriving-parent reconciliation. The
-- in_reply_to / thread_root lookups above miss a child that links to its
-- (not-yet-arrived) parent ONLY through the References header — a token match
-- inside the free-text reply_references column is unindexable and would turn
-- bulk sync into an O(n^2) scan. This table normalizes each message's
-- References tokens into one indexed row per (message row, referenced
-- Message-ID), so reconciliation finds those orphans with an indexed lookup.
-- Write-once: a message's References header is immutable, so rows are inserted
-- when the message is threaded and never updated. See ThreadingService and
-- backend/docs/THREADING_DESIGN.md ("Implementation note").
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
-- rows that reference it (account-scoped).
CREATE INDEX idx_message_reference_account_ref
    ON message_reference (account_id, referenced_message_id);

-- FK column index: cascade deletes and the write-once delete-then-insert on
-- re-indexing both filter by message_id.
CREATE INDEX idx_message_reference_message
    ON message_reference (message_id);


-- =====================================================================
-- 6) ATTACHMENTS — attachment metadata (the bytes themselves stream from IMAP).
-- =====================================================================
CREATE TABLE attachments (
    id           INTEGER       PRIMARY KEY AUTOINCREMENT,
    message_id   INTEGER       NOT NULL,
    part_path    VARCHAR(255)  NOT NULL,
    file_name    VARCHAR(255)  NOT NULL,
    content_type VARCHAR(255),
    size         INTEGER       NOT NULL DEFAULT 0,
    FOREIGN KEY (message_id) REFERENCES messages(id) ON DELETE CASCADE
);

CREATE INDEX idx_attachments_message_id ON attachments (message_id);


-- =====================================================================
-- 7) CONTACTS — the address book. One book for the whole application, NOT
--    one per mail account.
--
-- There is deliberately no account_id. The address book is data the user
-- curated by hand or imported from a .vcf; nothing syncs it from a provider,
-- so no mail account owns it. Scoping it per account would mean the same
-- person has to be entered twice for a user with a private and a work
-- mailbox (the common case), compose could only suggest the addresses of the
-- account being written from, and removing a mail account would silently
-- delete people — data that, unlike messages, cannot be re-fetched from
-- anywhere.
--
-- If per-account provenance is ever needed (a CardDAV / provider sync would
-- want it), that is an additive column, not a reason to scope the table.
--
-- Structured names: name (given name) + surname (family name), both
-- nullable. Email addresses live in a separate contact_emails table (a
-- contact may have 0..N addresses). The audit columns created_at, updated_at
-- are managed by @PrePersist / @PreUpdate in ContactEntity.
-- =====================================================================
CREATE TABLE contacts (
    id         INTEGER       PRIMARY KEY AUTOINCREMENT,
    name       VARCHAR(255),
    surname    VARCHAR(255),
    note       VARCHAR(1000),
    created_at DATETIME      NOT NULL,
    updated_at DATETIME      NOT NULL
);

-- Serves the default list ordering (surname, name).
CREATE INDEX ix_contacts_surname_name
    ON contacts (surname, name);


-- =====================================================================
-- 8) CONTACT EMAILS — 1:N addresses per contact.
--
-- Label values: WORK, HOME, OTHER (stored as TEXT).
-- is_primary: 1 = primary address, 0 = the rest.
--
-- Indexes:
--  * ux_contact_emails_contact_email — duplicate email on the same contact
--    is rejected. Its leading column also serves every lookup by contact_id
--    alone (loading a contact's addresses, the FK cascade), so no separate
--    index on contact_id exists — it would only duplicate this one's prefix.
--  * ux_contact_emails_contact_primary — partial unique enforces "exactly
--    one primary email per contact" at the DB level. SQLite 3.8+ supports
--    partial indexes.
--  * ix_contact_emails_email — speeds up the cross-contact duplicate check
--    (findByAnyEmail) over address books with thousands of contacts; the
--    composite (contact_id, email) is not enough for plain email=:x lookups.
--  * Cross-contact uniqueness is enforced in the application layer.
-- =====================================================================
CREATE TABLE contact_emails (
    id         INTEGER      PRIMARY KEY AUTOINCREMENT,
    contact_id INTEGER      NOT NULL,
    email      VARCHAR(255) NOT NULL,
    label      VARCHAR(10),
    is_primary INTEGER      NOT NULL DEFAULT 0,
    FOREIGN KEY (contact_id) REFERENCES contacts(id) ON DELETE CASCADE
);

CREATE UNIQUE INDEX ux_contact_emails_contact_email
    ON contact_emails (contact_id, email);

CREATE UNIQUE INDEX ux_contact_emails_contact_primary
    ON contact_emails (contact_id)
    WHERE is_primary = 1;

CREATE INDEX ix_contact_emails_email
    ON contact_emails (email);


-- =====================================================================
-- 9) CONTACT LABELS — user-defined labels ("Family", "Clients").
--
-- Not scoped per account, for the same reason as `contacts`: they group
-- entries of the one address book. A per-account label set would let two
-- rows both called "Family" exist and force the UI to present them as one
-- anyway — renaming and deleting would then have to guess which row the
-- user meant.
--
-- Distinct from contact_emails.label: that one is the *type* of a single
-- address (WORK/HOME/OTHER, a closed enum mapped to the vCard TYPE
-- parameter), while a contact label groups whole contacts and is created by
-- the user. Same split as Google Contacts, where the sidebar "Labels" are
-- contact groups and the address type is an inline field.
--
-- name_key is the case-folded form of name, maintained by the application
-- (ContactLabelService uses Locale.ROOT lower-casing) and carries the
-- uniqueness constraint. SQLite's COLLATE NOCASE folds ASCII A-Z only, so it
-- would happily accept both "Škola" and "škola" as distinct labels — for a
-- Czech address book that is a real duplicate, hence the explicit column.
-- =====================================================================
CREATE TABLE contact_labels (
    id         INTEGER     PRIMARY KEY AUTOINCREMENT,
    name       VARCHAR(60) NOT NULL,
    name_key   VARCHAR(60) NOT NULL,
    created_at DATETIME    NOT NULL
);

CREATE UNIQUE INDEX ux_contact_labels_name_key
    ON contact_labels (name_key);


-- =====================================================================
-- 10) CONTACT LABEL LINKS — M:N between contacts and contact labels.
--
-- Composite primary key makes "the same label twice on one contact"
-- impossible. Both FKs cascade: deleting a contact drops its assignments,
-- deleting a label unassigns it everywhere while the contacts survive.
-- ix_contact_label_links_label_id serves the per-label contact counts and the
-- labelId list filter, which both start from the label side (the PK's leading
-- column is contact_id and cannot help there).
-- =====================================================================
CREATE TABLE contact_label_links (
    contact_id INTEGER NOT NULL,
    label_id   INTEGER NOT NULL,
    PRIMARY KEY (contact_id, label_id),
    FOREIGN KEY (contact_id) REFERENCES contacts(id) ON DELETE CASCADE,
    FOREIGN KEY (label_id) REFERENCES contact_labels(id) ON DELETE CASCADE
);

CREATE INDEX ix_contact_label_links_label_id
    ON contact_label_links (label_id);


-- =====================================================================
-- 11) REMOTE IMAGE SENDER — per-sender allow-list for loading remote (https)
--    images in HTML mail bodies.
--
-- Remote images are blocked by default (tracking-pixel defense, see
-- docs/CONTENT_RENDERING_AUDIT.md finding F2); a sender the user has
-- explicitly trusted here has that sender's messages' remote https images
-- auto-loaded. Account-scoped so the decision is isolated per account and is
-- cleaned up by the ON DELETE CASCADE when the account is removed.
-- =====================================================================
CREATE TABLE remote_image_sender (
    id           INTEGER      PRIMARY KEY AUTOINCREMENT,
    account_id   INTEGER      NOT NULL,
    sender_email VARCHAR(255) NOT NULL,
    created_at   DATETIME     NOT NULL,
    CONSTRAINT uk_remote_image_account_sender UNIQUE (account_id, sender_email),
    FOREIGN KEY (account_id) REFERENCES accounts(id) ON DELETE CASCADE
);


-- =====================================================================
-- 12) FTS5 SEARCH — full-text index over messages.
--     Indexes subject + sender + content + recipients (TO and CC), so the
--     user can search for "mail from/to/about".
--     External content (content='messages') = the data is not duplicated;
--     FTS5 reads it from messages on query via rowid.
-- =====================================================================
CREATE VIRTUAL TABLE message_search USING fts5(
    subject,
    sender,
    content,
    recipients_to,
    recipients_cc,
    content='messages',
    content_rowid='id'
);

-- Trigger: after INSERT into messages, add a row to the FTS index.
CREATE TRIGGER messages_ai AFTER INSERT ON messages BEGIN
    INSERT INTO message_search(
        rowid, subject, sender, content, recipients_to, recipients_cc
    ) VALUES (
        new.id, new.subject, new.sender, new.content, new.recipients_to, new.recipients_cc
    );
END;

-- Trigger: after DELETE from messages, remove the row from the FTS index.
-- The 'delete' command requires the same values as the original insert (FTS5 contract).
CREATE TRIGGER messages_ad AFTER DELETE ON messages BEGIN
    INSERT INTO message_search(
        message_search, rowid, subject, sender, content, recipients_to, recipients_cc
    ) VALUES (
        'delete', old.id, old.subject, old.sender, old.content, old.recipients_to, old.recipients_cc
    );
END;

-- Trigger: after UPDATE of an FTS-indexed column, reindex the row
-- (delete + insert). Scoped via UPDATE OF on purpose: an unscoped AFTER
-- UPDATE fired on every flag flip (seen/flagged/answered) and threading
-- update too, re-tokenizing the whole body on the single-writer SQLite for
-- columns the index does not cover. Pairs with @DynamicUpdate on
-- MessageEntity (Hibernate emits SET only for dirty columns), so entity
-- flushes that do not touch these columns skip the reindex entirely.
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


-- =====================================================================
-- 13) CORRESPONDENT — distinct addresses the account has exchanged mail
-- with, harvested from message headers at sync time.
--
-- Feeds the compose-window typeahead so that a fresh install suggests the
-- people the user actually writes to, instead of only the address book,
-- which starts empty and is filled by hand or by a vCard import.
--
-- This is a DERIVED CACHE, never a source of truth: every row can be
-- reconstructed from `messages` by CorrespondentBackfillService. Nothing
-- reads it except the typeahead, so it may be dropped and rebuilt at any
-- time — which is why there is no data migration to worry about and why a
-- deleted message leaves its correspondent behind until a rebuild.
--
-- It is deliberately NOT the address book. Contacts stay hand-curated, so
-- labels and merge keep operating on a set the user chose; a table that
-- silently absorbed every sender would flood both.
--
-- email is stored normalized (trimmed, lower-cased) — the uniqueness
-- constraint is only meaningful on the folded form, and the typeahead
-- compares against a lower-cased query.
--
-- sent_count and received_count are separate rather than one counter
-- because ranking weighs them differently: an address the user has written
-- TO is a far stronger signal of a real correspondent than one that merely
-- wrote to them, and the split filters out most bulk mail for free.
--
-- Indexes: ux_correspondent_account_email carries the uniqueness that the
-- harvest upsert looks up by, and its leading account_id column also bounds
-- the typeahead scan to a single account. No second index for the prefix
-- lookup: the query ORs an email-prefix match with a display-name substring
-- match, which no index serves anyway, and this table is narrow (no body
-- column) and holds thousands of rows per account, not the tens of
-- thousands `messages` does.
-- =====================================================================
CREATE TABLE correspondent (
    id             INTEGER      PRIMARY KEY AUTOINCREMENT,
    account_id     INTEGER      NOT NULL,
    email          VARCHAR(255) NOT NULL,
    -- Most recently seen display name from the header; NULL when every
    -- sighting was a bare address.
    display_name   VARCHAR(255),
    sent_count     INTEGER      NOT NULL DEFAULT 0,
    received_count INTEGER      NOT NULL DEFAULT 0,
    last_seen_at   DATETIME     NOT NULL,
    FOREIGN KEY (account_id) REFERENCES accounts(id) ON DELETE CASCADE
);

CREATE UNIQUE INDEX ux_correspondent_account_email
    ON correspondent (account_id, email);
