# Release checklist

Checklist for the final verification before releasing the desktop application with the sidecar backend.

This document answers the question "did this candidate pass". The question "how a
release is cut" — version, tag, changelog split, release notes, draft vs.
publish, approval — is answered by [docs/RELEASE_PROCESS.md](../docs/RELEASE_PROCESS.md),
which refers back to this checklist wherever one of its steps needs per-candidate evidence.

Fill in per candidate:

```text
Release:
Date:
Backend commit:
Frontend commit:
Platform:
Tester:
```

**The boxes in this document are the template: they stay unticked.** The
per-candidate record is the worksheet in the appendix, which is where a tick
carries a date and a candidate ref. §1 and §2 were the exception until
2026-09-10 — twelve boxes ticked in the body, carrying evidence from two
candidates ago, while the same two sections were also recorded per candidate in
the appendix. A tick with no candidate attached cannot be read by the next
release, so the body no longer holds one. The dated `Notes:` blocks stay: they
carry their own date and read as history, which is what they are.

**Not every release runs every section.** A promotion of a finished beta to stable carries the manual sections from the beta's sheet when the code did not change; [RELEASE_PROCESS.md](../docs/RELEASE_PROCESS.md) "What a release runs" says which sections, and how to check that the shape applies.

## 0. Version

- [ ] Change the version **only** through `cd frontend ; npm run bump:version <X.Y.Z>`. It lands at once on every file that carries the version — `frontend/package.json`, `frontend/src-tauri/tauri.conf.json`, `frontend/src/lib/version.ts`, `frontend/src-tauri/Cargo.toml` and `backend/pom.xml` — and editing any of them by hand puts the rest out of step. The gate `npm run check:versions` (part of `npm run check`) catches the divergence, but only after it exists; the script will not allow it. The argument is validated against `SEMVER_RE`, so a typo does not get through.

## 1. Backend build

**Run `npm run regen:licenses:all` (Section 8a) before this build, not after it.** `NOTICE.txt` is bundled into the installer as a Tauri resource (`resources/NOTICE.txt` in [tauri.conf.json](../frontend/src-tauri/tauri.conf.json)), so a regen that changes anything once the candidate is built means the shipped package carries a component list that does not match it -- and the build has to be repeated. Section 8a is where that is verified, not where it is first run.

- [ ] `mvn -Dmaven.repo.local=.m2repo -Dapp.data-dir=target/test-data clean verify` — one command, not `spotless:check` + `package` separately. `clean` because otherwise SpotBugs analyses the `__BeanDefinitions` left by an earlier `-Paot package` and fails on generated code; `verify` because `package` does not fire the failsafe integration tests. Spotless and SpotBugs both run inside it. From PowerShell, quote the two `-D` arguments (`'-Dmaven.repo.local=.m2repo'`): unquoted, PowerShell splits them at the first dot and Maven stops on the unknown lifecycle phase `.repo.local=.m2repo` before building anything.
- [ ] Verify that the build reports `Failures: 0, Errors: 0, Skipped: 0` on both suites — surefire and failsafe. The test count is not recorded: it rotates faster than the checklist is read, and no gate recomputes it here.
- [ ] The artifact `target/mail-backend-0.1.0.jar` was produced.
- [ ] The Windows sidecar packaging passed through `scripts/package-sidecar-windows.ps1`.
- [ ] The sidecar launcher `app/mail-x86_64-pc-windows-msvc.cfg` carries the baked-in `...google.client-id` (not a `mail-local-*` placeholder). Both the signed workflow and `package-sidecar-dev-windows.ps1` ensure this; a bare `package-sidecar-windows.ps1` without the OAuth env now fails the build unless it runs with `-AllowPlaceholderOAuth`. **Verified 2026-06-26** (`package-sidecar-dev-windows.ps1 -SkipTests`): 3 OAuth values injected, the built-in verification step reports "Google client-id baked into the launcher; no placeholder"; the `.cfg` carries `google.client-id` + `google.client-secret` + `microsoft.client-id`, with no `mail-local-*`.
- [ ] The sidecar output contains `mail-x86_64-pc-windows-msvc.exe`, `app/` and `runtime/`. **Verified 2026-06-26:** top-level `mail-x86_64-pc-windows-msvc.exe` (a 495 KB launcher) + `app/` (jar 77.9 MiB + `.cfg` + `.jpackage.xml`) + `runtime/` (a 123 MB bundled JRE).
- [ ] The release candidate runs on a clean Windows profile without a system-installed Java/JDK/JRE. Note: `runtime/` is structurally self-contained — a bundled JRE `JAVA_VERSION=25.0.3` with `bin/jli.dll`, `bin/java.dll`, `bin/server/jvm.dll` (the native launcher loads the JVM through JNI; a standalone `java.exe` is deliberately not bundled by the jpackage app-image). All that remains is a real run on a clean machine.

## 2. Frontend automation

Run in the frontend repo against a dev/preview build with the backend sidecar running, or in mock mode where that is deliberate.

- [ ] `npm run generate:api`
- [ ] `npm run check:i18n`
- [ ] `npm run build`
- [ ] `npm run test:e2e`
- [ ] `npm run test:functional:stable`
- [ ] `npm run test:a11y`

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

**The machine half runs on a clean runner since 2026-09-13.** Actions → **Release Candidate Smoke** — started on its own after every successful Windows Signed Release, or dispatched from `main` with the tag — downloads the installer attached to the release, checks its SHA-256 and its build provenance, installs it on a clean `windows-latest` profile and runs [installed-app-smoke.mjs](../frontend/scripts/installed-app-smoke.mjs). It covers the silent install and that it records no answer about the startup update check (§3a), the per-user registration and install path, what the installation carries, both shortcuts, a first start to ready with `session.json`, `.ready`, health 200, `crypto.bin`, the WebView2 profile and the Tauri log under `VoxRox\Mail`, no `org.voxrox.mail*` folder, the webview reaching boot phase ready, a second start over the existing `crypto.bin`, reinstalling the same version without touching it (§3a), killing `app.exe` alone and the sidecar following it (§6), and the silent uninstall. A green run is the evidence for those items on a candidate's worksheet — record its URL there rather than repeating them by hand. It cannot see a first start while WebView2 is still being installed (runners have it preinstalled) or the absence of a UAC prompt (runners are administrators), so those stay manual, as does everything from §4 on.

- [ ] Install the release candidate through the Windows NSIS installer `voxrox-mail-<version>-windows-x64-setup.exe` onto a clean profile with no existing `%LOCALAPPDATA%\VoxRox\Mail`.
- [ ] Verify the default installation path (per-user, `installMode: currentUser`): binaries in `%LOCALAPPDATA%\Programs\VoxRox\Mail`, separate from the data in `%LOCALAPPDATA%\VoxRox\Mail`.
- [ ] The installation folder `%LOCALAPPDATA%\Programs\VoxRox\Mail` contains the bundled sidecar launcher `mail.exe`, `app/` and `runtime/`. The target triple in `mail-x86_64-pc-windows-msvc.exe` belongs to the build output only: Tauri strips it when it bundles `externalBin`, which the first Release Candidate Smoke run confirmed on the installed `v0.1.0` candidate.
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
- [ ] **Privacy page** (since 2026-09-14): an interactive install shows the page "Soukromí" / "Privacy" before any file is copied — a summary of what leaves the computer, a link that opens the privacy policy in the browser, and the checkbox "Check for updates when the application starts", ticked on a first install. With a screen reader the page is read and focus lands on the checkbox. Untick it: after the first start Settings → About shows the box unticked and `HKCU\Software\VoxRox\Mail` holds `UpdateStartupCheck` = 0. A reinstall offers the previous answer. The silent case (no value written) is a Release Candidate Smoke check. SignPath Foundation's terms ask for this page — see [CODE_SIGNING_POLICY.md](../CODE_SIGNING_POLICY.md).
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
server, real folders at the provider, a real recipient, the desktop shell and a
screen reader. **What automated tests already prove is listed at the end of
this section instead of being repeated by hand**, each entry naming the test
that proves it, so `check:refs` fails if a named test file disappears. Trimmed
2026-09-13: the hand-run list had grown to cover what tests run on every push,
and the time went into re-proving those rather than into what only a person at
a real account can see. Where the coverage turned out thinner than it looked,
the item stayed here.

### By hand — a real provider, a real recipient, the shell or a screen reader

- [ ] A full sync of 5000+ messages against a real IMAP account.
- [ ] Open a message with an attachment and save the attachment through the desktop save dialog.
- [ ] Send a new email with Markdown in the editor (`**bold**`, `# heading`, `- bullet`) to a real recipient: the received message carries the formatting and, at the same time, exactly what was typed.
- [ ] Send an email with a 30 MB attachment.
- [ ] Save a draft, then send it — the Drafts folder at the provider no longer holds it.
- [ ] Reply all, and forward a message with an attachment — the recipient gets the attachment and sees the reply in the same thread.
- [ ] Move a message to Trash, to a custom folder, and to Spam and back — verify each at the provider, not just in the UI.
- [ ] Search in contacts finds a contact by name and by e-mail. Only a unit test over a mocked repository covers it.
- [ ] Export vCard through the desktop save dialog and import the same file back — no duplicates are created. The e2e suite runs in a browser, without that dialog.
- [ ] Command palette (Ctrl+K) in the shell: it opens, runs a command, and Escape returns focus to where it was opened from — the shortcut reaches the app rather than the webview.
- [ ] Settings → Appearance → Window close: "Keep running in the notification area" — the close button hides the window, and the notification-area icon brings the app back and offers to quit. The default is Quit the application.
- [ ] Settings → About: switch the update channel between Stable and Beta. Only the channel mapping in `updates.ts` has a unit test.
- [ ] A screen-reader pass (NVDA) over the message list, an open message and compose — the rows read as grid cells, opening a message puts the reading cursor in its body, and sending is announced.

### Covered by automated tests — not repeated by hand

- HTML content, declared charsets, and a cid image rendered inline: [MailContentGreenMailIT](src/test/java/org/voxrox/mailbackend/feature/mail/service/MailContentGreenMailIT.java), [sanitizer.functional.e2e.ts](../frontend/src/routes/mail/sanitizer.functional.e2e.ts).
- Remote images blocked by default, loaded once, trusted per sender: [remote-images.functional.e2e.ts](../frontend/src/routes/mail/remote-images.functional.e2e.ts), [RemoteImageAllowlistServiceTest](src/test/java/org/voxrox/mailbackend/feature/mail/service/RemoteImageAllowlistServiceTest.java).
- Conversation grouping, expanding a thread, members from other folders: [grouping.functional.e2e.ts](../frontend/src/routes/mail/grouping.functional.e2e.ts), [ThreadingServiceTest](src/test/java/org/voxrox/mailbackend/feature/mail/service/ThreadingServiceTest.java).
- FTS5 search over a real SQLite index — content stays searchable, a replaced term stops matching: [MessageRepositoryIT](src/test/java/org/voxrox/mailbackend/feature/mail/repository/MessageRepositoryIT.java); the results UI in [search.functional.e2e.ts](../frontend/src/routes/search.functional.e2e.ts).
- Reply and forward prefill, and drafts saved, replaced and reopened over IMAP: [compose.functional.e2e.ts](../frontend/src/routes/compose.functional.e2e.ts), [DraftLifecycleGreenMailIT](src/test/java/org/voxrox/mailbackend/feature/mail/service/DraftLifecycleGreenMailIT.java).
- Markdown rendered into the HTML alternative: [MarkdownBodyRendererTest](src/test/java/org/voxrox/mailbackend/feature/mail/service/MarkdownBodyRendererTest.java).
- The signature, inserted automatically and through the button: [signature.functional.e2e.ts](../frontend/src/routes/signature.functional.e2e.ts).
- Address typeahead from the address book and from correspondence history: [compose.functional.e2e.ts](../frontend/src/routes/compose.functional.e2e.ts), [CorrespondentServiceTest](src/test/java/org/voxrox/mailbackend/feature/contact/service/CorrespondentServiceTest.java).
- `seen` and `flagged` on one message, and flag changes synced over IMAP: [toolbar.functional.e2e.ts](../frontend/src/routes/mail/toolbar.functional.e2e.ts), [MailSyncGreenMailIT](src/test/java/org/voxrox/mailbackend/feature/mail/service/MailSyncGreenMailIT.java).
- Bulk read, move and delete, and permanent deletion in Trash: [toolbar.functional.e2e.ts](../frontend/src/routes/mail/toolbar.functional.e2e.ts), [trash-delete.functional.e2e.ts](../frontend/src/routes/mail/trash-delete.functional.e2e.ts).
- Creating a contact, labels, merge, bulk delete and vCard import: [contacts.functional.e2e.ts](../frontend/src/routes/contacts.functional.e2e.ts).
- "Add to contacts" from the sender row of an open message: [sender-contact.functional.e2e.ts](../frontend/src/routes/mail/sender-contact.functional.e2e.ts).
- Command palette focus return, in the browser build: [palette.functional.e2e.ts](../frontend/src/routes/palette.functional.e2e.ts).

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

**Gate (proportionate for v0.1.0):** run the app with sync enabled across two windows — a first hour that fits in the same sitting as §3–§7, and an overnight stretch that only wall-clock time can buy — and run the log-scan gate over both. **A deep JFR + JDK Mission Control analysis (lock contention) is an optional post-release deep-dive** — do it on a real performance complaint or a suspected leak, not as a blocker of the first release.

**Why it is split, measured 2026-09-10.** The length was never about the application's own timers. Sync runs on a five-minute interval and the UID enumeration hourly, so what those exercise is bound by how many cycles pass, not by how long the clock runs. `reclaim` — the expensive maintenance pass, a WAL checkpoint plus a threshold-gated VACUUM — is scheduled at the same interval as the soak itself (`mail.client.db.reclaim-interval`, 24 h as configured today, after a 30-minute initial delay), so a 24 h run exercises it **exactly once**, at the half-hour mark, and no more than a one-hour run would; its second pass falls past the end of the run. What the long stretch actually buys is the machine's daily cycle — sleep and wake, a network change, a scheduled antivirus pass, an OAuth access token expiring while the app is up. That is why the second window is "overnight" rather than a number, and why the checks that need minutes no longer queue behind the ones that need a night. Before this split the section was one undifferentiated 24 h block, and it had never been performed once.

### 8.1 First hour — the same sitting as §3–§7

- [ ] Start the application with sync enabled and leave it running.
- [ ] Check that the IMAP pool does not wrongly recycle dead connections.
- [ ] An OAuth access token expires and is refreshed while the application keeps running. §4 covers the revoke → re-login cycle and the refresh across a restart; neither exercises an expiry under load.
- [ ] The first `reclaim` pass (half an hour in) completes without stalling the UI or leaving the database locked — the only time this pass runs, whatever the length of the soak.
- [ ] Log-scan gate after every smoke/long run: `Select-String -Path logs\mail.log -Pattern "ERROR|WARN"`, and either explain every hit or open an issue — a silent error path is exactly the class of bug from the 2026-06 review.

### 8.2 Overnight — what only wall-clock time buys

**Decided 2026-09-13: this is no longer a manual gate before publishing, and automation replaces it.** A manual overnight run before every publish is unusual, and this one had never once been performed: it held the publish back by a night for what the paragraph above says the length buys — the machine's daily cycle, not the application's timers. What replaces it is not built yet: a deterministic test for each thing the night was meant to cover (a suspend and resume of the process, a network loss, an OAuth access token expiring under load), and a scheduled accelerated soak against GreenMail with shortened intervals that asserts memory, WAL growth, duplicates and the log signals listed below. The daily cycle on real machines is then observed on the beta, through the diagnostic dumps testers send. **For v0.1.0 this section does not run and goes into §9 as an accepted risk**: the audience is the closed beta, and a defect found after publishing is repaired through the updater. The items below stay as the list that automation has to cover.

- [ ] The application is still running in the morning, having crossed at least one sleep/wake of the machine.
- [ ] Check the memory footprint.
- [ ] Check the growth of the SQLite DB/WAL.
- [ ] Check that repeated syncs do not create duplicate messages.
- [ ] A passive log-watch for the transient hiccup **D** (`failed to create new store connection`) — wrapped in a bounded retry+backoff since #78, with the transient classified by [TransientMailErrors.java](src/main/java/org/voxrox/mailbackend/feature/mail/service/TransientMailErrors.java). Scan `logs\mail.log` for three signals:
  - **Healthy:** `WARN` "Transient IMAP error during folder sync … reconnecting and retrying" ([MailSyncService.java](src/main/java/org/voxrox/mailbackend/feature/mail/service/MailSyncService.java)) followed by recovery on the next attempt — a couple per day is expected noise, just record the count.
  - **Escalate (should be ~0):** `ERROR` "Folder sync … still failing after N transient-retry attempt(s)" ([MailSyncService.java](src/main/java/org/voxrox/mailbackend/feature/mail/service/MailSyncService.java)) = the retry budget is exhausted → investigate the cause / raise `mail.client.retry.*`.
  - **Investigate the classifier:** `ERROR` "Critical error during folder sync … failed to create new store connection" ([MailSyncService.java](src/main/java/org/voxrox/mailbackend/feature/mail/service/MailSyncService.java)) should no longer appear for a transient cause; if it does, `TransientMailErrors` missed it → extend the classifier.
  - On escalation, record the dimensions: does it cluster after sleep/wake or a network change? which provider/folder? was recovery confirmed on the next cycle?

### Post-release deep-dive (optional, not a release blocker)

- [ ] Run with JFR: `-XX:StartFlightRecording=duration=24h,filename=soak.jfr,settings=profile` (for the sidecar, add it to `--java-options` in the package script; for a dev run, to `JAVA_TOOL_OPTIONS`).
- [ ] Evaluate `soak.jfr` in JDK Mission Control: lock contention (Java Monitor Blocked / Park) on `accountLocks`/`refreshLocks`, exception counts, thread growth (executor leaks).
- [ ] Take a thread dump at the end of the run (`jcmd <pid> Thread.print`) — no orphaned/parked threads outside the known pools.

## 8a. Docs & web sync

- [ ] `npm run check` green — this includes the doc-claims lint (stack versions and stale phrases in README/CONTRIBUTING vs `pom.xml`/`package.json`/`.nvmrc`).
- [ ] The version date on <https://voxrox.org/privacy/> (both CS and EN) matches `PRIVACY.md` / `PRIVACY.en.md` in the repo.
- [ ] The page <https://voxrox.org/support/> matches `SECURITY.md` (supported versions, timelines, vulnerability scope).
- [ ] `frontend/END_USER_README.md` matches the version being released (features, the state of the OAuth providers, the installer name).
- [ ] The third-party inventories and the bundled `NOTICE.txt` match the real dependency tree: from `frontend/` run `npm run regen:licenses:all` and inspect `git diff`. **The regen itself belongs before Section 1** (see the note there): `NOTICE.txt` ships inside the installer, so anything found here invalidates an already-built candidate instead of being fixed in place. **At most a single changed line is acceptable — `Generated:` in `NOTICE.txt`.** That one carries the run date, so it appears only when the previous regen fell on a different day; two regens on the same day leave the tree completely clean, which is also fine. Discard that line. Anything else (a component, a version, a count) means the files have diverged from the tree since the last regen — and `NOTICE.txt` is bundled into the installer, so the user would get a list that does not match what is in the package. Nothing watches this continuously: dependabot does not regenerate, and there is no CI gate for it; this item is the first place it shows. **The comparison holds only against a regen taken on the same platform.** The npm half comes from `license-checker --production` in [regen-third-party-licenses.mjs](../frontend/scripts/regen-third-party-licenses.mjs), which walks the installed `node_modules` rather than the lockfile, so a package npm declines to install here is simply absent from the file. Measured 2026-09-10: five packages under the optional, platform-gated `*-wasm32-wasi` bindings (`@emnapi/core`, `@emnapi/runtime`, `@emnapi/wasi-threads`, `@napi-rs/wasm-runtime`, `@tybys/wasm-util`) left the inventory while `package-lock.json` did not change at all. Regenerate on the platform the release is built on -- for this repo the Windows runner -- and read a difference of that shape as the platform rather than as drift.
- [ ] The status badge on <https://voxrox.org> ("Ve vývoji (Beta)" and similar) matches the reality of the release.
- [ ] GitHub repo metadata: description + Website (`https://voxrox.org`) filled in; the Releases page contains a published release (not just a draft).

## 8b. Permanent rules from the release publish onward (review #170)

Timed process bombs from the 2026-07-17 review (issue #170). The first two items are not one-off checks — on the day the first release is published they become permanent rules.

- [ ] **The V1 migration is frozen on publish day.** The pre-release rule "edit `V1__init.sql` in place + reset the dev DB" ends with the first installed release. Every further schema change = a new `V2+` migration. `application.properties` does not disable Flyway validation (default `validate-on-migrate=true`) — an installation with a recorded V1 checksum would, after an update carrying an edited V1, fail on `Migration checksum mismatch` and the sidecar would not start (it surfaces as a mysterious startup crash at the user's end).
  - The rule is watched by the build gate `FlywayBaselineChecksumTest` (which pins the Flyway checksum of V1). **Until the release has shipped, updating the constant `PINNED_V1_CHECKSUM` is a legitimate part of editing the baseline; after publishing, changing that constant is always a bug.** The specific action on publish day: none — the test is already armed, it just must not be re-pinned from then on.
  - If it happens anyway, startup fails with the audit event `db_migration_altered_after_apply` and a message that explicitly says restoring from a backup will not help; the procedure is roll-forward, see OPERATIONS.md "Update troubleshooting".
- [ ] **Every published release = a full signed build.** The stable channel reads `releases/latest/download/latest.json` (`frontend/src-tauri/tauri.conf.json`) and the signed workflow turns on `VITE_ENABLE_AUTO_UPDATE_CHECK=1`, so **anything** published in the repo becomes "latest" and every installation that has not turned the startup check off in Settings → About checks it on every start. A release published without `latest.json` + `.sig` (a tag with notes, say) = an update-check error on every start of every installation. Keep notes, partial builds and the like as a **draft** or a **prerelease** (the `releases/latest` redirect skips prereleases — see OPERATIONS.md "Release channels").
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

**The candidate is the tag, not a commit on `main`.** Evidence recorded against a branch commit goes stale the moment anything merges, which is what happened to the sheet below: §0/§1/§2 were taken on `9c51d9d` and `main` moved past it the same day. A tag is immutable, so once `v<X.Y.Z>` exists nothing that lands afterwards can invalidate a tick and the question does not come back — record the tag in `Candidate ref`, and treat a commit SHA there as provisional, valid only until the tag is cut. One window cannot be closed this way, because the process needs it: §0/§1/§2 run before the version bump and the changelog cut, and those are what gets tagged. Keep that window to minutes rather than days — it is the only stretch in which a merge can cost a re-run, and §1 is the expensive half to repeat (§2 is what CI re-takes on every push anyway). **Deliberately not written here: a rule that a docs-only commit does not invalidate §1.** It cannot be checked by being read, it has already been applied once by hand — the 2026-06-26 note in §1 above, "code unchanged since 3210e1a — only docs commits since" — and a claim nothing recomputes is the kind this repo deletes rather than maintains. If the gate ever has to run against a moving `main`, compute it instead: compare the git object ids of the paths §1 rests on, the way `check:audits` already does for the audits. `npm run release:status` (from `frontend/`) does that for the newest sheet on `main`, against both `main` and the tag; once the tag, the signed build and the draft are in order, it names the first section still open.

### Candidate 2026-09-15 — see the object ids below

```text
Release:         VoxRox Mail 0.1.0  (identifier org.voxrox.mail)
Sign-off date:   ____________
Candidate ref:   the commit this sheet lands in — provisional; replaced by tag v0.1.0 once re-cut
Backend commit:  same
Frontend commit: same  (the same monorepo)
Platform:        Windows 11 Pro x64, machine lacina-hp-650
Tester:          machine half automated; §3–§9 are the maintainer's
Build origin:    local and unsigned — the signed build comes from the re-cut tag, per RELEASE_PROCESS steps 3–4
```

**The tag is re-cut a third time, for three fixes the 2026-09-14 build does not have.** #490: the tray menu items "Synchronizovat" and "Nová zpráva" did nothing and the update dialog's progress bar never moved, because the capability set never granted `core:event:allow-listen`; a screen reader got no percentage announcements during the download. #491: `rustls` 0.23.38, which the updater uses for HTTPS, is under RUSTSEC-2026-0285. #492: a session file watcher that had never run is removed together with its two permissions. Nothing manual had been taken on the 2026-09-14 build — §3–§9 of that sheet were all open — so the draft and the tag are deleted and cut again from the commit this sheet lands in. The tree, on the seven paths §1 and §2 rest on:

| path                         | object id      |
| ---------------------------- | -------------- |
| `backend/src`                | `6eb6fc4973c5` |
| `backend/pom.xml`            | `dbef3f26bcc3` |
| `backend/scripts`            | `12190c2d9a67` |
| `frontend/src`               | `0d00dcc6ba69` |
| `frontend/src-tauri`         | `551639e85c3a` |
| `frontend/package.json`      | `b846b2790345` |
| `frontend/package-lock.json` | `fe149c9e846a` |

Recompute them before ticking anything below; `npm run release:status` does it against `main` and against the tag.

- [x] **§0 Version** — `npm run check:versions` OK, `0.1.0` on all five files. No bump needed.
- [x] **§1 Backend build** — **carried over from the 2026-09-14 sheet**: `backend/src`, `backend/pom.xml` and `backend/scripts` carry the same object ids, so the green `clean verify` and the sidecar packaging recorded there are of this tree. `regen:licenses:all` ran first, as the note at the top of §1 requires, and moved versions but no component: `rustls` 0.23.45 and `rustls-webpki` 0.103.15 from #491, and the npm bumps from #482 and #483 that reach the runtime inventory (`bits-ui`, `postcss`, `vite`, `rolldown` and type packages). No component was added or removed and no licence changed; the backend inventory did not move. Because versions moved, `NOTICE.txt` and both changed inventories are committed with this sheet, which is also why `frontend/src-tauri` differs from `main`.
- [x] **§2 Frontend automation** — **re-taken**, because `frontend/src` moved in #490 and #492 and `frontend/src-tauri` in #490, #491 and #492, and after the licence regeneration so it ran on the tree this sheet records. `generate:api` left the generated client unchanged, `check:i18n` and `build` exited 0, `test:e2e` exited 0, and `test:functional:stable` and `test:a11y` run on their own passed with functional 277 and a11y 65, the counts of the 2026-09-14 sheet; no flakes reported. Port 4173 was checked free before each Playwright run, so every run started its own preview instead of recycling a stale one.
- [ ] **§3 Fresh install** — on the signed build from the re-cut tag. Release Candidate Smoke starts on its own after that build; its URL goes here as the evidence for the items the note at the top of §3 lists. Before the manual part, run the WebView2 install-date check the `a2caa27` sheet describes, so the no-window finding gets its evidence this time.
- [ ] **§3a Installer behaviour** — including the **privacy page** item, taken with a screen reader, and reading `latest.json` from the draft by hand. Still not covered by anything: reinstalling over an existing installation, and the downgrade block.
- [ ] **§4 Account flows** — the blocking item of the gate: it is what proves the production `client-id` is baked into the launcher `.cfg`.
- [ ] **§5 Mail workflows** — the trash case stays a named item: a folder holding two IMAP copies of one Message-ID must persist both and report a non-null `lastSyncAt`. New for this candidate: the tray menu items "Synchronizovat" and "Nová zpráva" each do what they say (#490).
- [ ] **§6 Sidecar lifecycle** — on the new binary; the session handshake now polls only (#492), which it already did in every earlier build.
- [ ] **§7 Diagnostics** — including the dump's `processStartedAt`, which measures from JVM start since #469; the privacy half is checked by reading the dump, not by producing it.
- [ ] **§8 Long run** — §8.1 open. §8.2 does not run for this candidate and goes into §9 as an accepted risk, per the 2026-09-13 decision at the top of §8.2.
- [ ] **§9** — release decision.

**Why the 2026-09-14 sheet below is superseded.** `frontend/src` moved in #490 and #492 and `frontend/src-tauri` in #490, #491 and #492, so its §2 no longer describes the tree, and its §3 evidence would be a smoke of the installer built from `298e2e8`, which the re-cut tag replaces. Its §1 still holds and is carried over above.

### Candidate 2026-09-14 — see the object ids below

```text
Release:         VoxRox Mail 0.1.0  (identifier org.voxrox.mail)
Sign-off date:   ____________
Candidate ref:   the commit this sheet lands in — provisional; replaced by tag v0.1.0 once re-cut
Backend commit:  same
Frontend commit: same  (the same monorepo)
Platform:        Windows 11 Pro x64, machine lacina-hp-650
Tester:          machine half automated; §3–§9 are the maintainer's
Build origin:    local and unsigned — the signed build comes from the re-cut tag, per RELEASE_PROCESS steps 3–4
```

**The tag is re-cut so that the release carries what the published privacy policy and SignPath Foundation's terms describe.** `v0.1.0` at `2ea2a98` predates #481 and #485: its build has neither the setting that turns the startup update check off nor the installer's privacy page, while the privacy policy on voxrox.org already describes both, and SignPath's condition that a project be "released in the form that should be signed" would point at that build. Nothing manual had been taken on it — §3–§9 of the 2026-09-13 sheet were all open — so the draft and the tag are deleted and cut again from the commit this sheet lands in. The tree, on the seven paths §1 and §2 rest on:

| path                         | object id      |
| ---------------------------- | -------------- |
| `backend/src`                | `6eb6fc4973c5` |
| `backend/pom.xml`            | `dbef3f26bcc3` |
| `backend/scripts`            | `12190c2d9a67` |
| `frontend/src`               | `0baf2b092283` |
| `frontend/src-tauri`         | `6f67c0a6dcc3` |
| `frontend/package.json`      | `cc8ff9b8c027` |
| `frontend/package-lock.json` | `ae0d216cfd15` |

Recompute them before ticking anything below; `npm run release:status` does it against `main` and against the tag.

- [x] **§0 Version** — `npm run check:versions` OK, `0.1.0` on all five files. No bump needed; they already carried it.
- [x] **§1 Backend build** — **re-taken**, because `backend/scripts` moved from `ccfeee6ca824` to `12190c2d9a67` in #481, which builds the launcher under the product name and renames it back; `backend/src` and `backend/pom.xml` are unchanged. `clean verify` BUILD SUCCESS in 4:07, surefire and failsafe both `Failures: 0, Errors: 0, Skipped: 0`, read from the reactor summary and from `failsafe-summary.xml` (no errors, failures, skips or flakes), and `target/mail-backend-0.1.0.jar` produced. The sidecar was packaged right after through `backend/package-sidecar-dev-windows.ps1 -SkipTests`: the launcher `.cfg` carries `google.client-id`, `google.client-secret` and `microsoft.client-id` and no `mail-local-` placeholder, the output holds the launcher, `app/` and `runtime/`, the launcher's version resource reads `VoxRox Mail 0.1.0` by `VoxRox`, and the launcher is newer than the jar it wraps. `tauri:smoke:sidecar` on that binary passed — CORS, the boot endpoints, an account round trip and Czech number formatting. `regen:licenses:all` ran before the build and moved no component: its whole output was a new `Generated:` date in `NOTICE.txt` and three `windows-*` crates swapped in order in the Tauri inventory, so nothing was committed. **Still open in §1**, as on every sheet: the clean-profile run without a system-installed JDK.
- [x] **§2 Frontend automation** — **re-taken**, because `frontend/src` and `frontend/src-tauri` moved in #481 and #485 — the setting that turns the startup update check off, the registry bridge and the installer's privacy page. Taken after §1 was green, since `generate:api` reads the backend's OpenAPI snapshot. `generate:api` left the generated client unchanged, `check:i18n` and `build` exited 0, `test:e2e` passed with functional 277 and a11y 65, and `test:functional:stable` and `test:a11y` run on their own passed with the same counts; no flakes reported. Port 4173 was checked free before each Playwright run, so every run started its own preview instead of recycling a stale one.
- [ ] **§3 Fresh install** — on the signed build from the re-cut tag. Release Candidate Smoke starts on its own after that build; its URL goes here as the evidence for the items the note at the top of §3 lists, which now include that a silent install records no answer about the startup update check. Before the manual part, run the WebView2 install-date check the `a2caa27` sheet describes, so the no-window finding gets its evidence this time.
- [ ] **§3a Installer behaviour** — including the new **privacy page** item, taken with a screen reader, and reading `latest.json` from the draft by hand. Still not covered by anything: reinstalling over an existing installation, and the downgrade block.
- [ ] **§4 Account flows** — the blocking item of the gate: it is what proves the production `client-id` is baked into the launcher `.cfg`.
- [ ] **§5 Mail workflows** — the trash case stays a named item: a folder holding two IMAP copies of one Message-ID must persist both and report a non-null `lastSyncAt`.
- [ ] **§6 Sidecar lifecycle** — on the new binary, whose launcher is now renamed after packaging (#481).
- [ ] **§7 Diagnostics** — including the dump's `processStartedAt`, which measures from JVM start since #469; the privacy half is checked by reading the dump, not by producing it.
- [ ] **§8 Long run** — §8.1 open. §8.2 does not run for this candidate and goes into §9 as an accepted risk, per the 2026-09-13 decision at the top of §8.2.
- [ ] **§9** — release decision.

**Why the 2026-09-13 sheet below is superseded.** `backend/scripts` moved in #481 and `frontend/src` and `frontend/src-tauri` in #481 and #485, so neither its §1 nor its §2 describes the tree, and its §3 evidence is a smoke of the installer built from `2ea2a98`, which the re-cut tag replaces.

### Candidate 2026-09-13 — see the object ids below

```text
Release:         VoxRox Mail 0.1.0  (identifier org.voxrox.mail)
Sign-off date:   ____________
Candidate ref:   the commit this sheet lands in — provisional; replaced by tag v0.1.0 once cut
Backend commit:  same
Frontend commit: same  (the same monorepo)
Platform:        Windows 11 Pro x64, machine STOLAK
Tester:          machine half automated; §3–§9 are the maintainer's
Build origin:    local and unsigned — the signed build comes from the tag, per RELEASE_PROCESS steps 3–4
```

**The first sheet after the release was restarted on 2026-09-12.** The draft and the tag `v0.1.0` were deleted: the draft's assets came from `db11430`, whose sidecar carries the trash defect #467 fixes, so nothing on GitHub describes this candidate any more and the signed build starts from nothing. The sheet names object ids for the reason the one below gives — it lands in the same commit as the changelog cut, so the SHA it would name does not exist while it is being written. The tree, on the seven paths §1 and §2 rest on:

| path                         | object id      |
| ---------------------------- | -------------- |
| `backend/src`                | `6eb6fc4973c5` |
| `backend/pom.xml`            | `dbef3f26bcc3` |
| `backend/scripts`            | `ccfeee6ca824` |
| `frontend/src`               | `9b3a3ef9972c` |
| `frontend/src-tauri`         | `47e446b51110` |
| `frontend/package.json`      | `cc8ff9b8c027` |
| `frontend/package-lock.json` | `ae0d216cfd15` |

Recompute them before ticking anything below; `npm run release:status` does it against `main` and against the tag.

- [x] **§0 Version** — `npm run check:versions` OK, `0.1.0` on all five files. No bump needed; they already carried it.
- [x] **§1 Backend build** — **carried forward from the 2026-09-11 sheet, computed rather than assumed.** `backend/src`, `backend/pom.xml` and `backend/scripts` carry the object ids that sheet's run was taken on, so its `clean verify`, the sidecar packaging and `tauri:smoke:sidecar` on that exact binary describe this tree too. The licence inventories carry the same way: the last `regen:licenses:all` (#457, 2026-09-10) ran before that build, and nothing it reads has moved since — `backend/pom.xml`, `frontend/package-lock.json` and `frontend/src-tauri` (which holds `Cargo.lock` and the bundled `NOTICE.txt`) are unchanged, and `frontend/package.json` moved by one `scripts` entry, not a dependency. **Still open in §1**, as on every sheet: the clean-profile run without a system-installed JDK. The `a2caa27` sheet closed it on a second machine, but on the build of `db11430`, so it does not carry.
- [x] **§2 Frontend automation** — **re-taken**, because `frontend/package.json` moved from `f2a33dc2009c` to `cc8ff9b8c027` when #472 added the `release:status` entry. That a `scripts` line cannot change the build is a judgement, and the appendix accepts the computed form or a re-run, not a judgement — so a re-run. `generate:api`, `check:i18n`, `build`, `test:e2e`, `test:functional:stable` and `test:a11y` all exited 0: functional 277 passed, a11y 65 passed, no flakes reported, and `generate:api` left the tree unchanged. It carries into this sheet only because §1 does, since it reads the backend's OpenAPI snapshot. Port 4173 was checked free beforehand, so each run started its own preview instead of recycling a stale one.
- [ ] **§3 Fresh install** — on the signed build from the new tag. **Machine half green 2026-09-13:** Release Candidate Smoke from `main` against the `v0.1.0` draft passed all 21 checks, SHA-256 and build provenance included — [run 34763977364](https://github.com/TheVoxRox/mail/actions/runs/34763977364). That run is the evidence for the items the note at the top of §3 lists, the §3a reinstall and the §6 parent kill among them; the box stays open for what it cannot see. Before the manual part, run the WebView2 install-date check the `a2caa27` sheet describes, so the no-window finding gets its evidence this time.
- [ ] **§3a Installer behaviour** — including reading `latest.json` from the draft by hand. Still not covered by anything: reinstalling over an existing installation, and the downgrade block.
- [ ] **§4 Account flows** — the blocking item of the gate: it is what proves the production `client-id` is baked into the launcher `.cfg`.
- [ ] **§5 Mail workflows** — the trash case stays a named item: a folder holding two IMAP copies of one Message-ID must persist both and report a non-null `lastSyncAt`.
- [ ] **§6 Sidecar lifecycle** — on the new binary.
- [ ] **§7 Diagnostics** — including the dump's `processStartedAt`, which measures from JVM start since #469; the privacy half is checked by reading the dump, not by producing it.
- [ ] **§8 Long run** — §8.1 open. §8.2 does not run for this candidate and goes into §9 as an accepted risk, per the 2026-09-13 decision at the top of §8.2.
- [ ] **§9** — release decision.

**Why the 2026-09-11 sheet below is superseded.** `frontend/package.json` moved in #472, so its §2 — itself carried from `a2caa27` by object id — stopped describing the tree. Its §1 still does, which is why it carries above. Its §3–§8 were never taken: the release was restarted before a build of that candidate existed.

### Candidate 2026-09-11 — see the object ids below

```text
Release:         VoxRox Mail 0.1.0  (identifier org.voxrox.mail)
Sign-off date:   ____________
Candidate ref:   the commit this sheet lands in — provisional; replaced by tag v0.1.0 once cut
Backend commit:  same
Frontend commit: same  (the same monorepo)
Platform:        Windows 11 Pro x64, machine lacina-hp-650
Tester:          machine half automated; §3–§9 are the maintainer's
Build origin:    local and unsigned — the signed build comes from the tag, per RELEASE_PROCESS steps 3–4
```

**This sheet names object ids rather than a SHA, and does so deliberately.** It is cut in the same commit that moves `backend/src`, so the SHA it would name does not exist while it is being written — and would stop existing at the squash-merge in any case, which is the reason `check:audits` compares object ids instead of commit ranges. The tree this sheet describes, on the seven paths §1 and §2 rest on:

| path                         | object id      |
| ---------------------------- | -------------- |
| `backend/src`                | `6eb6fc4973c5` |
| `backend/pom.xml`            | `dbef3f26bcc3` |
| `backend/scripts`            | `ccfeee6ca824` |
| `frontend/src`               | `9b3a3ef9972c` |
| `frontend/src-tauri`         | `47e446b51110` |
| `frontend/package.json`      | `f2a33dc2009c` |
| `frontend/package-lock.json` | `ae0d216cfd15` |

Recompute them before ticking anything below; a tick against a different `backend/src` is the failure this table exists to prevent.

- [x] **§0 Version** — `npm run check:versions` OK, `0.1.0` on all five files. No bump needed; they already carried it.
- [x] **§1 Backend build** — `mvn -Dmaven.repo.local=.m2repo -Dapp.data-dir=target/test-data clean verify` BUILD SUCCESS in 3:51, artifact `target/mail-backend-0.1.0.jar` produced. Both suites clean, **read from the reports rather than from the console**: `target/failsafe-reports/failsafe-summary.xml` gives `completed=88 errors=0 failures=0 skipped=0 flakes=0`, and all 115 surefire test-suite XMLs carry `failures="0" errors="0" skipped="0"`. The per-class `.txt` reports are not a source for this — they omit nested classes and sum to roughly half the real count. **The console is not a source either**, which cost a run here: the first attempt reported a zero exit through the tool that launched it while the log said `BUILD FAILURE` on a `spotless:check` import ordering, so the result above is read from the log and the XMLs and not from an exit code. Sidecar packaged through `package-sidecar-dev-windows.ps1 -SkipTests` (the dev wrapper, the tests having just run on this same tree): the output carries `mail-x86_64-pc-windows-msvc.exe`, `app/` and `runtime/`, the launcher is newer than the jar it wraps, `app/mail-x86_64-pc-windows-msvc.cfg` carries the production `google.client-id` with no `mail-local-*` placeholder, and jdeps needed 14 modules over 108 jars with all of them declared in `--add-modules`. `npm run sidecar:sync:windows` then placed it under `frontend/src-tauri/binaries` with both cfg names present, and `npm run tauri:smoke:sidecar` passed on that exact binary: webview origin 200 with the origin echoed, foreign origin 403, `/v1/client-config` and `/v1/accounts` 200 through the webview origin, an account created and mapped, and `jdk.localedata` live in the packaged runtime. **Still open in §1**, as on every sheet: the clean-profile run without a system-installed JDK — a physical test, not a command.
- [x] **§2 Frontend automation** — **carried forward from `a2caa27`, computed rather than assumed.** All four paths §2 rests on — `frontend/src`, `frontend/src-tauri`, `frontend/package.json`, `frontend/package-lock.json` — carry object ids identical to that sheet's, so the run recorded there (functional 277, a11y 65, no flakes) describes this tree too. This is the computed form the appendix asks for, not the docs-only rule it refuses. **One dependency is not covered by those four ids:** `generate:api` derives the client from the backend's OpenAPI snapshot, so a backend change that moved a controller or a DTO would invalidate it — §1's `OpenApiSnapshot` golden is what proves this one did not, and §2 does not carry until §1 is green.
- [ ] **§3 Fresh install** — **re-take required.** The evidence on the previous sheet is against the signed build of `db11430f`, which predates #467, #468 and the change in this commit. A new tag produces a different binary.
- [ ] **§3a Installer behaviour** — same; nothing in the installer changed, but the artifact it installs did. Still not covered by anything: reinstalling over an existing installation, and the downgrade block.
- [ ] **§4 Account flows** — re-take on the new signed build. Still the blocking item of the gate: it is what proves the production `client-id` is baked into the launcher `.cfg`.
- [ ] **§5 Mail workflows** — re-take, and **the trash case is now a named item rather than part of the sweep**: a folder holding two IMAP copies of one Message-ID must persist both and report a non-null `lastSyncAt`. That is the shape of the defect §8 found, and a green suite is not evidence for it — `DuplicateMessageIdSyncIT` covers the code, not the account.
- [ ] **§6 Sidecar lifecycle** — re-take on the new binary.
- [ ] **§7 Diagnostics** — re-take, and **not only because the binary moved**: the dump's own content changed in this commit, `processStartedAt` and every `startedAfterProcessMs` now measuring from JVM start. The privacy half of the item is unaffected and still has to be checked by reading the dump, not by producing it.
- [ ] **§8 Long run** — §8.1 and §8.2 both open. See the note below on what the previous attempt did and did not deliver.
- [ ] **§9** — release decision.

**What the previous candidate's §8 actually produced, and why it is not carried forward.** A diagnostic bundle and the data directory came back from the tester's machine covering 2026-09-10 13:44 to 2026-09-11 09:55. Three things are worth keeping from it:

- **It found the defect the two backend fixes above answer.** Not during the long run — on the first sync after the account was added, at 14:42:52, and then 29 more times over the next three hours. §8 did its job here; what it caught was a folder that could never recover on its own.
- **It was not an overnight soak.** The machine suspended at 17:09 and resumed at 09:51 — Hikari's housekeeper measured the gap at `16h40m47s` and the backend logged nothing across it. Real continuous running was 3 h 5 min, which is §8.1's territory, not §8.2's. **§8.2 has still never been run**, and the sheet should not be allowed to read as though it has.
- **Resume from a long suspend is clean, and that is worth recording once.** The 200 scheduled cycles the `fixedRate` scheduler had missed all fired within one second of resume; `SyncLockManager` rejected all 200 and the pass that did run completed normally. This is an observation from one resume, not a tested property.

**Why the `a2caa27` sheet below is superseded.** By the rule its own closing paragraph states: the git object id of `backend/src` moved from `6c59df1e87ea` to `6eb6fc4973c5` across #467, #468 and this commit, so §1 no longer describes the tree it was taken from. The other six paths are unchanged, which is why §2 carries and §1 does not. §3–§7 go with it for a different reason — they were taken on a signed build whose commit is named on that sheet as `db11430f`, and the sidecar inside it is the one carrying the defect.

### Candidate 2026-09-10 — `a2caa27`

```text
Release:         VoxRox Mail 0.1.0  (identifier org.voxrox.mail)
Sign-off date:   ____________
Candidate ref:   a2caa27 — provisional; replaced by tag v0.1.0 once cut
Backend commit:  a2caa27
Frontend commit: a2caa27  (the same monorepo)
Platform:        Windows 11 Pro x64, machine lacina-hp-650
Tester:          machine half automated; §3–§9 are the maintainer's
Build origin:    local and unsigned — the signed build comes from the tag, per RELEASE_PROCESS steps 3–4
```

- [x] **§0 Version** — `npm run check:versions` OK, `0.1.0` on all five files. No bump was needed; they already carried it.
- [x] **§1 Backend build** — `mvn -Dmaven.repo.local=.m2repo -Dapp.data-dir=target/test-data clean verify` BUILD SUCCESS in 4:24. Both suites clean, **read from the reports rather than from the console**: `target/failsafe-reports/failsafe-summary.xml` gives `completed=86 errors=0 failures=0 skipped=0 flakes=0`, and all 114 surefire test-suite XMLs carry `failures="0" errors="0" skipped="0"`. The per-class `.txt` reports are not a source for this — they omit nested classes and sum to roughly half the real count. Artifact `target/mail-backend-0.1.0.jar` produced. Sidecar packaged through `scripts/package-sidecar-windows.ps1` (via the dev wrapper with `-SkipTests`, the tests having just run): the output carries `mail-x86_64-pc-windows-msvc.exe`, `app/` and `runtime/`, the launcher is newer than the jar it wraps, and `app/mail-x86_64-pc-windows-msvc.cfg` carries the production `google.client-id`, not a `mail-local-*` placeholder. **Still open in §1:** the clean-profile run without a system-installed JDK — a physical test, not a command.
- [x] **§2 Frontend automation** — `generate:api`, `check:i18n`, `build`, `test:e2e`, `test:functional:stable` and `test:a11y`, all green: functional 277 passed, a11y 65 passed, no flakes reported. Port 4173 was checked free beforehand, so Playwright started its own preview instead of recycling a stale one against an old build.
- [x] **§3 Fresh install** — the signed installer from the draft, verified before running (build provenance through Sigstore: workflow `windows-signed-release.yml@refs/tags/v0.1.0`, commit `db11430f74ba`; SHA-256 `ef2907428c6c654e…3a3442` matching both the published `.sha256` and the digest in the provenance subject). Installed on a **second machine with no system-installed Java** — which is what closes the last open item of §1. Per-user path, binaries in `%LOCALAPPDATA%\Programs\VoxRox\Mail` carrying the sidecar, `app\` and `runtime\`. **One finding, unresolved — see below.**
- [x] **§3a Installer behaviour** — no UAC prompt and no install-mode choice, installer language followed the system, desktop shortcut created. **Not covered:** reinstalling over an existing installation (the reinstall here followed an uninstall, which is a different case) and the downgrade block.
- [x] **§4 Account flows** — a real Google account added through the loopback redirect on the signed build. **This is the blocking item of the whole gate** and it passing is what proves the production `client-id` is baked into the shipped launcher rather than a `mail-local-*` placeholder. The revoke → re-login cycle also passed: after the grant was withdrawn on Google's side the account was marked as needing re-authentication, the UI prompted rather than failing silently, and signing in again returned it to a syncable state. **Not covered:** Microsoft accounts, PASSWORD accounts, `@hotmail`/`@live`.
- [x] **§5 Mail workflows** — reading (HTML, attachment, embedded cid image, remote images blocked by default with per-sender allow), FTS search both hit and empty result, conversation grouping across folders, sending (new, reply, reply all, forward with attachment verified at the recipient, drafts saved and sent, Markdown formatting, signature automatic and manual, address typeahead), organising (Trash, custom folder, Spam and back verified at the provider, seen and flagged toggles, bulk actions), contacts (create, label, search, add from sender row, merge, vCard export and re-import without duplicates) and controls (Ctrl+K with focus returned on Escape, close-to-notification-area, update channel switch). **Not covered:** the 5000+ message full sync — the account used has a smaller mailbox — and the 30 MB attachment, deliberately skipped.
- [x] **§6 Sidecar lifecycle** — killing the Tauri parent left **no orphaned Java process**, which is the case that was bug #89/#90; killing only the sidecar produced a readable error state with an offer to restart, and restarting restored health. **Not covered, deliberately:** reboot mid-sync with WAL recovery, a damaged DB, restoring the pre-migration backup, and disk-full during sync. The June sheet left the same four open; they are candidates for §9 as accepted risk rather than as verified.
- [x] **§7 Diagnostics** — the diagnostic dump generated and **opened and read**, not merely produced: no full email addresses, OAuth tokens, internal API key or message content in it.
- [ ] **§8 Long run** — §8.1 running at the time of writing; §8.2 overnight.
- [ ] **§9** — release decision.

**Finding, unresolved: the first install produced a running process with no window.** On the first launch after the fresh install, no window appeared for about three minutes. It was not a dead process — the uninstaller reported the application as running — so something started and never rendered. An uninstall followed by a reinstall fixed it, and the application has behaved normally since. **The evidence is gone**: the data directory did not survive the uninstall, so there are no logs from that run. What is known: on the same machine a normal start now takes about **20 seconds** and the window appears immediately, showing its connection progress — so the shell-first behaviour works, and a start with no window at all is a different failure from a slow one. The leading hypothesis is the **WebView2 runtime**: this repo's NSIS template installs it when missing, and an application launched while that is still happening has nothing to render into, which would also explain why the second install worked. **The hypothesis was not confirmed** — the check of the WebView2 install date was not run. Anyone reaching this before the next candidate should run it before drawing a conclusion; it is one command against `C:\Program Files (x86)\Microsoft\EdgeWebView\Application`.

**The 20-second start carries no machine name**, against the rule in `PERFORMANCE_BASELINE.md` that every startup number does. The machine was not identified beyond "a second Windows PC without a system JDK", so the number is recorded here as an observation rather than added to the baseline.

**Why this sheet still holds one commit later.** The commit that records it sits on top of `a2caa27` and touches only `CHANGELOG.md` and this file. That is not taken on trust, and not waved through as "docs-only" — the appendix refuses that rule on purpose. The git object ids of the paths §1 and §2 rest on are identical across the two commits: `backend/src` `6c59df1e87ea`, `backend/pom.xml` `dbef3f26bcc3`, `backend/scripts` `ccfeee6ca824`, `frontend/src` `9b3a3ef9972c`, `frontend/src-tauri` `47e446b51110`, `frontend/package.json` `f2a33dc2009c`, `frontend/package-lock.json` `ae0d216cfd15`. This is the computed form the appendix asks for, the same shape `npm run check:audits` uses for the audits. Any further commit before the tag needs the same check, or a re-run.

The sheet for `9c51d9d` below is **superseded**, and not only by age: production Java ([ImapCapabilities.java](src/main/java/org/voxrox/mailbackend/feature/mail/service/ImapCapabilities.java)) and the bundled `NOTICE.txt` both changed after it, so its §0/§1/§2 stopped describing the tree they were taken from.

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
