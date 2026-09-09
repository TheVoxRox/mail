# Release checklist

Checklist for the final verification before releasing the desktop application with the sidecar backend.

This document answers the question "did this candidate pass". The question "how a
release is cut" — version, tag, changelog split, release notes, draft vs.
publish, approval — is answered by [docs/RELEASE_PROCESS.md](../docs/RELEASE_PROCESS.md),
which refers back to this checklist in its steps §1, §6 and §8.

Fill in per candidate:

```text
Release:
Date:
Backend commit:
Frontend commit:
Platform:
Tester:
```

## 0. Version

- [ ] Change the version **only** through `cd frontend ; npm run bump:version <X.Y.Z>`. It lands at once on every file that carries the version — `frontend/package.json`, `frontend/src-tauri/tauri.conf.json`, `frontend/src/lib/version.ts`, `frontend/src-tauri/Cargo.toml` and `backend/pom.xml` — and editing any of them by hand puts the rest out of step. The gate `npm run check:versions` (part of `npm run check`) catches the divergence, but only after it exists; the script will not allow it. The argument is validated against `SEMVER_RE`, so a typo does not get through.

## 1. Backend build

**Run `npm run regen:licenses:all` (Section 8a) before this build, not after it.** `NOTICE.txt` is bundled into the installer as a Tauri resource (`resources/NOTICE.txt` in [tauri.conf.json](../frontend/src-tauri/tauri.conf.json)), so a regen that changes anything once the candidate is built means the shipped package carries a component list that does not match it -- and the build has to be repeated. Section 8a is where that is verified, not where it is first run.

- [x] `mvn -Dmaven.repo.local=.m2repo -Dapp.data-dir=target/test-data clean verify` — one command, not `spotless:check` + `package` separately. `clean` because otherwise SpotBugs analyses the `__BeanDefinitions` left by an earlier `-Paot package` and fails on generated code; `verify` because `package` does not fire the failsafe integration tests. Spotless and SpotBugs both run inside it.
- [x] Verify that the build reports `Failures: 0, Errors: 0, Skipped: 0` on both suites — surefire and failsafe. The test count is not recorded: it rotates faster than the checklist is read, and no gate recomputes it here.
- [x] The artifact `target/mail-backend-0.1.0.jar` was produced.
- [x] The Windows sidecar packaging passed through `scripts/package-sidecar-windows.ps1`.
- [x] The sidecar launcher `app/mail-x86_64-pc-windows-msvc.cfg` carries the baked-in `...google.client-id` (not a `mail-local-*` placeholder). Both the signed workflow and `package-sidecar-dev-windows.ps1` ensure this; a bare `package-sidecar-windows.ps1` without the OAuth env now fails the build unless it runs with `-AllowPlaceholderOAuth`. **Verified 2026-06-26** (`package-sidecar-dev-windows.ps1 -SkipTests`): 3 OAuth values injected, the built-in verification step reports "Google client-id baked into the launcher; no placeholder"; the `.cfg` carries `google.client-id` + `google.client-secret` + `microsoft.client-id`, with no `mail-local-*`.
- [x] The sidecar output contains `mail-x86_64-pc-windows-msvc.exe`, `app/` and `runtime/`. **Verified 2026-06-26:** top-level `mail-x86_64-pc-windows-msvc.exe` (a 495 KB launcher) + `app/` (jar 77.9 MiB + `.cfg` + `.jpackage.xml`) + `runtime/` (a 123 MB bundled JRE).
- [ ] The release candidate runs on a clean Windows profile without a system-installed Java/JDK/JRE. Note: `runtime/` is structurally self-contained — a bundled JRE `JAVA_VERSION=25.0.3` with `bin/jli.dll`, `bin/java.dll`, `bin/server/jvm.dll` (the native launcher loads the JVM through JNI; a standalone `java.exe` is deliberately not bundled by the jpackage app-image). All that remains is a real run on a clean machine.

## 2. Frontend automation

Run in the frontend repo against a dev/preview build with the backend sidecar running, or in mock mode where that is deliberate.

- [x] `npm run generate:api`
- [x] `npm run check:i18n`
- [x] `npm run build`
- [x] `npm run test:e2e`
- [x] `npm run test:functional:stable`
- [x] `npm run test:a11y`

Notes:

```text
2026-05-08: The backend package passed with Tests run: 625, Failures: 0, Errors: 0, Skipped: 0.
2026-05-08: The Windows sidecar packaging passed after a separate backend package through scripts/package-sidecar-windows.ps1 -SkipTests and then npm run sidecar:sync:windows.
2026-05-08: npm run test:e2e passed; the wrapper runs the functional suite and the a11y suite through the stable preview mode.
2026-05-08: npm run test:a11y was unified onto the stable preview runner and passed 42/42.
2026-06-26: `mvn verify` on main (3210e1a) green — surefire 885 + failsafe 28 = 913 tests, 0 fail/error/skip; artifact mail-backend-0.1.0.jar (77.9 MB). Frontend gate (3210e1a) green — check (1368 files, 0 errors), check:i18n (570 keys), check:i18n:backend (82), check:translations:strict, knip, test:unit (357), build, test:functional:stable (116), test:a11y:stable (54). The log-scan gate (§7/§8) was run by hand over the last smoke log: bug D = 0, audit.log 0 CRITICAL; the remaining ERROR/WARN are pre-fix noise from a build before #69/#70/#71 (bugs F/G/E).
2026-06-26: §1.23–24 (sidecar packaging) verified with a fresh `package-sidecar-dev-windows.ps1 -SkipTests` from main (3516bf5, code unchanged since 3210e1a — only docs commits since). BUILD SUCCESS, jpackage app-image into `target/sidecar/x86_64-pc-windows-msvc/`. §1.23: the built-in verify step reports "Google client-id baked into the launcher; no placeholder", the `.cfg` carries the google client-id+secret + the microsoft client-id, 0× `mail-local-`. §1.24: top-level exe + `app/` (jar 77.9 MiB) + `runtime/` (123 MB JRE, Java 25.0.3, jli/java/jvm.dll). §1.25 remains — the runtime is self-contained, but a real run on a clean Windows profile without system Java is a manual step.
```

## 3. Fresh install

- [ ] Install the release candidate through the Windows NSIS installer `voxrox-mail-<version>-windows-x64-setup.exe` onto a clean profile with no existing `%LOCALAPPDATA%\VoxRox\Mail`.
- [ ] Verify the default installation path (per-user, `installMode: currentUser`): binaries in `%LOCALAPPDATA%\Programs\VoxRox\Mail`, separate from the data in `%LOCALAPPDATA%\VoxRox\Mail`.
- [ ] The installation folder `%LOCALAPPDATA%\Programs\VoxRox\Mail` contains the bundled sidecar `mail-x86_64-pc-windows-msvc.exe`, `app/` and `runtime/`.
- [ ] Start the client.
- [ ] The backend sidecar auto-starts.
- [ ] `${app.data-dir}/crypto.bin` is created.
- [ ] After the first run, `%LOCALAPPDATA%\VoxRox\Mail\webview\` (WebView2) and `%LOCALAPPDATA%\VoxRox\Mail\logs\mail-frontend.log` (the Tauri log) exist.
- [ ] NO `org.voxrox.mail*` folder was created in `%LOCALAPPDATA%` — all data is under `VoxRox\Mail`.
- [ ] The release start does not use `MAIL_CRYPTO_KEY`/`MAIL_CRYPTO_SALT` from a local `backend/.env`; the desktop default is `crypto.bin`.
- [ ] A repeated start with an existing `crypto.bin` succeeds even with a stale `crypto.fingerprint`; the internal handshake API key is generated in memory on every start and written to `session.json` (no persistent artifact to rotate), and user credentials remain untouched.
- [ ] `${app.data-dir}/session.json` is created.
- [ ] `session.json` contains the current random loopback port and a `baseUrl` of the form `http://127.0.0.1:<port>/api`.
- [ ] `${app.data-dir}/.ready` is created.
- [ ] The UI does not show an error dialog.
- [ ] `/api/internal/health` returns 200 with the `X-API-KEY` from `session.json`.
- [ ] Measure the start of the release build: `cd frontend ; npm run tauri:smoke:release-startup`. It runs the exe from `src-tauri/target/release/`, waits for the sidecar and drops a report into `frontend/target/tauri-release-startup-<stamp>.json` plus both logs. It refuses to run when a backend is already going (it detects that from `session.json`) — close the app first in that case, otherwise it would measure a foreign process.

## 3a. Installer and update behavior

- [ ] The installation is per-user (`installMode: currentUser`) — the installer shows no mode choice and requires no elevation (UAC). Binaries go to `%LOCALAPPDATA%\Programs\VoxRox\Mail`.
- [ ] The installer language is automatically Czech/English by system; other locales fall back to English.
- [ ] The desktop shortcut is created **always** — the installer deliberately offers no choice for it (decided 2026-09-07, see `todo.md` → Rozhodnuti). The template [nsis-installer.nsi](../frontend/src-tauri/windows/nsis-installer.nsi) calls `CreateOrUpdateDesktopShortcut` unconditionally and has neither a components page nor the `MUI_FINISHPAGE_SHOWREADME` checkbox through which the default Tauri template offers that choice. Only `/NS` (NoShortcutMode) or `/UPDATE` on the command line suppresses it. What is verified is therefore that the shortcut is created and points at `${MAINBINARYNAME}.exe`, not that it can be unticked.
- [ ] The Start menu shortcut is created as `VoxRox\VoxRox Mail`.
- [ ] Reinstalling the same version succeeds without data loss.
- [ ] Downgrading to an older version is blocked.
- [ ] The signed release contains `voxrox-mail-<version>-windows-x64-setup.exe`, `.sig` and `latest.json`.
- [ ] **Signing pair match:** the public key bundled in the running app matches the signing private key (`TAURI_UPDATER_PUBKEY` ↔ `TAURI_SIGNING_PRIVATE_KEY`). **Since 2026-08-27 a build gate watches this**, not just this item: the step "Verify updater signature against the shipped pubkey" in `windows-signed-release.yml` verifies the installer signature against the `pubkey` from the generated release config and fails the build before publishing on a mismatch (`npm run tauri:verify-updater-signature`). The manual verification does **not** end there — the gate proves the pair matches, not that an update actually happens, so the update smoke vN-1 → vN stays mandatory for the rest of the chain (download, install, data survival). **Unfixable after shipping.**
- [ ] **Update smoke vN-1 -> vN on the real channel:** install the previous version, let the running application take the update, then confirm the data survived and the app starts. Until 2026-09-09 this lived only inside the item above and in `todo.md`, so the one step nothing else covers had no line of its own -- the build gate proves the key pair and the audit proves the trust chain, and neither proves that the NSIS installer overwrites `runtime/**` and `app/**` of a _running_ installation without a "file in use" failure. **Unfixable after shipping**: an updater broken in the version people already have cannot be repaired through the updater. **For the first ship there is no vN-1** and the stable channel stays empty until `v0.1.0` is published, so this item cannot be closed the way later releases close it. Three ways to stand, none of them free: accept it and record it in Section 9 (the audience is the closed beta, who would reinstall by hand), prove it through the beta channel with a `v0.1.0-beta.1` prerelease ahead of the stable publish (which orphans the body of the current draft -- the cost measured in `todo.md` -> Rozhodnuti), or prove the overwrite locally against the compile-time `TAURI_UPDATER_BETA_ENDPOINT` in [lib.rs](../frontend/src-tauri/src/lib.rs), which covers the unknown part but not the GitHub plumbing. From `0.2.0` on the item is ordinary.
- [ ] The release build passed through the signed workflow (`windows-signed-release.yml`), which applies `tauri.release.conf.json` (env `TAURI_UPDATER_*`). The base `tauri.conf.json` updater block (endpoint + pubkey) is only a dev reference — a bare `npm run tauri:build` would ship the base values without a `.sig`; do not publish a manually assembled build.
- [ ] Release channels: the tag matches the version in `tauri.conf.json` (the workflow fails otherwise); a prerelease-suffixed tag (`vX.Y.Z-…`) was created as a GitHub **prerelease** (outside the `releases/latest` redirect = outside the stable channel); after Publish, `beta-channel.yml` completed and `releases/download/beta/latest.json` carries the published version. Halt/re-point procedure: [OPERATIONS.md](OPERATIONS.md) "Release channels".

## 4. Account flows

- [ ] Add a PASSWORD account with a predefined provider.
- [ ] Add a PASSWORD account with custom IMAP/SMTP settings.

### Google OAuth (Gmail)

Preconditions before the smoke:

- [ ] `GOOGLE_OAUTH_CLIENT_ID` / `GOOGLE_OAUTH_CLIENT_SECRET` in the release build are the _real production_ values from the Google Cloud Console (a "Desktop app" client), not the `mail-local-*` placeholder. Without them Google rejects the login with `Error 401: invalid_client` / "OAuth client was not found" (root cause 2026-06-17: the production sidecar was packaged without the OAuth env → a launcher `.cfg` with no `...google.client-id`; verify with the item in §1).
- [ ] OAuth consent screen: for v0.1.0 it stays `Testing` and that is a deliberate exception (decided 2026-06-25, a closed beta — see the `todo.md` section Rozhodnuti). The cost is written down in advance, not discovered during the smoke: the refresh token expires after **7 days**, so a tester signs in again weekly, and only an account on the test users list can sign in at all. This item is signed off as "the state matches the decision"; `In production` is a condition only for a public release.
- [ ] The restricted scope `https://mail.google.com/` (`application.properties`) has completed Google verification (the CASA security assessment). Without it external users get an "unverified app" screen / a 100-user limit. **A blocking item for a production consumer release** (the counterpart to the MS "verified publisher").

Smoke flow:

- [ ] Add a Google OAuth account against a real account through the loopback redirect with the current random port. **Blocking — it confirms that Google accepts the baked-in client-id.**
- [ ] Revoke the Google grant on Google's side.
- [ ] The next sync marks the account `requires_reauth=true`.
- [ ] The UI shows a prompt to sign in again.
- [ ] Re-login returns the account to a syncable state.

### Microsoft OAuth (Outlook / Exchange Online)

Preconditions before the smoke:

- [ ] The Azure App Registration `VoxRox Mail` exists under `info@voxrox.org` in the tenant `Default Directory (infovoxrox.onmicrosoft.com)`.
- [ ] The **Application (client) ID** in `backend/.env` (`MICROSOFT_OAUTH_CLIENT_ID`) matches the value in Azure → Overview → "Application (client) ID". CAREFUL: do not use the value from the "Secret ID" column of the client secrets table — that is a Secret ID, not the Application ID (the mix-up was the root cause of `unauthorized_client` on 2026-05-20).
- [ ] **No client secret** — Microsoft is registered as a _public client_ (`client-authentication-method=none`, the flow secured by PKCE). `MICROSOFT_OAUTH_CLIENT_SECRET` is neither sent to the token endpoint nor bundled into the installer; it is unused and may stay empty.
- [ ] **Supported account types** in Azure → Authentication = "Accounts in any organizational directory + personal Microsoft accounts" (multitenant + MSA, matching the `common` tenant in `application.properties`).
- [ ] **Platform** = "Mobile and desktop applications", **Redirect URI** = exactly `http://localhost/login/oauth2/code/microsoft` (no port, no https, `localhost` NOT `127.0.0.1`). CAREFUL: this is a migration from the original Web platform — under the Web platform a public client (with no secret) would end up at `AADSTS7000218` (missing client_secret).
- [ ] Azure → Authentication → **"Allow public client flows" = Yes**.
- [ ] ~~Client secret rotation~~ **does not apply** — a public client uses no secret, so there is nothing to rotate and nothing that expires (previously planned for 2028-04-18).
- [ ] **Verified publisher / Partner One ID** (formerly MPN ID) — **DEFERRED (decided 2026-06-20: no legal entity for now).** NOT blocking for a consumer release: MSA accounts (`@outlook.com`/`@hotmail.com`/`@live.com`) and our own tenant (`@voxrox.org`) get consent without it (they only see an "unverified" notice). The hard block AADSTS700016 (reproduced 2026-05-20 with a foreign enterprise tenant) applies ONLY to foreign organizational Entra tenants (B2B) — that audience is deferred until a verified legal entity exists (MS business verification requires a company verified against official registers). The procedure once/if an entity exists is in the `todo.md` section "Microsoft OAuth".

Smoke flow:

- [ ] Add a Microsoft OAuth account against a real `@outlook.com` account — the wizard offers the Microsoft button after the domain is entered.
- [ ] The Microsoft authorization page **shows an account picker** (a consequence of `prompt=select_account` in `application.properties`), and possibly a consent screen too.
- [ ] After consent, the redirect to `http://localhost:<port>/login/oauth2/code/microsoft` succeeds, the account is created in the DB with the provider `outlook.com` and `requires_reauth=false`.
- [ ] The first IMAP sync comes up over OAuth2 / XOAUTH2 SASL against `outlook.office365.com:993` and downloads the real inbox.
- [ ] An SMTP send over OAuth against a real account delivers a message (this requires the `SMTP.Send` scope in the token — already in `application.properties`).
- [ ] Repeat the smoke with `@hotmail.com` and `@live.com` (both are in the provider seed).
- [ ] Refresh token roundtrip: after the access token expires (3599 s) `MicrosoftTokenService` refreshes it automatically and the sync continues without user action.
- [ ] Revoke the Microsoft grant on Microsoft's side (`https://account.microsoft.com/privacy/app-access`).
- [ ] The next sync marks the account `requires_reauth=true`.
- [ ] The UI shows a prompt to sign in again.
- [ ] Re-login returns the account to a syncable state.

## 5. Mail workflows

The manual smoke covers what the automated suites cannot: a real IMAP/SMTP
server, real folders at the provider, a real recipient and a screen reader.
Anything that runs against the MSW mocks has its own e2e — what stands here is
what has something riding on the real side.

### Sync and reading

- [ ] A full sync of 5000+ messages against a real IMAP account.
- [ ] Open the detail of a message with HTML content.
- [ ] Open a message with an attachment.
- [ ] Open a message with an embedded (cid) image — the image renders.
- [ ] A message with remote images: blocked by default, "Load images" loads them once, "Always from this sender" also on every subsequent open.
- [ ] Conversation grouping: turn it on, expand a thread, open a message from it, turn it off. A thread holds messages across folders — a reply in Sent hangs with the original in Inbox.
- [ ] An FTS5 search finds the expected hit.
- [ ] An FTS5 search returns an empty result for a non-existent query.

### Composing and sending

- [ ] Send a new email.
- [ ] Send an email with a 30 MB attachment.
- [ ] Save a draft.
- [ ] Send a saved draft.
- [ ] Reply.
- [ ] Reply all.
- [ ] Forward a message with an attachment — the attachment reaches the recipient.
- [ ] Markdown in the editor (`**bold**`, `# heading`, `- bullet`): the received message carries the formatting and, at the same time, exactly what was typed.
- [ ] The account signature is inserted into a new message both automatically and through the manual button.
- [ ] The address typeahead offers entries from the address book and from correspondence history.

### Organising mail

- [ ] Move to Trash.
- [ ] Move to a custom folder.
- [ ] Move to the Spam folder and back — verify at the provider, not just in the UI.
- [ ] Toggle `seen`.
- [ ] Toggle `flagged`.
- [ ] Bulk actions over several selected messages: read / unread, star, move, delete.

### Contacts

- [ ] Create a contact, assign a label, find it by searching in contacts.
- [ ] "Add to contacts" from the sender row of an open message.
- [ ] Merge duplicates and bulk delete.
- [ ] Export vCard and import the same file back — no duplicates are created.

### Controls and the application

- [ ] Command palette (Ctrl+K): run a command, and Escape returns focus to where it was opened from.
- [ ] Settings → Appearance → Window close: "Keep running in the notification area" — the close button hides the window, and the notification-area icon brings the app back and offers to quit. The default is Quit the application.
- [ ] Settings → About: switch the update channel between Stable and Beta.

## 6. Sidecar lifecycle

- [ ] Kill the Tauri parent process through Task Manager / `kill -9`.
- [ ] Verify that the sidecar backend does not stay running as an orphaned Java process.
  - _Since 2026-06-30 a parent-death watchdog handles this ([ParentProcessWatchdog.java](src/main/java/org/voxrox/mailbackend/core/lifecycle/ParentProcessWatchdog.java)): the frontend starts the sidecar with `MAIL_SIDECAR_WATCH_PARENT=1`, the backend reads `System.in` and calls `System.exit(0)` when the stdin pipe closes (the frontend dying, force-kill included). This item verifies that it really works on a packaged build — the `mail` JVM should disappear from Task Manager within a few seconds._
  - _Verified 2026-06-30 on a dev-packaged sidecar through tauri:dev: force-kill of `app.exe` → both `mail.exe` (launcher + JVM grandchild) disappeared in ~13 s, 0 orphans (of which ~10 s was the graceful timeout on a hanging SSE, then a clean DB close). To be repeated on a signed release build._
- [ ] Kill only the sidecar backend.
- [ ] The UI shows an understandable error state.
- [ ] The UI offers to restart the sidecar.
- [ ] Restarting the sidecar restores health and ordinary API calls.
- [ ] Reboot the computer in the middle of a sync.
- [ ] The next start goes through SQLite WAL recovery and the application continues.
- [ ] A damaged DB (`mail.db` or the WAL) — the application starts into a readable error state, not a silent crash.
- [ ] Restore from the backup `db/mail.db.backup-pre-v*` (the pre-migration snapshot) — after replacing the DB with the backup the app starts and the data matches the snapshot.
- [ ] Disk full during a sync — the sync fails in a controlled way (a readable error, no DB corruption) and continues once space is freed.

## 7. Diagnostics

- [ ] Check `logs/mail.log` for unexpected `ERROR`s (known transients — see the log-scan gate in §8).
- [ ] Check `logs/audit.log` for unexpected `CRITICAL`s.
- [ ] Generate `/api/internal/diagnostic-dump`.
- [ ] The ZIP contains `summary.json`, `accounts.json`, `folder-sync-states.json`, `message-counts.json`, `runtime.json`.
- [ ] The ZIP contains no full email addresses, OAuth tokens, internal API key or message content.

## 8. Long run

**Gate (proportionate for v0.1.0):** leave the app running for hours up to 24 h with sync enabled + run the log-scan gate and the memory / SQLite DB / WAL / duplicate growth checks below. **A deep JFR + JDK Mission Control analysis (lock contention) is an optional post-release deep-dive** — do it on a real performance complaint or a suspected leak, not as a blocker of the first release.

- [ ] Leave the application running for 24 h with sync enabled.
- [ ] _(Optional, post-release deep-dive)_ Run with JFR: `-XX:StartFlightRecording=duration=24h,filename=soak.jfr,settings=profile` (for the sidecar, add it to `--java-options` in the package script; for a dev run, to `JAVA_TOOL_OPTIONS`).
- [ ] _(Optional, post-release deep-dive)_ Evaluate `soak.jfr` in JDK Mission Control: lock contention (Java Monitor Blocked / Park) on `accountLocks`/`refreshLocks`, exception counts, thread growth (executor leaks).
- [ ] _(Optional, post-release deep-dive)_ Take a thread dump at the end of the run (`jcmd <pid> Thread.print`) — no orphaned/parked threads outside the known pools.
- [ ] Check the memory footprint.
- [ ] Check the growth of the SQLite DB/WAL.
- [ ] Check that the IMAP pool does not wrongly recycle dead connections.
- [ ] Check that repeated syncs do not create duplicate messages.
- [ ] Log-scan gate after every smoke/long run: `Select-String -Path logs\mail.log -Pattern "ERROR|WARN"`, and either explain every hit or open an issue — a silent error path is exactly the class of bug from the 2026-06 review.
- [ ] A passive log-watch for the transient hiccup **D** (`failed to create new store connection`) — wrapped in a bounded retry+backoff since #78, with the transient classified by [TransientMailErrors.java](src/main/java/org/voxrox/mailbackend/feature/mail/service/TransientMailErrors.java). Scan `logs\mail.log` for three signals:
  - **Healthy:** `WARN` "Transient IMAP error during folder sync … reconnecting and retrying" ([MailSyncService.java](src/main/java/org/voxrox/mailbackend/feature/mail/service/MailSyncService.java)) followed by recovery on the next attempt — a couple per day is expected noise, just record the count.
  - **Escalate (should be ~0):** `ERROR` "Folder sync … still failing after N transient-retry attempt(s)" ([MailSyncService.java](src/main/java/org/voxrox/mailbackend/feature/mail/service/MailSyncService.java)) = the retry budget is exhausted → investigate the cause / raise `mail.client.retry.*`.
  - **Investigate the classifier:** `ERROR` "Critical error during folder sync … failed to create new store connection" ([MailSyncService.java](src/main/java/org/voxrox/mailbackend/feature/mail/service/MailSyncService.java)) should no longer appear for a transient cause; if it does, `TransientMailErrors` missed it → extend the classifier.
  - On escalation, record the dimensions: does it cluster after sleep/wake or a network change? which provider/folder? was recovery confirmed on the next cycle?

## 8a. Docs & web sync

- [ ] `npm run check` green — this includes the doc-claims lint (stack versions and stale phrases in README/CONTRIBUTING vs `pom.xml`/`package.json`/`.nvmrc`).
- [ ] The version date on <https://voxrox.org/privacy/> (both CS and EN) matches `PRIVACY.md` / `PRIVACY.en.md` in the repo.
- [ ] The page <https://voxrox.org/support/> matches `SECURITY.md` (supported versions, timelines, vulnerability scope).
- [ ] `frontend/END_USER_README.md` matches the version being released (features, the state of the OAuth providers, the installer name).
- [ ] The third-party inventories and the bundled `NOTICE.txt` match the real dependency tree: from `frontend/` run `npm run regen:licenses:all` and inspect `git diff`. **The regen itself belongs before Section 1** (see the note there): `NOTICE.txt` ships inside the installer, so anything found here invalidates an already-built candidate instead of being fixed in place. **At most a single changed line is acceptable — `Generated:` in `NOTICE.txt`.** That one carries the run date, so it appears only when the previous regen fell on a different day; two regens on the same day leave the tree completely clean, which is also fine. Discard that line. Anything else (a component, a version, a count) means the files have diverged from the tree since the last regen — and `NOTICE.txt` is bundled into the installer, so the user would get a list that does not match what is in the package. Nothing watches this continuously: dependabot does not regenerate, and there is no CI gate for it; this item is the first place it shows.
- [ ] The status badge on <https://voxrox.org> ("Ve vývoji (Beta)" and similar) matches the reality of the release.
- [ ] GitHub repo metadata: description + Website (`https://voxrox.org`) filled in; the Releases page contains a published release (not just a draft).

## 8b. Permanent rules from the release publish onward (review #170)

Timed process bombs from the 2026-07-17 review (issue #170). The first two items are not one-off checks — on the day the first release is published they become permanent rules.

- [ ] **The V1 migration is frozen on publish day.** The pre-release rule "edit `V1__init.sql` in place + reset the dev DB" ends with the first installed release. Every further schema change = a new `V2+` migration. `application.properties` does not disable Flyway validation (default `validate-on-migrate=true`) — an installation with a recorded V1 checksum would, after an update carrying an edited V1, fail on `Migration checksum mismatch` and the sidecar would not start (it surfaces as a mysterious startup crash at the user's end).
  - The rule is watched by the build gate `FlywayBaselineChecksumTest` (which pins the Flyway checksum of V1). **Until the release has shipped, updating the constant `PINNED_V1_CHECKSUM` is a legitimate part of editing the baseline; after publishing, changing that constant is always a bug.** The specific action on publish day: none — the test is already armed, it just must not be re-pinned from then on.
  - If it happens anyway, startup fails with the audit event `db_migration_altered_after_apply` and a message that explicitly says restoring from a backup will not help; the procedure is roll-forward, see OPERATIONS.md "Update troubleshooting".
- [ ] **Every published release = a full signed build.** The stable channel reads `releases/latest/download/latest.json` (`frontend/src-tauri/tauri.conf.json`) and the signed workflow turns on `VITE_ENABLE_AUTO_UPDATE_CHECK=1`, so **anything** published in the repo becomes "latest" and every installation checks it on every start. A release published without `latest.json` + `.sig` (a tag with notes, say) = an update-check error on every start of every installation. Keep notes, partial builds and the like as a **draft** or a **prerelease** (the `releases/latest` redirect skips prereleases — see OPERATIONS.md "Release channels").
- [ ] **Review of temporary pins and exceptions** (on every release, and on every bump of the affected dependency, check the removal conditions):
  - the tomcat override `<tomcat.version>11.0.25</tomcat.version>` in `backend/pom.xml` — remove it once the Spring Boot managed `tomcat.version` is >= 11.0.25 (SB 4.1.1 = 11.0.24). The only remaining backend pin: both jackson boms and log4j2 went away on 2026-08-26 with Boot 4.1.1.
  - The temporary `cargo audit` exceptions are **not in `vuln-scan.yml`** but in [.cargo/audit.toml](../frontend/src-tauri/.cargo/audit.toml); the job `Tauri cargo audit (RustSec)` runs with `working-directory: frontend/src-tauri` and `cargo audit` picks that file up from there by itself — anyone looking for them in the workflow finds nothing. **There is no temporary vulnerability exception in there today:** both quick-xml entries (`RUSTSEC-2026-0194`, `RUSTSEC-2026-0195`) fell away on 2026-09-08 through an upgrade, not through another acceptance — `plist` 1.10.1 pulls quick-xml 0.42.0. The review therefore asks whether a new temporary exception has appeared; the rest of the file is permanently accepted unmaintained/unsound entries with their own justifications.
  - dependabot ignores TypeScript 7.x, because typescript-eslint declares the peer `typescript >=4.8.4 <6.1.0` (see #147). **That ignore is not in `.github/dependabot.yml`** — it is held by dependabot's own state from the comment `@dependabot ignore this major version` on PR [#147](https://github.com/TheVoxRox/mail/pull/147) and can only be cancelled by **reopening that PR**. Anyone looking for it in the configuration finds nothing and concludes none exists; that is why it is written down here.

## 9. Release decision

```text
Blockers:

Known issues:

Approved:
```

---

## Appendix — smoke worksheet v0.1.0

A per-candidate worksheet for the manual smoke (§3–§8 above). §1 (backend build) and §2 (frontend automation) are ticked in the checklist above — this is the interactive part. _(Until 2026-07-20 a separate file `RELEASE_SMOKE_v0.1.0.md`, merged here during the documentation consolidation.)_

**The worksheet is formalised from existing evidence (the 2026-06-23 tauri:dev smoke, the 2026-06-25 Phase B signed smoke on a clean profile, the 2026-06-26 build+log-scan gate).** Completed items carry a date+source; open `[ ]` items are real gaps nobody has covered yet.

**The candidate is the tag, not a commit on `main`.** Evidence recorded against a branch commit goes stale the moment anything merges, which is what happened to the sheet below: §0/§1/§2 were taken on `9c51d9d` and `main` moved past it the same day. A tag is immutable, so once `v<X.Y.Z>` exists nothing that lands afterwards can invalidate a tick and the question does not come back — record the tag in `Candidate ref`, and treat a commit SHA there as provisional, valid only until the tag is cut. One window cannot be closed this way, because the process needs it: §0/§1/§2 run before the version bump and the changelog cut, and those are what gets tagged. Keep that window to minutes rather than days — it is the only stretch in which a merge can cost a re-run, and §1 is the expensive half to repeat (§2 is what CI re-takes on every push anyway). **Deliberately not written here: a rule that a docs-only commit does not invalidate §1.** It cannot be checked by being read, it has already been applied once by hand — the 2026-06-26 note in §1 above, "code unchanged since 3210e1a — only docs commits since" — and a claim nothing recomputes is the kind this repo deletes rather than maintains. If the gate ever has to run against a moving `main`, compute it instead: compare the git object ids of the paths §1 rests on, the way `check:audits` already does for the audits.

### Candidate 2026-09-09 — `9c51d9d`

```text
Release:         VoxRox Mail 0.1.0  (identifier org.voxrox.mail)
Sign-off date:   ____________
Candidate ref:   9c51d9d — provisional; replaced by tag v0.1.0 once cut
Backend commit:  9c51d9d
Frontend commit: 9c51d9d  (the same monorepo)
Platform:        Windows 11 Pro x64
Tester:          Lukáš Lacina
Build origin:    local dress rehearsal 2026-09-09 (unsigned, per-user NSIS); the signed build comes from the tag per RELEASE_PROCESS §3–§4
```

- [x] **§0 Version** — `npm run check:versions` OK, `0.1.0` matches on all five files.
- [x] **§1 Backend build** — `mvn -Dmaven.repo.local=.m2repo -Dapp.data-dir=target/test-data clean verify` BUILD SUCCESS, surefire and failsafe both `Failures: 0, Errors: 0, Skipped: 0`, artifact `target/mail-backend-0.1.0.jar`, Spotless and SpotBugs inside the run. Sidecar packaged with `package-sidecar-dev-windows.ps1 -SkipTests`: the launcher `.cfg` carries all three OAuth values and zero `mail-local-` placeholders, and the output has `mail-x86_64-pc-windows-msvc.exe`, `app/` and `runtime/`. This tick covers the build and the packaging only: the last item of §1 above — a run on a clean Windows profile with no system-installed JDK — is manual, is still open, and does not carry over into it.
- [x] **§2 Frontend automation** — CI green on this commit (build, unit, functional stable, a11y stable, lint, knip, `npm run check`); `npm run check:i18n` was run locally as CI does not run it.
- [ ] **§3–§8** — the manual smoke on the installed candidate. Installer: `frontend/src-tauri/target/release/bundle/nsis/voxrox-mail-0.1.0-windows-x64-setup.exe`.
- [ ] **§9** — release decision.

The sheet below is the record of the **previous** candidate (3516bf5, June). Its `[x]` marks carry a date and a source and do not carry over to this build — it reads as evidence of what once passed, not as a ticked item.

#### Legend

- `[x]` — verified directly (date + source inline)
- `[x] (impl.)` — implicitly covered by a wider smoke (a clean boot / a real sync could not have happened without it), not itemised separately
- `[ ]` — a real gap, still to be verified

```text
Release:         VoxRox Mail 0.1.0  (identifier org.voxrox.mail)
Sign-off date:   ____________
Backend commit:  3516bf5  (monorepo HEAD 2026-06-26 14:44)
Frontend commit: 3516bf5  (the same monorepo)
Platform:        Windows 11 Pro x64
Tester:          Lukáš Lacina
Build origin:    Phase B (2026-06-25) = signed build · a fresh local NSIS 2026-06-26 (HEAD 3516bf5)
```

### Candidate build

- **Signed (authoritative for the release decision):** GitHub `windows-signed-release.yml` → `voxrox-mail-0.1.0-windows-x64-setup.exe`, `.sig`, `latest.json`.
- **Local dress rehearsal (built 2026-06-26 from HEAD 3516bf5):**
  ```powershell
  cd C:\dev\java\mail\backend ; .\package-sidecar-dev-windows.ps1 -SkipTests
  cd C:\dev\java\mail\frontend ; npm run tauri:build:with-sidecar
  # → src-tauri\target\release\bundle\nsis\voxrox-mail-0.1.0-windows-x64-setup.exe
  ```

#### Helper — health / diagnostic-dump (reads the port+key from session.json)

```powershell
$data = "$env:LOCALAPPDATA\VoxRox\Mail"
$s = Get-Content "$data\session.json" -Raw | ConvertFrom-Json
$s | Format-List appVersion, apiVersion, minClientVersion, dbSchemaVersion, port, baseUrl
Invoke-RestMethod "$($s.baseUrl)/internal/health" -Headers @{ 'X-API-KEY' = $s.apiKey }   # → 200 + status
# Invoke-WebRequest "$($s.baseUrl)/internal/diagnostic-dump" -Headers @{ 'X-API-KEY' = $s.apiKey } -OutFile "$env:TEMP\diag-dump.zip"
```

#### Helper — log scan (bug D + the rest)

```powershell
$logs = "$env:LOCALAPPDATA\VoxRox\Mail\logs"
Select-String -Path "$logs\mail.log" -Pattern 'ERROR|WARN' | Measure-Object
Select-String -Path "$logs\mail.log","$logs\mail.log.*" -Pattern 'failed to create new store connection' | Measure-Object  # bug D <=~1/day = noise
Select-String -Path "$logs\audit.log" -Pattern 'CRITICAL'
```

### §3 Fresh install

- [x] Installing `voxrox-mail-0.1.0-windows-x64-setup.exe` onto a clean profile — **2026-06-25 Phase B** (signed build, a clean `%LOCALAPPDATA%\VoxRox\Mail`).
- [ ] The default installation path (per-user, `installMode: currentUser`): binaries in `%LOCALAPPDATA%\Programs\VoxRox\Mail`, data separate in `%LOCALAPPDATA%\VoxRox\Mail`. **To be re-verified on a currentUser build — Phase B tested the older layout.**
- [x] `mail-x86_64-pc-windows-msvc.exe`, `app/`, `runtime/` in the installation folder — the sidecar packaging is **§1.24, verified 2026-06-26**.
- [x] Start the client → the sidecar auto-starts — **2026-06-23 + 2026-06-25**.
- [x] `crypto.bin` is created — **2026-06-23** (a clean profile → `VOXSEC1` + a DPAPI blob, no plaintext).
- [x] (impl.) `webview\` + `logs\mail-frontend.log` — the app was running, and the Tauri webview does not come up without them.
- [x] NO `org.voxrox.mail*` was created in `%LOCALAPPDATA%` — data under `VoxRox\Mail` (project_data_dirs, the Phase B clean profile).
- [x] The release start does NOT use `MAIL_CRYPTO_KEY/SALT` from `.env` — `crypto.bin` per machine, **2026-06-23**.
- [x] A restart with an existing `crypto.bin` succeeds even with a stale fingerprint, credentials untouched — **2026-06-23** (restart → both accounts decrypt without a re-login).
- [x] (impl.) `session.json` with the port + `baseUrl http://127.0.0.1:<port>/api` — a clean boot synced mail, which is impossible without session.json.
- [x] (impl.) `.ready` is created — the FE does not continue past boot without `.ready`.
- [x] The UI shows no error dialog — **2026-06-25** (the smoke after the fixes #65–#71 ran cleanly).
- [x] (impl.) `/api/internal/health` → 200 with `X-API-KEY` — the FE does not start talking to the backend without a health 200.

→ Note: the all-users (`%ProgramFiles%`) mode was removed on 2026-07-07 — the installation is now exclusively per-user (`installMode: currentUser`, see threat model AR-2), and the install path moved to `%LOCALAPPDATA%\Programs\VoxRox\Mail`.

### §3a Installer / update behavior

- [ ] The installation is per-user (`installMode: currentUser`) — no mode choice, no elevation (UAC). Binaries in `%LOCALAPPDATA%\Programs\VoxRox\Mail`.
- [ ] The installer language is automatically CZ/EN by system.
- [ ] Desktop shortcut = an optional checkbox. _(Superseded by §3a above: the decision of 2026-09-07 records that the shortcut is created always and that this checkbox never existed. Kept here as the wording this sheet was filled against.)_
- [ ] Start menu shortcut `VoxRox\VoxRox Mail`.
- [ ] Reinstalling the same version without data loss.
- [ ] Downgrading to an older version blocked.
- [ ] The signed release contains `voxrox-mail-0.1.0-windows-x64-setup.exe`, `.sig`, `latest.json`. _(= the Release&Update / Tauri updater item, signed-only.)_

→ Note: the whole of §3a is un-itemised — mode/language/shortcuts/reinstall/downgrade remain to be verified deliberately.

### §4 Account flows

- [ ] A PASSWORD account with a predefined provider. _(Untested — no password account available.)_
- [ ] A PASSWORD account with custom IMAP/SMTP. _(Untested.)_

#### Google OAuth

Preconditions: [x] real prod `GOOGLE_OAUTH_CLIENT_ID/SECRET` (an org-based client, **2026-06-23**) · **[ ] consent screen `In production` — the project is still `Testing`** (the refresh token expires after 7 days outside the test users) · **[ ] restricted scope `https://mail.google.com/` CASA verification — BLOCKING for a consumer release.**

- [x] Add Google OAuth through the loopback — **2026-06-23 + 2026-06-25** (after fix E #71, with no `auth-failed`).
- [x] The first IMAP sync — **2026-06-23** (a bounded sync without stalling).
- [x] SMTP send over OAuth — **2026-06-23**.
- [ ] Revoke the grant at Google → `requires_reauth=true` → a UI prompt → re-login. _(The cycle was not performed — it belongs to "Produkcni OAuth readiness".)_

#### Microsoft OAuth

Preconditions: [x] the App Registration `VoxRox Mail` under `info@voxrox.org` · [x] a public client with no secret (PKCE), platform "Mobile and desktop", redirect `http://localhost/login/oauth2/code/microsoft`, public-client flows = Yes (**2026-06-10**) · [ ] Verified publisher DEFERRED _(not a blocker for MSA / our own tenant)._

- [x] Add MS OAuth against `@outlook.com` — **2026-06-23** (an MSA login).
- [x] After consent the account is created, provider `outlook.com`, `requires_reauth=false` — **2026-06-23**.
- [x] The first IMAP sync over XOAUTH2 (`outlook.office365.com:993`) — **2026-06-23**.
- [x] SMTP send over OAuth (587/STARTTLS after fix #58) — **2026-06-23**.
- [x] Refresh token roundtrip (auto-refresh after expiry) — **2026-06-23** (including after a restart).
- [ ] Repeat with `@hotmail.com` and `@live.com`. _(Only `@outlook.com` was tested.)_
- [ ] Revoke the MS grant → `requires_reauth=true` → a UI prompt → re-login. _(The cycle was not performed.)_

→ Note: enterprise tenants (B2B) only after a verified publisher — outside the target audience of v0.1.0.

### §5 Mail workflows

- [ ] A full sync of 5000+ messages. _(Sync was verified, but not at this scale.)_
- [x] (impl.) The detail of a message with HTML content — **2026-06-25** (opening the detail was the core of the "ghost message 404" bug).
- [ ] A message with an attachment. _(Untested.)_
- [ ] FTS5 search — hit. _(Untested.)_
- [ ] FTS5 search — empty result. _(Untested.)_
- [x] Send a new email — **2026-06-23** (SMTP send, Google+MS).
- [ ] Send an email with a 30 MB attachment. _(Untested.)_
- [ ] Save a draft → send the saved draft. _(The draft data-loss bug was fixed in the 2026-06-06 audit, but saving/sending a draft through the UI was not itemised in the release smoke.)_
- [ ] Reply → Reply all. _(Untested.)_
- [ ] Move to Trash → Move to a custom folder. _(Untested.)_
- [ ] Toggle `seen` → toggle `flagged`. _(Untested.)_

→ Note: §5 is the largest real gap — only send + detail view are covered; the rest (attachment, scale, FTS, draft, reply, move, flags) is waiting.

### §6 Sidecar lifecycle

- [x] Kill the Tauri parent → the sidecar is NOT left orphaned — **2026-06-23** (BootErrorView Retry: no orphan, no double spawn).
- [x] Kill only the sidecar → a UI error state → it offers a restart — **2026-06-23**.
- [x] Restarting the sidecar restores health + the API (exactly 1 backend) — **2026-06-23**.
- [ ] Reboot the computer in the middle of a sync → SQLite WAL recovery → the application continues. _(Untested.)_

→ Note: kill/orphan/restart is covered; only the reboot-mid-sync WAL recovery is missing.

### §7 Diagnostics

- [x] `logs\mail.log` with no unexpected `ERROR` — **2026-06-26** log-scan gate (bug D = 0).
- [x] `logs\audit.log` with no `CRITICAL` — **2026-06-26** (0 CRITICAL).
- [ ] Generate `/api/internal/diagnostic-dump` (the helper above). _(Untested.)_
- [ ] The ZIP contains `summary.json`, `accounts.json`, `folder-sync-states.json`, `message-counts.json`, `runtime.json`. _(Untested.)_
- [ ] The ZIP does NOT contain full email addresses, OAuth tokens, the internal API key or message content. _(Untested — an important PII check.)_

→ Note: the log/audit scan is green; the diagnostic dump + PII redaction remain.

### §8 Long run (24 h)

- [ ] The whole section NOT PERFORMED — a 24h soak + JFR, evaluated in JMC (lock contention on `accountLocks`/`refreshLocks`, thread leaks, DB/WAL growth, duplicates, a thread dump). A separate all-day run.

### §9 Release decision

```text
Blockers (open):
  - Google consent screen in Testing + CASA verification of the restricted scope (a consumer release blocker)
  - The end-to-end signed release dry-run (.sig / latest.json / updater) was not performed
  - §8 24h soak not performed

Known issues:
  - Bug D: a transient "failed to create new store connection" — a passive watch (<=~1/day noise)

Real gaps in the manual smoke (low/medium risk, see above):
  - §3a installer behaviour (mode/language/shortcuts/reinstall/downgrade)
  - §4 PASSWORD accounts, the Google+MS revoke→re-login cycle, @hotmail/@live
  - §5 attachment / 30 MB / sync 5000+ / FTS / draft / reply / move / flags
  - §6 reboot-mid-sync WAL recovery
  - §7 diagnostic dump + PII check

Approved (date + signature):
```
