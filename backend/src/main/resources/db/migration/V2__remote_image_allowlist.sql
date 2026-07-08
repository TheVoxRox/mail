-- Per-sender allow-list for loading remote (https) images in HTML mail bodies.
-- Remote images are blocked by default (tracking-pixel defense, see
-- docs/CONTENT_RENDERING_AUDIT.md finding F2); a sender the user has explicitly
-- trusted here has that sender's messages' remote https images auto-loaded.
-- Account-scoped so the decision is isolated per account and is cleaned up by
-- the ON DELETE CASCADE when the account is removed.
CREATE TABLE remote_image_sender (
    id           INTEGER      PRIMARY KEY AUTOINCREMENT,
    account_id   INTEGER      NOT NULL,
    sender_email VARCHAR(255) NOT NULL,
    created_at   DATETIME     NOT NULL,
    CONSTRAINT uk_remote_image_account_sender UNIQUE (account_id, sender_email),
    FOREIGN KEY (account_id) REFERENCES accounts(id) ON DELETE CASCADE
);
