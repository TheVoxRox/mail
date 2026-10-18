-- Crash guard for an account's sync (SyncCrashGuard in feature/mail/service).
--
-- The first schema change after a build was installed outside development,
-- so it is a migration of its own rather than an edit of V1, whose checksum
-- FlywayBaselineChecksumTest freezes.
--
-- sync_started_at: set while a sync of the account runs, cleared when the last
--   one ends. Still set at startup means the previous backend process ended
--   in the middle of it.
-- sync_crashes: how many processes in a row ended that way, counted only when
--   the shell restarted the backend after an exit it did not ask for. Back to
--   0 whenever a sync ends inside the process.
-- sync_paused: set once sync_crashes reaches the limit. The scheduled pass and
--   the folder sync on the read path skip the account; a manual sync clears it.
ALTER TABLE accounts ADD COLUMN sync_started_at DATETIME;
ALTER TABLE accounts ADD COLUMN sync_crashes INTEGER NOT NULL DEFAULT 0;
ALTER TABLE accounts ADD COLUMN sync_paused BOOLEAN NOT NULL DEFAULT 0;
