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
-- 1) MAIL PROVIDERS — predefined templates for Google (Gmail), Seznam,
--    Microsoft (Outlook/Office 365). The 'name' is the user-facing brand;
--    OAuth flow routing keys on oauth2_registration_id, not on the name.
--
-- supports_oauth2 + oauth2_registration_id:
--   The frontend bootstrap loads providers and uses these fields to decide
--   whether to offer an OAuth button (Microsoft / Google) or a password
--   form. The oauth2_registration_id value must exactly match the Spring
--   Security ClientRegistration ID in application.properties (e.g.
--   "google", "microsoft") — backend and frontend then use the same
--   identifier for OAuth flow routing.
-- =====================================================================
CREATE TABLE mail_providers (
    id                     INTEGER       PRIMARY KEY AUTOINCREMENT,
    name                   VARCHAR(255)  NOT NULL UNIQUE,
    domains                VARCHAR(1000) NOT NULL,
    imap_host              VARCHAR(255)  NOT NULL,
    imap_port              INTEGER       NOT NULL,
    imap_ssl               BOOLEAN,
    smtp_host              VARCHAR(255)  NOT NULL,
    smtp_port              INTEGER       NOT NULL,
    smtp_ssl               BOOLEAN,
    is_system_template     BOOLEAN,
    supports_oauth2        BOOLEAN       NOT NULL DEFAULT 0,
    oauth2_registration_id VARCHAR(50)
);

-- Seed of system templates. 'domains' format: comma-anchored (",a.cz,b.cz,")
-- for exact LIKE '%,<domain>,%' matching in MailProviderRepository.
-- Ports 993/465 = implicit SSL/TLS (useSsl=1); STARTTLS variants (143/587)
-- use useSsl=0. Office 365 SMTP submission is STARTTLS-only on 587 (no
-- implicit-SSL endpoint exists), so Microsoft is seeded smtp 587/useSsl=0;
-- its IMAP keeps implicit SSL on 993.
--
-- supports_oauth2 = 1 + oauth2_registration_id signals to the frontend that
-- the provider has a backend OAuth2 implementation (token service + Spring
-- ClientRegistration). Seznam stays PASSWORD-only — there is no public
-- OAuth2 API. Microsoft (Outlook/Office 365) uses Microsoft Identity Platform
-- tenant 'common' (personal MSA and work/AAD accounts under one registration).
INSERT INTO mail_providers (name, domains, imap_host, imap_port, imap_ssl, smtp_host, smtp_port, smtp_ssl, is_system_template, supports_oauth2, oauth2_registration_id) VALUES
    ('Google',    ',gmail.com,googlemail.com,',                    'imap.gmail.com',   993, 1, 'smtp.gmail.com',   465, 1, 1, 1, 'google'),
    ('Seznam',    ',seznam.cz,email.cz,post.cz,spoluzaci.cz,',     'imap.seznam.cz',   993, 1, 'smtp.seznam.cz',   465, 1, 1, 0, NULL),
    ('Microsoft', ',outlook.com,hotmail.com,live.com,msn.com,',    'outlook.office365.com', 993, 1, 'smtp.office365.com', 587, 0, 1, 1, 'microsoft');


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
    version           INTEGER,
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

CREATE INDEX idx_messages_stable_id
    ON messages (stable_id);

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
-- 7) CONTACTS — per-account address book.
--
-- A contact belongs to exactly one account (FK with CASCADE). Structured
-- names: name (given name) + surname (family name), both nullable. Email
-- addresses live in a separate contact_emails table (a contact may have
-- 0..N addresses). The audit columns created_at, updated_at are managed
-- by @PrePersist / @PreUpdate in ContactEntity.
-- =====================================================================
CREATE TABLE contacts (
    id         INTEGER       PRIMARY KEY AUTOINCREMENT,
    account_id INTEGER       NOT NULL,
    name       VARCHAR(255),
    surname    VARCHAR(255),
    note       VARCHAR(1000),
    created_at DATETIME      NOT NULL,
    updated_at DATETIME      NOT NULL,
    FOREIGN KEY (account_id) REFERENCES accounts(id) ON DELETE CASCADE
);

CREATE INDEX ix_contacts_account_surname_name
    ON contacts (account_id, surname, name);


-- =====================================================================
-- 8) CONTACT EMAILS — 1:N addresses per contact.
--
-- Label values: WORK, HOME, OTHER (stored as TEXT).
-- is_primary: 1 = primary address, 0 = the rest.
--
-- Indexes:
--  * ux_contact_emails_contact_email — duplicate email on the same contact
--    is rejected.
--  * ux_contact_emails_contact_primary — partial unique enforces "exactly
--    one primary email per contact" at the DB level. SQLite 3.8+ supports
--    partial indexes.
--  * ix_contact_emails_email — speeds up the cross-contact duplicate check
--    (findByAccountIdAndAnyEmail) over address books with thousands of
--    contacts; the composite (contact_id, email) is not enough for plain
--    email=:x lookups.
--  * Cross-contact uniqueness per account is enforced in the application layer.
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

CREATE INDEX ix_contact_emails_contact_id
    ON contact_emails (contact_id);

CREATE UNIQUE INDEX ux_contact_emails_contact_primary
    ON contact_emails (contact_id)
    WHERE is_primary = 1;

CREATE INDEX ix_contact_emails_email
    ON contact_emails (email);


-- =====================================================================
-- 9) REMOTE IMAGE SENDER — per-sender allow-list for loading remote (https)
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
-- 10) FTS5 SEARCH — full-text index over messages.
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
