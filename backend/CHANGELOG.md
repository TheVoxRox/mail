# Changelog

Backend technical changes and migration notes live in this file.
The release-level changelog for the whole monorepo is in `../CHANGELOG.md`.

## Compatibility matrix

Backend and frontend always ship together in the Tauri bundle release. The table is for cases where someone mixes them manually (e.g. development build, manual reinstall):

| Backend | Frontend | DB schema | Status        |
|---------|----------|-----------|---------------|
| 0.0.x   | 0.0.x    | V1        | supported     |

When upgrading to a new MAJOR/MINOR backend version the DB schema migrates forward-only via Flyway V2+. A pre-migration DB snapshot is kept by `DatabaseBackupService` in `db/mail.db.backup-pre-v<version>` (default: 3 most recent).

## Unreleased

### Added

- Sidecar lifecycle contract: graceful shutdown, `session.json` + `.ready` gate written by `HandshakeService` on `ApplicationReadyEvent`.
- First-run crypto bootstrap into `${app.data-dir}/crypto.bin`; `MAIL_CRYPTO_KEY/SALT` remain an optional dev override.
- Consolidated `/api/v1/system/readiness` endpoint that carries `appName`, `appVersion`, `apiVersion`, `minClientVersion`, `dbSchemaVersion` and the boot phase. Replaces the former `/api/handshake` (now removed); the desktop client uses readiness for the full startup compatibility check.
- Audit event `app_started` on `ApplicationReady` — fields `appVersion`, `dbSchemaVersion`, `previousAppVersion` (derived from the newest `.backup-pre-v*` snapshot).
- Startup health gate: a failing `PRAGMA quick_check` raises `IllegalStateException` with a recovery message pointing to `OPERATIONS.md` and emits the audit event `startup_health_gate_failed`.
- Translation whitelist lint (`backend/scripts/check-translation-whitelist.sh`) wired into CI; non-whitelisted Czech text in `backend/src/main/java` is a build failure.
- JEP 483 AOT cache opt-in via `backend/scripts/package-sidecar-windows.ps1 -EnableAotCache` (cold start −37 % on a plain `java -jar` baseline, see `PERFORMANCE_BASELINE.md`).
- Spring AOT activated via Maven profile `aot` (`mvn -Paot package`).

### Changed

- SpotBugs `check` goal bound to the Maven `verify` lifecycle phase via plugin `<executions>` in `pom.xml`. Previously `spotbugs:check` ran only when invoked explicitly, so a local `mvn verify` silently skipped a gate the CI workflow enforced — a real bug (the `DMI_RANDOM_USED_ONLY_ONCE` finding listed under Fixed) was latent for six months because of that gap. CI workflow simplified: the dedicated `Run SpotBugs` step removed; `Run unit + integration tests + SpotBugs` is now one job step.
- OpenAPI surface audit (see `backend/docs/OPENAPI_AUDIT.md`) — A-tier fixes applied to the snapshot before first release:
  - A1: `GET /api/v1/notifications/stream` no longer leaks the framework `SseEmitter` class as its response schema. `NotificationController.stream` rewritten with `@ApiResponse(content = oneOf = [SyncNotification, SendNotification, ThreadUpdated])`. Both DTO records now land in `components.schemas`.
  - A2: `@Operation(description=...)` on the SSE stream enumerates every event variant (`sync_completed`, `send_completed`, `send_failed`, `thread_updated`) instead of only `sync_completed`.
  - A3: `OpenApiConfig` no longer advertises "Electron mail client" — replaced with "Tauri desktop client" / "desktop client" in the `Info.description` and the `ApiKeyAuth` scheme description.
  - A4: Explicit `@ApiResponse(responseCode = "202")` added on every async POST (`MailWriteController.sendEmail`, `DraftController.saveDraft`, `DraftController.sendDraft`, `MailActionController.triggerSync`) and explicit `@ApiResponse(responseCode = "204")` added on every `ResponseEntity`-returning DELETE / PATCH that drifts (`MailActionController.deleteMessage`, `MailActionController.moveMessage`, `MailActionController.updateMessageFlag`). springdoc auto-detection had defaulted these to 200 because it cannot statically prove the runtime status of a `ResponseEntity` return type. `void` + `@ResponseStatus(NO_CONTENT)` were already correct and were left alone.
- B-tier OpenAPI cleanups:
  - B3: `GET /api/v1/messages/{stableId}/attachments/{partPath}` no longer documents `StreamingResponseBody` (the Spring framework class) as its body schema. Replaced with an explicit `@ApiResponse(200, content = @Content(mediaType = "application/octet-stream", schema = @Schema(type = "string", format = "binary")))`.
  - B4: SSE operation `operationId` renamed from the generic `stream` to `streamNotifications` so client generators do not collide if a second `stream` endpoint ships later.
- Pre-migration DB backup runs only when `flyway.info().pending().length > 0` (previously on every start). Retention 3 most recent (`mail.backup.retention-count`). Audit events `db_backup_created` / `db_backup_pruned`.
- Springdoc excluded from the fat jar via `spring-boot-maven-plugin`; `OpenApiConfig` is guarded by `@ConditionalOnClass`. Use the `openapi` profile to build a fat jar that includes docs.
- JVM args in the sidecar packaging script tuned for faster startup: `-XX:TieredStopAtLevel=1`, `-Xms64m`, `-Xmx384m`, `-XX:+UseSerialGC`, `-Dspring.aot.enabled=true`.
- Flyway migrations consolidated: the former `V2__add_modseq.sql` (`last_known_modseq` column) is folded into `V1__init.sql`. Safe before first release because the project carries no production data.
- All Czech programmer text in `src/main/java`, `src/test/java`, Flyway migrations and packaging scripts translated to English. Permanent whitelist entries (SMTP-bound labels, Czech IMAP folder name fixtures, UTF-8 stress fixtures) live in `backend/docs/translation-whitelist.txt`.

### Fixed

- `DMI_RANDOM_USED_ONLY_ONCE` SpotBugs finding in `HandshakeService.generateApiKey()` — the in-memory handshake API key generator was constructing a fresh `SecureRandom` on every call. Cached as a `private static final SecureRandom RANDOM` (SecureRandom is thread-safe and meant to be reused; default Windows provider is CryptGenRandom-backed and never blocks). Same observable behaviour; lets the build pass `mvn verify` cleanly once spotbugs:check is wired into the lifecycle (see Changed). Latent since the Faze 6.11 refactor that replaced the persisted `internal_api_key`.
- Pool-starvation deadlock in `mailSyncExecutor` that left user-initiated SMTP send / draft save stuck in `WAITING` indefinitely. Root cause: a single throttled executor (concurrency limit = 4) was shared between background sync, user actions and downstream event handlers; `MailSyncService.performFullSyncCycle` recursively submitted `MailboxMaintenanceService.enforceLocalWindowLimitAsync` and the `MailSyncCompletedEvent` listener back to the same pool while holding the per-account IMAP lock, draining permits. Split into three executors: `mailSyncExecutor` (background sync, limit 4), `userMailExecutor` (SMTP send / draft save / draft send + IMAP move/flag, limit 8), `mailEventExecutor` (event handlers + maintenance, unbounded virtual threads). The sync executor docstring now documents the no-recursive-submit rule.
- IMAP read / connection timeouts had no real effect in production. `ImapConnectionManager` was passing `Duration` objects directly into the JavaMail `Properties` map, but JavaMail's `PropUtil.getIntProperty` reads via `props.getProperty(name)` which returns `null` for non-String values. As a result `mail.imap{,s}.timeout` and `mail.imap{,s}.connectiontimeout` were silently ignored, leaving IMAP sockets without a timeout (silent fallback to infinite). Fix: pass `String.valueOf(...toMillis())` and document the JavaMail constraint with an inline comment. Slow servers like Seznam now surface a `SocketTimeoutException` after 60 s instead of hanging indefinitely.
- IMAP fetch robustness: `MessageFetcher.mapToResponse` previously dropped the whole message when `MimePartExtractor.extractAttachmentMetadata` triggered a lazy BODYSTRUCTURE fetch that the server returned malformed (typical for some Seznam INBOX messages). The MIME walk is now wrapped in a try/catch: on failure the message is persisted as an envelope-only stub with `attachments=[]`, `hasAttachments=false` and `contentError` set so the user still sees subject / sender / date in the list. The detail endpoint re-attempts the content fetch and surfaces `contentError` dynamically via `MailFacade.getMessageDetail`.
- `OAuth2TokenServiceRegistry.find()` removed (zero callers); `OAuth2TokenServiceRegistry.resolve()` is the supported lookup.
- Boot blocker after the Microsoft public-client migration: `MicrosoftTokenService` still injected `@Value("...microsoft.client-secret")` without a default, but the property no longer exists — the unresolvable placeholder crashed the Spring context on every start. The abstract `clientSecret()` hook was removed from `OAuth2TokenService`; the shared refresh body is now public-client-only (`client_id`, `refresh_token`, `grant_type`) and Google adds its installed-app `client_secret` via `customizeRefreshBody`. `MicrosoftTokenServiceTest` asserts `client_secret` is absent from the refresh body (a public client sending one fails with AADSTS700025). The dummy microsoft client-secret system properties were dropped from `StartupSmokeTest` / `OpenApiSnapshotTest` / `OpenApiEndpointDisabledTest`, so the startup test now matches the real run.
- Zombie JVM after a failed start: `MailBackendApplication.main` now wraps `app.run(args)` and forces `System.exit(1)` when the context refresh fails — non-daemon threads otherwise kept the process alive after "Application run failed", holding the data dir so the next start died with exit 78 ("already running"). `SpringApplication.AbandonedRunException` is rethrown so the build-time `spring-boot:process-aot` run (which invokes `main()` and aborts it on purpose) keeps working.

### Backend hygiene (pre-first-release cleanup, phases 6.11–6.18)

- `feature/config/settings` subsystem removed — the persisted `internal_api_key` record was never load-bearing (frontend reads `session.json` on every sidecar start anyway), so `HandshakeService` now generates the API key in memory on each start. `V1__init.sql` no longer creates the `settings` table.
- Crypto subsystem trimmed: dead helpers (`generateRandomBytes`, base64 round-trip helpers) and dead configuration knobs (`mail.crypto.algorithm`, `tag-length-bit`, `iv-length-byte`, `key-length-bit`) deleted; the values are now `static final` constants in `CryptoService` since changing them would silently break decryption of existing ciphertexts (no migration path).
- Single-impl interfaces collapsed to concrete classes (Mockito 5+ mocks concrete types without issue): `CryptoService`, `MailConnectionProbe`, `TokenCache`.
- Google + Microsoft `TokenService` deduplicated via an abstract `OAuth2TokenService` base class (~170 lines of duplicate refresh / cache / requires_reauth / error-handling logic centralized). Provider subclasses are slim: token endpoint URL, display name, optional refresh-body customisation, and the per-provider revoke contract.
- Exception subsystem cleanup: dead `UnauthorizedException`, `AccountInactiveException` and unused `ErrorCode` enum values (`UNAUTHORIZED`, `API_KEY_REQUIRED`, `INVALID_CREDENTIALS`, `INVALID_EMAIL_FORMAT`, `ACCOUNT_INACTIVE`) removed along with their i18n keys.
- Dead code removed: `LogMasker` public helpers without callers (`maskIdentifier`, `maskToken`, `lazyUsername`, `lazyToken`, `lazyIdentifier`), `LogCategory.CONFIG`, `SmtpProperties.{defaultPort, protocolSsl, protocolStandard}`, `AccountLastErrorCode.CRYPTO_CREDENTIAL_DECRYPT_FAILED`.

### Added (cont.)

- Hybrid pagination phase 1: `MailFacade.getEmails` exposes a server-count-aware fast-path. `FolderCountCache` (per `(account, folderName)`, TTL ~60 s, `Clock`-injectable for tests) avoids hammering IMAP `STATUS` when the local page already covers the request. Stale `localCount` race with the periodic sync eliminated by reading the count inside `executeInFolder` under the per-account lock.
- Send notifications via SSE: `MailWriteController.sendEmail` / `DraftController.sendDraft` return a server-generated `sendId` in the 202 body and asynchronously broadcast `send_completed` / `send_failed` events (carrying the same `sendId` plus an `AccountLastErrorCode`). `SseEvent` sealed interface added to model both `SyncNotification` and the new `SendNotification`.

### Added — threading / conversations (Phase 1, backend-only)

- V2 Flyway migration `add_threading.sql` — three columns on `messages` (`thread_id`, `thread_root_message_id`, `thread_position`) + two composite indexes (`account_id, thread_id` and `account_id, thread_root_message_id`). Nullable to support the post-migration backfill window.
- `ThreadingService` — JWZ-light algorithm (direct In-Reply-To match → References walk oldest-to-newest, capped at 50 → new thread fallback → late-arriving-parent reconciliation that merges orphan chains). Per-account scope. Subject clustering deliberately skipped (false positives on common subjects like `"Re: Hi"`). Wired into `MessageDownloader.saveMessagesBatchAtomic` after entity persistence so each message gets a `thread_id` as part of the sync transaction. 12 golden-fixture unit tests covering the JWZ corpus including Gmail cross-folder Message-ID dupes.
- `ThreadingBackfillService` — runs once on `ApplicationReadyEvent` asynchronously (Spring `mailEventExecutor`, `Ordered.LOWEST_PRECEDENCE` so `session.json` / `.ready` are written first). Walks every `active=true && requires_reauth=false` account, assigns `thread_id` to every `null` row in ascending `receivedAt` order. Audit events `threading_backfill_started`, `threading_backfill_completed`, `threading_backfill_skipped_account`.
- `ThreadResponse` DTO + `MailFacade.getThread(accountId, threadId)` with ownership scoping via the `account_id` predicate in the query. Throws `ResourceNotFoundException` when no row in the account belongs to the thread.
- New endpoint `GET /api/v1/messages/account/{accountId}/threads/{threadId}` in `MailReadController` returning a `ThreadResponse`. Standard `ProblemDetail` 401/404/500/503 responses via the global customizer plus explicit `@ApiResponse(200/404)`.
- New `thread_updated` SSE event variant — `ThreadUpdated` record + extended `SseEvent` `permits` clause + `oneOf: [SyncNotification, SendNotification, ThreadUpdated]` in the OpenAPI snapshot. Emitted when a new message lands in an existing thread or when late-arriving-parent reconciliation merges orphan chains.
- Hidden `POST /api/internal/threading/recompute?accountId=N` — synchronous reset for QA and support after manually corrupting threading state or importing older mailboxes that bypassed the sync path.
- `MailSummaryResponse` and `MailDetailResponse` advertise a nullable `threadId` field; the JPQL projection in `MessageRepository.findSummariesByAccountAndFolder` selects it. The V0.1.0 frontend types and `type-contract.ts` guards are generated, but the V0.1.0 UI does not yet consume them — see `backend/docs/THREADING_DESIGN.md` for the Phase 2 plan.

### Removed

- `PATCH /api/v1/accounts/{id}` and the entire partial-update surface. `AccountController.partialUpdateAccount`, `AccountPatchRequest` DTO, `AccountService.patchAccount` + `applyServerConfigPatch`, the matching frontend types and msw handlers, the `validation.account.patchProviderXorCustom` i18n key and 15 backend test scenarios all deleted (2026-06-01, OPENAPI_AUDIT.md B2-Vol3). The desktop frontend has always used `PUT` for account edits; the PATCH surface had working logic and tests but no caller. Trimmed before the first public release to keep the v0.1.0 API surface minimal — `git show` on the removal commit restores the logic verbatim if a future partial-edit UX is added.
- `GET /api/v1/messages/account/{accountId}/folder/{folderRef}` (the path-variant of the folder listing endpoint). The query-variant `?folderRef=...` covers the same logic; the duplicate was a stale artifact of incremental API design (2026-06-01, OPENAPI_AUDIT.md B1-a).
- `/api/handshake` REST endpoint and its `HandshakeController` / controller test. The frontend never called it after the readiness consolidation; the data is exposed via `/api/v1/system/readiness`. `HandshakeResponse` survives as an internal DTO between `HandshakeService` and `SystemReadinessService`.
- `CryptoMigrationService` subsystem (re-encrypt of legacy PBKDF2-210k → 600k ciphertexts) plus `CryptoService.reEncryptIfNeeded`, the legacy decrypt fallback path, the `mail.crypto.legacy-pbkdf2-iterations` property and three legacy tests. The project has no production data; future crypto-key rotation means wiping the dev DB and starting over rather than in-place migration.
- `SyncStateService.getOrCreateState(2-arg)` — dead variant with no caller.
- `MessageMapper.LEGACY_NO_SUBJECT` / `LEGACY_UNKNOWN_SENDER` sentinels used to detect pre-i18n historic DB rows. Pre-production, so historic rows do not exist.

## Template for a new release

```
## [0.X.0] - YYYY-MM-DD

### Breaking changes
- (none / describe the incompatible change + user migration steps)

### Migrations applied
- V<N>__<description>.sql — what it does, whether it requires a pre-existing data fixture test

### OAuth scope changes
- (none / new scopes, user re-consent required)

### Manual steps
- (none / e.g. "revoke the previous OAuth grant and re-run the login flow")

### Added / Changed / Fixed
- ...
```
