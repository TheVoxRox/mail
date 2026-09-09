# VoxRox Mail Backend — operations runbook

Internal maintenance guide for the single-user desktop backend. The goal is to tell quickly whether a problem sits in the sidecar startup, the database, the IMAP/SMTP/OAuth layer, or the client.

## Base paths

The default standalone data directory of the backend:

```text
${user.home}/.voxrox/mail
```

The desktop/Tauri release on Windows uses an explicit data directory:

```text
%LOCALAPPDATA%\VoxRox\Mail
```

Contents:

```text
crypto.bin                         local master key + salt (Windows: DPAPI-protected, VOXSEC1 format), created on the first start
session.json                       current port, baseUrl, X-API-KEY and API version
.ready                             ready signal for the Tauri client
db/mail.db                 SQLite DB
db/mail.db-wal             SQLite WAL, if the DB is active
db/mail.db-shm             SQLite shared memory file
logs/mail.log              main application log
logs/audit.log                     security/audit log
attachments/                       local attachments
tmp/                               temporary files
```

`app.data-dir` can be overridden with a JVM argument:

```powershell
java -jar target\mail-backend-0.1.0.jar --app.data-dir=C:\temp\mail-smoke
```

## Start and sidecar

By default the backend listens on loopback only:

```text
server.address=127.0.0.1
server.port=0
```

`server.port=0` means a random free port chosen by Spring. The actual port is
written to `session.json` after startup as `port` and `baseUrl`; the client must
never guess a fixed port number.

For diagnostics, or for compatibility with a provider that requires an exact
OAuth redirect URI, the port can be overridden temporarily:

```powershell
java -jar target\mail-backend-0.1.0.jar --server.port=60100
```

When an explicitly chosen port is already taken, the backend exits before the
Spring start with exit code `78` and a readable message.

The Tauri client is expected to wait for `${app.data-dir}/.ready`, then read `${app.data-dir}/session.json` and use `baseUrl` + `apiKey`. If `.ready` does not appear, look for the error in `logs/mail.log`.

## Health check

Endpoint:

```text
GET /api/internal/health
```

It is protected by the internal API key from `session.json`. Mind how the URL is
assembled: `baseUrl` already contains `/api`
(`http://127.0.0.1:<port>/api`, see
[HandshakeService.java](src/main/java/org/voxrox/mailbackend/core/init/HandshakeService.java)),
so `/internal/...` is appended to it, not `/api/internal/...` — otherwise you get
`/api/api/...` and the answer is a 404 that looks like a dead backend:

```powershell
$session = Get-Content "$env:LOCALAPPDATA\VoxRox\Mail\session.json" | ConvertFrom-Json
Invoke-RestMethod "$($session.baseUrl)/internal/health" -Headers @{ "X-API-KEY" = $session.apiKey }
```

Health carries the DB state, disk space and the `sync` component. `requiresReauth` accounts do not pull health `DOWN`; they mean a user action is needed (sign in to the OAuth account again).

## Diagnostic dump

Internal support snapshot:

```text
GET /api/internal/diagnostic-dump
```

The endpoint is protected by the same `X-API-KEY` as the health check and returns a ZIP attachment. It contains `summary.json`, `accounts.json`, `folder-sync-states.json`, `message-counts.json` and `runtime.json`.

The dump deliberately carries no passwords, OAuth tokens, internal API key, message content, message subjects or full email addresses. Accounts are listed with a masked email, the provider, the authentication type, the IMAP/SMTP host/port/SSL configuration, the reauth state and a flag for whether a `last_error` exists.

Windows PowerShell:

```powershell
$session = Get-Content "$env:LOCALAPPDATA\VoxRox\Mail\session.json" | ConvertFrom-Json
Invoke-WebRequest "$($session.baseUrl)/internal/diagnostic-dump" -Headers @{ "X-API-KEY" = $session.apiKey } -OutFile "$env:TEMP\mail-diagnostic.zip"
```

## Logs

Main log:

```text
${app.data-dir}/logs/mail.log
```

Audit log:

```text
${app.data-dir}/logs/audit.log
```

The main log rotates at `10MB`, keeps 7 files and caps at `100MB`. The audit log has a longer retention: 365 days, `10MB` per file, `500MB` cap.

Quick search for the latest errors in Windows PowerShell:

```powershell
Select-String "$env:LOCALAPPDATA\VoxRox\Mail\logs\mail.log" -Pattern "ERROR|WARN|CRITICAL" | Select-Object -Last 80
Select-String "$env:LOCALAPPDATA\VoxRox\Mail\logs\audit.log" -Pattern "FAILURE|CRITICAL" | Select-Object -Last 80
```

## Database and backups

SQLite runs with WAL:

```text
journal_mode=WAL
synchronous=NORMAL
foreign_keys=ON
busy_timeout=5000
cache_size=-20000
```

On startup the backend verifies `PRAGMA quick_check`; anything other than `ok` fails the start fast and writes `db_corruption_detected` into the audit log.

A safe backup:

1. Quit the Tauri client and verify that no Java sidecar is running.
2. Copy the whole `${app.data-dir}` directory, not just the `.db` file.
3. To restore, put the whole directory back including `crypto.bin`; without it the credentials cannot be decrypted.

Note (Windows): `crypto.bin` is protected through DPAPI in **USER scope** — only
the same Windows user on the same machine can decrypt it. Restoring a backup
under a different user account or on a different computer therefore will not
unlock the credentials; on startup the accounts are marked `requiresReauth=true`
and a fresh login is required (the same behaviour as losing `crypto.bin`). Mail,
contacts and the rest of the data in `mail.db` stay readable — the DB itself is
not encrypted.

DB consistency after a suspicious crash:

```sql
PRAGMA quick_check;
SELECT version, success FROM flyway_schema_history ORDER BY installed_rank;
```

`quick_check` must return `ok`; Flyway V1 must have `success = 1`.

## JVM tuning and Spring AOT

The sidecar JVM (jpackage app-image, Java 25) runs with tuning aimed at the
single-user desktop scenario — unlike the server-oriented JVM defaults, which
are optimised for throughput over a long lifetime, the sidecar needs a **fast
start** and a **small heap**. The current arguments from
`backend/scripts/package-sidecar-windows.ps1`:

```text
--enable-native-access=ALL-UNNAMED  Java 25 native access (JNI) without a warning.
-Dfile.encoding=UTF-8               Consistent encoding across platforms.
-Dspring.aot.enabled=true           Activates the Spring AOT artifacts from the jar (see below).
-XX:TieredStopAtLevel=1             C1 JIT only (~10-15 % faster cold start).
-Xms64m / -Xmx384m                  Single-user sidecar; the default Xmx (1/4 RAM)
                                    is an order of magnitude more than needed.
-XX:+UseSerialGC                    1 GC thread = minimal startup overhead
                                    and a small footprint for a small heap.
```

**Spring AOT** is enabled through the Maven profile `aot` (`mvn -Paot package`).
The profile adds the `spring-boot-maven-plugin` goal `process-aot`, which
generates `__BeanDefinitions.java` classes into `target/spring-aot/main/sources`
and packs them into the fat jar. At runtime with `-Dspring.aot.enabled=true`
Spring uses those context classes instead of the reflective bean factory, which
cuts 20-40 % off the cold start (measured empirically for this project; see
`PERFORMANCE_BASELINE.md`).

AOT is the `aot` profile, **NOT** the default — `mvn test` and
`mvn spring-boot:run` do not run it, so the dev/test build is not slowed down by
`process-aot` starting an AppContext internally during the build.

**Springdoc** (the Swagger UI starter) is excluded from the fat jar through
`spring-boot-maven-plugin` `<excludeGroupIds>` on both the `repackage` and
`process-aot` executions (CAREFUL: `<excludes>` requires both groupId and
artifactId — an entry with groupId alone silently matches nothing; the exclusion
on `process-aot` is necessary, otherwise AOT generates springdoc
`__BeanDefinitions` referring to classes missing from the jar). It shrinks the
fat jar by ~3 MB and shortens the classpath scan by ~200-500 ms.
For a fat jar WITH Swagger docs (a debug build):

```powershell
mvn -Popenapi -Dspringdoc.api-docs.enabled=true `
    -Dspringdoc.swagger-ui.enabled=true package
```

Dev `mvn spring-boot:run` still has springdoc on the compile classpath — Swagger
UI works normally during development.

### JEP 483 AOT class cache (experimental, default OFF)

Java 25+ supports "ahead-of-time class loading & linking" — on exit the JVM
writes a binary cache of class loading + linking metadata, which is attached on
the next start instead of being rebuilt from the jar. Combined with Spring AOT
this can shorten the cold start by a further ~20-40 % beyond the other JVM
optimisations.

**The default is OFF** in `package-sidecar-windows.ps1` (parameter
`-EnableAotCache`). The cache is enabled explicitly because of its size (~115 MB
next to the jar) and its binding to the exact jar hash + Java version — it has to
be regenerated on every jar rebuild.

> Historical note: an older version of the codebase had `@Lazy <Self> self`
> injection in `ContactService` and `MailContentService`, which failed under
> Spring AOT (even without `-XX:AOTCache`) with a `ClassCastException` in
> `CglibAopProxy.setCallbacks` — the proxy generated through
> `ContextAnnotationAutowireCandidateResolver.buildLazyResolutionProxy` received
> `SerializableNoOp` instead of `Dispatcher`. The bug was resolved by moving to
> `ObjectProvider<Self>` (Spring native lazy resolution, which does not go
> through CGLib). If you ever reach for `@Lazy <Self>` injection again, or an
> equivalent CGLib lazy proxy, test the startup from a `mvn -Paot package` jar,
> not only from `spring-boot:run`.

Workflow (when enabled):

1. `package-sidecar-windows.ps1 -EnableAotCache` runs `generate-aot-cache-windows.ps1`
   after the jpackage app-image step:
   - phase 1 (record): a JVM with `-XX:AOTMode=record -XX:AOTConfiguration=<file>`,
     and the env `MAIL_AOT_TRAINING_RUN=1` activates `AotTrainingExitListener`,
     which calls `System.exit(0)` after `ApplicationReadyEvent`. The JVM writes
     the config file.
   - phase 2 (create): a JVM with `-XX:AOTMode=create -XX:AOTConfiguration=<file>
-XX:AOTCache=<file>` reads the config and builds the binary cache (~100-200 MB).
   - the cache is copied to `<install_dir>/app/mail.aot`.
2. The jpackage launcher then starts production with `--java-options
"-XX:AOTCache=app\mail.aot"` (a path relative to the install dir).

Manually (experimental, against a `mvn package` jar):

```powershell
.\scripts\generate-aot-cache-windows.ps1 `
    -JarPath target\mail-backend-0.1.0.jar `
    -CachePath target\mail.aot
```

The cache is bound to the exact jar hash + Java version — it must be regenerated
when the jar is rebuilt. The AOT record phase typically returns a non-zero exit
because of `Preload Warning: Verification failed` on Spring Security
configurations (SAML, OAuth2 server, LDAP) — those warnings are benign and the
script ignores them as long as the config file was produced.

### Measuring the cold start

```powershell
# After `.ready` is written, the backend logs a summary:
Get-Content "$env:LOCALAPPDATA\VoxRox\Mail\logs\mail.log" |
  Select-String "Startup timing|Started MailBackend|spring.application-ready" |
  Select-Object -First 30
```

For frontend boot timings: the dev console in `tauri:dev`, store `bootState.timings`.

## Update process

Updates arrive as a whole Tauri bundle (frontend + backend sidecar together). A version mismatch therefore cannot happen — what the release pipeline signed reaches the user atomically.

What happens when a new version starts:

1. The backend starts from the same `${app.data-dir}` as the previous version; the installer does not delete the data dir.
2. Before `flyway.migrate()`, `DatabaseBackupService` writes a consistent snapshot of the DB through `VACUUM INTO` as `db/mail.db.backup-pre-v<currentAppVersion>` (idempotent — a no-op if one already exists for that version). `VACUUM INTO` makes a transactionally consistent, self-contained copy **including committed data still sitting in an uncheckpointed `-wal`** — a plain file copy of the main `.db` would silently drop the last transactions from the WAL after an unclean shutdown (crash / killed sidecar), and the restore point would be incomplete.
3. It prunes old backups outside the retention window (default: the 3 newest, see `mail.backup.retention-count`).
4. It applies the cumulative Flyway migrations. Before the first release there is
   a single `V1__init.sql` (older V2/V3 were repeatedly folded into it, see
   `backend/CHANGELOG.md`); from the v0.1.0 publish on it is frozen and every
   further schema change arrives as `V2+` (the rule is in `RELEASE_CHECKLIST.md`
   §8b).
5. `verifySqlitePragmas` verifies `PRAGMA quick_check`. A failure → fail-fast with a recovery message + the audit event `startup_health_gate_failed`.
6. The `app_started` audit record captures `appVersion`, `dbSchemaVersion` and `previousAppVersion` (derived from the newest backup file).

The Tauri client reads `dbSchemaVersion` from the handshake response and logs it into the diagnostic dump for post-update support.

Manual fallback if the Tauri updater fails (network timeout, signature mismatch, disk full): download the current `voxrox-mail-<version>-windows-x64-setup.exe` from GitHub Releases by hand and run it "over" the installation. Data files are preserved. Downgrading with the ordinary installer is forbidden because of the database migrations.

### Update troubleshooting

`The sidecar does not start after an update` (the audit log contains `startup_health_gate_failed`) → restore the DB from the newest backup:

```powershell
# 1. Stop the backend (kill the Tauri / Java sidecar process)
# 2. Find the newest backup
Get-ChildItem "$env:LOCALAPPDATA\VoxRox\Mail\db\mail.db.backup-pre-v*" |
  Sort-Object LastWriteTime -Descending | Select-Object -First 1
# 3. Move the damaged DB aside + clear its stale WAL/SHM (otherwise the old WAL
#    would be applied to the restored DB and could damage it)
Move-Item "$env:LOCALAPPDATA\VoxRox\Mail\db\mail.db" `
          "$env:LOCALAPPDATA\VoxRox\Mail\db\mail.db.broken"
Remove-Item "$env:LOCALAPPDATA\VoxRox\Mail\db\mail.db-wal", `
            "$env:LOCALAPPDATA\VoxRox\Mail\db\mail.db-shm" -ErrorAction SilentlyContinue
# 4. Restore from the backup (replace <BACKUP> with the file name from step 2)
Copy-Item "$env:LOCALAPPDATA\VoxRox\Mail\db\<BACKUP>" `
          "$env:LOCALAPPDATA\VoxRox\Mail\db\mail.db"
# 5. Run the support-approved recovery build/procedure (the downgrade installer is blocked)
# 6. Report the bug with a snippet of audit.log
```

`The sidecar does not start after an update` with the audit event `db_migration_altered_after_apply` (detail `V<n> CHECKSUM_MISMATCH` / `DESCRIPTION_MISMATCH` / `TYPE_MISMATCH`) → **restoring from a backup will NOT help here and the procedure above does not apply to this case.** The database is fine; the faulty part is a build carrying a different version of an already-applied migration than the one recorded in `flyway_schema_history`. It can only arise by editing an existing migration after the release instead of adding a new one (the build gate `FlywayBaselineChecksumTest` stands against that).

Procedure:

1. Read the `app_started` line of the previous run from the audit log — `dbSchemaVersion` and `dbSchemaChecksum` say what schema the installation actually has.
2. **Do not run `flyway repair`** as a blanket fix. Repair overwrites the recorded checksums with whatever the new build carries, which turns a loud refusal to start into silent acceptance of a schema the database does not have — an unstartable application becomes corrupted data.
3. The fix is roll-forward: ship a higher version whose migrations match (typically revert the edit to that migration + a new `V<n+1>__*.sql` carrying the intended change). Downgrading with the installer is blocked.
4. If the faulty build already went out to a channel, re-point the beta channel to the last good tag (`beta-channel.yml`, `force=true`) — see "Release channels".

`db_backup_failed` in the audit log at startup → check the free disk space and the permissions on `${app.data-dir}/db/`. The backup cannot fail silently — if it does, Flyway migrate does not run at all and the user stays on the previous schema version.

`The update notification does not appear` (the Tauri client does not report a new version) → check the `tauri.conf.json` `plugins.updater.endpoints` URL (in Tauri 2 the updater is a plugin, **not** `bundle.updater` — looking under `bundle` finds nothing) and that the manifest signing key matches `pubkey`. On the beta channel, additionally verify that the `beta` release holds a fresh `latest.json` (see Release channels below).

## Release channels

This section describes the channels and what to do when something goes wrong.
The ordered release procedure (version → changelog → tag → build → draft →
publish) is in [docs/RELEASE_PROCESS.md](../docs/RELEASE_PROCESS.md).

The updater has two channels. Each installation holds the choice in Settings → About (default Stable; webview `localStorage` key `mail.updateChannel`). The channel maps onto a manifest URL in the Tauri shell (`check_for_update` in `frontend/src-tauri/src/lib.rs`) — the webview never passes a URL, only a channel name.

| Channel | Manifest                                                                      | Who fills it                                                      |
| ------- | ----------------------------------------------------------------------------- | ----------------------------------------------------------------- |
| Stable  | `releases/latest/download/latest.json` (GitHub redirect; ignores prereleases) | publishing a full release                                         |
| Beta    | `releases/download/beta/latest.json` (the moving `beta` prerelease)           | the workflow `.github/workflows/beta-channel.yml` on each publish |

**Never publish a release without `latest.json` + `.sig`** (not even "just a tag with notes"): the stable channel is a GitHub redirect to the latest non-prerelease publish, so a bare release immediately becomes "latest" and every installation gets an update-check error on every start. Anything that is not a full signed build stays a draft or a prerelease (review #170).

### The release model: everything goes through beta

Decided 2026-08-28. **Every change ships as a beta first and is promoted to stable only after testing.** Beta is not a parking place for unfinished work — it carries finished features that already passed developer testing; beta only means a wider circle of users.

Two properties follow, and the rest of this section rests on them:

- **There is no stable release that skipped beta** — with a single recorded exception, the first ship `v0.1.0` (see below).
- **There is no beta that cannot be promoted** — when stable needs a fix, the fix goes into the beta line and is promoted with it. There is never a need to ship `0.1.1` alongside a running beta `0.2.0-beta.1`, because `0.2.0` is releasable by definition.

**Exception: the first ship `v0.1.0` goes straight to stable.** Decided 2026-09-09. The model protects the stable audience by putting a beta cycle in front of it — but at the first ship there is no second audience; the closed beta _is_ the whole audience. The second half is what decides it: a prerelease-suffixed tag would leave the **stable channel empty** (the `releases/latest` redirect skips prereleases), while the default channel of every installation is `stable`. Anyone who does not switch the channel in Settings → About would sit on a channel with no manifest, and the startup check would fail **silently** — the background check only `console.warn`s, deliberately, so that it does not raise a dialog on every cold start. Silently not receiving updates is the worst failure shape that path has. With stable-first, by contrast, a tester who never touches the settings rides the stable line and gets every promotion. The "beta" label does not hang on the tag: it is carried by the status badge on [voxrox.org](https://voxrox.org) and by the release title. **From `0.2.0` on the model holds literally** and the exception does not repeat.

**This is why a red `beta-channel.yml` is a signal, not noise.** The guard rejects a candidate older than the current beta manifest, and in this model such a candidate has no legitimate way to arise — red means something happened outside the model, typically a re-publish of an old tag. **Do not solve it with `force=true`**; that is reserved for the HALT below. First find out which publish triggered it.

**What the model costs:** a fix reaches stable users only after a beta cycle, not immediately. For a closed beta (~45 testers) that is acceptable. If a fix genuinely could not wait, you are outside the model — the procedure is then: build `0.1.1` from the tag `v0.1.0` with that fix alone, publish it (stable users get it), **expect `beta-channel.yml` to end red and leave it that way** (the beta manifest must not be overwritten, it would send testers backwards), and get the fix into the beta line separately as `0.2.0-beta.2`. Until then the beta testers are on an unfixed version — which is exactly why this path is not used.

### Shipping a beta build

1. Set the prerelease version (`0.2.0-beta.1`) in `tauri.conf.json`/`package.json`/`version.ts` and tag `v0.2.0-beta.1`. The release workflow checks that the tag matches the version, and a prerelease-suffixed tag creates the release with `--prerelease`; if the release already exists (pre-drafted notes, a partial earlier run), the workflow adds the flag through `gh release edit` — without it, publishing would hand the beta build to the `releases/latest` redirect of the stable channel.
2. After a manual Publish of the release, `beta-channel.yml` overwrites `beta/latest.json`. The stable channel does not see the build — the `releases/latest` redirect skips prereleases.

### Promotion to stable

Ship a full version (no suffix) through the ordinary release process. Publishing triggers `beta-channel.yml` here too, so beta users converge onto the same stable build (SemVer: `0.2.0` > `0.2.0-beta.1`) and the beta channel never lags behind stable.

### HALT — withdrawing a faulty beta

1. Switch the faulty prerelease in GitHub Releases back to draft (or delete it) — that ends manual installer downloads.
2. Re-point the beta manifest to the last good version: Actions → "Beta Channel Manifest" → Run workflow with `tag=<last good tag>` and `force=true`. The guard (`frontend/scripts/beta-channel-guard.mjs`) otherwise refuses a manifest downgrade — `force` exists precisely for this step.
3. Anyone who already installed the faulty beta will not get a downgrade (`allowDowngrades: false` + DB migrations) — for them the roll-forward below applies.

### Roll-forward (fixing an already-installed faulty version)

There is no binary rollback: both the updater and the installer block a downgrade because of the DB migrations. The fix ships as a NEW higher version (revert/fix the code, bump the patch — e.g. a faulty `0.2.0-beta.1` → fix `0.2.0-beta.2`; a faulty stable `0.2.0` → `0.2.1`). If the faulty version damaged data, restore from the pre-migration backup, see "Update troubleshooting" above.

## Account reset

The preferred procedure is deleting the account through the application. Emergency SQL procedure for support:

1. Stop the backend.
2. Back up the whole `${app.data-dir}`.
3. Delete the account in SQLite by ID or by email:

```sql
DELETE FROM accounts WHERE email = 'user@example.com';
```

The FK `ON DELETE CASCADE` deletes the credentials, sync state, messages, contacts and related rows. After a restart the user adds the account again.

## Crypto key rotation

`crypto.bin` is the permanent local anchor for encrypting credentials and the internal API key. Do not delete it on its own.

On Windows `crypto.bin` is stored in the protected `VOXSEC1` format: the key+salt
are wrapped through Windows DPAPI (`CryptProtectData`, USER scope + app entropy),
so the file copied to another Windows account/machine is unusable. On the other
platforms (and in tests) an identity fallback is used and confidentiality rests
on the file permissions (`rw-------`), as before. Older installations with a
plaintext `crypto.bin` migrate seamlessly to `VOXSEC1` on the first start after
the update (in place, with no key change → no DB re-encrypt). DPAPI USER scope
does not protect against malware running as the same user (unprotect succeeds for
it) — that is outside the threat model.

The release and the ordinary Tauri desktop mode use `crypto.bin` as the default
key source. A local `backend/.env` may contain `MAIL_CRYPTO_KEY` /
`MAIL_CRYPTO_SALT` for an explicit backend-only override, but the Tauri
dev/release launcher does not forward them to the sidecar by default. That way
the release runs the same as a fresh install: the first start creates
`crypto.bin`, later starts reuse it. If you need to test the explicit env crypto
mode, run it separately and not against an existing user `${app.data-dir}`.

If `crypto.fingerprint` is stale or does not match the current `crypto.bin`, the
desktop bootstrap mode regenerates it from `crypto.bin` at startup. The internal
handshake API key is generated in memory on every sidecar start and written to
`session.json` — no persistent artifact, so rotating crypto material does not
affect it. User credentials are separate: passwords/OAuth tokens cannot be
recovered without the original `crypto.bin` or the original env crypto values.
Startup marks such an account as `requiresReauth=true`, sets `last_error` and the
application continues; the account has to be signed in or added again.

A practical single-user rotation procedure:

1. Remove all accounts in the UI, or accept that they will have to be added again.
2. Stop the backend.
3. Back up the whole `${app.data-dir}`.
4. Delete `crypto.bin` and the DB, or the whole data directory.
5. Start the backend; it creates a new `crypto.bin`, `session.json` and a fresh DB.
6. Add the accounts again.

Changing `MAIL_CRYPTO_KEY`/`MAIL_CRYPTO_SALT` against an existing DB without a re-encrypt migration results in unreadable credentials.

## IMAP/SMTP diagnostics

Order of checks:

1. `/api/internal/health` - DB, disk and the sync component.
2. `accounts.last_error` - the last user-relevant error of the account.
3. `logs/mail.log` - the `IMAP`, `SMTP`, `SYNC`, `AUTH` categories.
4. `logs/audit.log` - `imap_auth`, `mail_send`, `account_requires_reauth`, `decrypt`, `api_key_auth`.

Useful SQL queries:

```sql
SELECT id, email, active, requires_reauth, last_sync_at, last_error
FROM accounts
ORDER BY id;

SELECT account_id, folder_name, last_uid, last_sync_at
FROM folder_sync_states
ORDER BY account_id, folder_name;
```

If `requires_reauth = 1`, this is not a backend outage. The user has to go through the OAuth login again.

## OAuth tokens and system time

Google and Microsoft access tokens are cached in the process (per account, in a
shared in-memory `TokenCache`) and are considered stale 60 seconds before their
nominal expiry. That is how the backend protects IMAP/SMTP XOAUTH2 logins against
a token that would expire right between a cache hit and its use.

Production must run with active system time synchronisation (Windows Time/NTP).
Without NTP a large clock drift can cause needlessly early token refreshes, or a
brief use of an already expired token. The second case is benign: an IMAP/SMTP
auth failure invalidates the cache and the next attempt refreshes the token, but
the log gains needless auth-retry noise.

### Microsoft refresh token revoke

The Microsoft Identity Platform does not implement RFC 7009 token revoke. On
account deletion the backend clears only the local cache and writes the audit
event `token_revoke ... revoke=local_cache_only`; the refresh token on
Microsoft's side stays valid until the original period elapses (typically 90 days
of inactivity) or until the user revokes it explicitly.

If the user wants to revoke access immediately (e.g. after losing the device):

1. Open `https://account.microsoft.com/privacy` (a personal MSA account) or
   `https://myapps.microsoft.com` (an organisational AAD account).
2. In "Apps and services" / "Apps with access", find the application by its
   client ID and click "Remove permission".
3. The backend record no longer exists anyway (deleteAccount removed it), so
   this step is cleanup on the provider's side.

## Sidecar startup failures

Telling them apart:

```text
No session.json and no .ready      the backend did not get through bootstrap
session.json but no .ready         the backend crashed between handshake and the ready signal
.ready present, health silent      port/firewall/wrong baseUrl, or a dead process
health answers, UI does not        a problem in the Tauri client / API client
```

What to look for in the log:

```text
The explicit port is taken         another backend instance or a foreign process
SQLite quick_check failed          suspected DB corruption, restore from a backup
Crypto self-test failed            wrong or changed crypto key/salt
GOOGLE_OAUTH_CLIENT_* missing      missing OAuth configuration for the dev/prod build
MICROSOFT_OAUTH_CLIENT_* missing   missing OAuth configuration for Outlook/Exchange Online
```

When the sidecar crashes, the Tauri client should attempt a limited restart and then show the path to the logs. The backend is restart-idempotent: Flyway is a no-op on a matching schema, the SQLite WAL recovers automatically, and IMAP connections are established again.

## Release smoke

The minimal backend verification before a release:

```powershell
$env:MAVEN_OPTS='-Duser.home=C:\dev\java\mail\backend'
mvn.cmd "-Dmaven.repo.local=C:\dev\java\mail\backend\.m2repo" "-Dapp.data-dir=C:\dev\java\mail\backend\target\test-data" clean verify
```

`clean verify`, not `package`: without `clean`, SpotBugs analyses the
`__BeanDefinitions` classes left in `target/` by an earlier `-Paot package` and
fails on generated code; `package` additionally does not fire the failsafe
integration tests. [RELEASE_CHECKLIST.md](RELEASE_CHECKLIST.md) §1 holds the same
command.

Expectations:

```text
Failures: 0, Errors: 0, Skipped: 0   on both suites — surefire and failsafe
target/mail-backend-0.1.0.jar exists as a repackaged Spring Boot JAR
StartupSmokeTest creates crypto.bin, session.json, .ready and applies Flyway V1
```

The test count is not written here: it rotates faster than the runbook is read,
and no gate recomputes it in this file.

The sidecar artifact for Windows:

```powershell
.\scripts\package-sidecar-windows.ps1
```

> Requires a real OAuth client id in the environment (CI secrets), and fails the
> build otherwise. For a local build use `package-sidecar-dev-windows.ps1` (which
> reads them from `.env`), or add `-AllowPlaceholderOAuth` for a build without a
> working OAuth login.

Output:

```text
target/sidecar/x86_64-pc-windows-msvc/
```

Copy the whole directory with `.exe`, `app/` and `runtime/` into the Tauri bundle, not just the `.exe` itself.
