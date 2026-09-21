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
- [ ] The sidecar launcher `app/voxrox-mail-backend-x86_64-pc-windows-msvc.cfg` carries the baked-in `...google.client-id` (not a `mail-local-*` placeholder). Both the signed workflow and `package-sidecar-dev-windows.ps1` ensure this; a bare `package-sidecar-windows.ps1` without the OAuth env now fails the build unless it runs with `-AllowPlaceholderOAuth`. **Verified 2026-06-26** (`package-sidecar-dev-windows.ps1 -SkipTests`): 3 OAuth values injected, the built-in verification step reports "Google client-id baked into the launcher; no placeholder"; the `.cfg` carries `google.client-id` + `google.client-secret` + `microsoft.client-id`, with no `mail-local-*`.
- [ ] The sidecar output contains `voxrox-mail-backend-x86_64-pc-windows-msvc.exe`, `app/` and `runtime/`. **Verified 2026-06-26:** top-level `mail-x86_64-pc-windows-msvc.exe` (a 495 KB launcher) + `app/` (jar 77.9 MiB + `.cfg` + `.jpackage.xml`) + `runtime/` (a 123 MB bundled JRE).
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

**The machine half runs on a clean runner since 2026-09-13.** Actions → **Release Candidate Smoke** — started on its own after every successful Windows Signed Release, or dispatched from `main` with the tag — downloads the installer attached to the release, checks its SHA-256 and its build provenance, installs it on a clean `windows-latest` profile and runs [installed-app-smoke.mjs](../frontend/scripts/installed-app-smoke.mjs). It covers the silent install and that it records no answer about the startup update check (§3a), the per-user registration and install path, what the installation carries, both shortcuts, a first start to ready with `session.json`, `.ready`, health 200, `crypto.bin`, the WebView2 profile and the Tauri log under `VoxRox\Mail`, no `org.voxrox.mail*` folder, the webview reaching boot phase ready, a second start over the existing `crypto.bin`, reinstalling the same version without touching it (§3a), killing `voxrox-mail.exe` alone and the sidecar following it (§6), and the silent uninstall. A green run is the evidence for those items on a candidate's worksheet — record its URL there rather than repeating them by hand. It cannot see a first start while WebView2 is still being installed (runners have it preinstalled) or the absence of a UAC prompt (runners are administrators), so those stay manual, as does everything from §4 on.

- [ ] Install the release candidate through the Windows NSIS installer `voxrox-mail-<version>-windows-x64-setup.exe` onto a clean profile with no existing `%LOCALAPPDATA%\VoxRox\Mail`.
- [ ] Verify the default installation path (per-user, `installMode: currentUser`): binaries in `%LOCALAPPDATA%\Programs\VoxRox\Mail`, separate from the data in `%LOCALAPPDATA%\VoxRox\Mail`.
- [ ] The installation folder `%LOCALAPPDATA%\Programs\VoxRox\Mail` contains the desktop shell `voxrox-mail.exe`, the bundled sidecar launcher `voxrox-mail-backend.exe`, `app/` and `runtime/`. The target triple in `voxrox-mail-backend-x86_64-pc-windows-msvc.exe` belongs to the build output only: Tauri strips it when it bundles `externalBin`, which the first Release Candidate Smoke run confirmed on the installed `v0.1.0` candidate.
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
  - _Since 2026-06-30 a parent-death watchdog handles this ([ParentProcessWatchdog.java](src/main/java/org/voxrox/mailbackend/core/lifecycle/ParentProcessWatchdog.java)): the frontend starts the sidecar with `MAIL_SIDECAR_WATCH_PARENT=1`, the backend reads `System.in` and calls `System.exit(0)` when the stdin pipe closes (the frontend dying, force-kill included). This item verifies that it really works on a packaged build — the `voxrox-mail-backend` process should disappear from Task Manager within a few seconds._
  - _Verified 2026-06-30 on a dev-packaged sidecar through tauri:dev: force-kill of `app.exe` → both `mail.exe` (launcher + JVM grandchild) disappeared in ~13 s, 0 orphans (of which ~10 s was the graceful timeout on a hanging SSE, then a clean DB close). To be repeated on a signed release build._
- [ ] Kill only the sidecar backend.
- [ ] The UI shows an understandable error state.
- [ ] The UI offers to restart the sidecar.
- [ ] Restarting the sidecar restores health and ordinary API calls.
- [ ] Reboot the computer in the middle of a sync. The weekly soak kills the backend hard in the middle of its work and checks that the restarted one leaves a sound database and a complete mirror; a reboot adds only the machine itself.
- [ ] The next start goes through SQLite WAL recovery and the application continues.
- [ ] A damaged DB (`mail.db` or the WAL) — the application starts into a readable error state, not a silent crash. The backend half is covered by [DatabaseRecoveryTest](src/test/java/org/voxrox/mailbackend/core/config/DatabaseRecoveryTest.java): a file that is not a database and one with damaged pages both stop the start before Flyway, as exit 65, with nothing written. By hand only the shell half: the boot error view says the database is damaged, at once and without restarts.
- [ ] Restore from the backup `db/mail.db.backup-pre-v*` (the pre-migration snapshot) — after replacing the DB with the backup the app starts and the data matches the snapshot. The same test follows OPERATIONS.md "Damaged database" step by step on a real file, so this needs a hand only when that procedure changes.
- [ ] Disk full during a sync — the sync fails in a controlled way (a readable error, no DB corruption) and continues once space is freed. The weekly soak does this on a small tmpfs: the backend stays up and healthy while the disk is full, and the database and the mirror are sound at the end.

## 7. Diagnostics

- [ ] `npm run release:scan-logs` (from `frontend/`) over the installation's logs: every ERROR and WARN group it lists is explained or has an issue. It reads the rotated `.gz` files as well, which a `Select-String` over `mail.log.*` silently does not.
- [ ] The same scan reports no CRITICAL audit record and no leak — no unmasked address or Message-ID, no token, not the session API key; it exits 1 on any of them.
- [ ] Generate `/api/internal/diagnostic-dump`.
- [ ] The ZIP contains `summary.json`, `accounts.json`, `folder-sync-states.json`, `message-counts.json`, `runtime.json`.
- [ ] The ZIP contains no full email addresses, OAuth tokens, internal API key, message content, names of folders the user created, or the Windows account name. Checked by machine twice — [DiagnosticDumpPrivacyIT](src/test/java/org/voxrox/mailbackend/core/diagnostic/DiagnosticDumpPrivacyIT.java) on synced mail under `mvn verify`, and `tauri:smoke:sidecar` on the packaged sidecar in the signed build — so by hand this is reading the dump of a real installation once.

## 8. Long run

**Gate (proportionate for v0.1.0):** run the app with sync enabled across two windows — a first hour that fits in the same sitting as §3–§7, and an overnight stretch that only wall-clock time can buy — and run the log-scan gate over both. **A deep JFR + JDK Mission Control analysis (lock contention) is an optional post-release deep-dive** — do it on a real performance complaint or a suspected leak, not as a blocker of the first release.

**Why it is split, measured 2026-09-10.** The length was never about the application's own timers. Sync runs on a five-minute interval and the UID enumeration hourly, so what those exercise is bound by how many cycles pass, not by how long the clock runs. `reclaim` — the expensive maintenance pass, a WAL checkpoint plus a threshold-gated VACUUM — is scheduled at the same interval as the soak itself (`mail.client.db.reclaim-interval`, 24 h as configured today, after a 30-minute initial delay), so a 24 h run exercises it **exactly once**, at the half-hour mark, and no more than a one-hour run would; its second pass falls past the end of the run. What the long stretch actually buys is the machine's daily cycle — sleep and wake, a network change, a scheduled antivirus pass, an OAuth access token expiring while the app is up. That is why the second window is "overnight" rather than a number, and why the checks that need minutes no longer queue behind the ones that need a night. Before this split the section was one undifferentiated 24 h block, and it had never been performed once.

### 8.1 First hour — the same sitting as §3–§7

- [ ] Start the application with sync enabled and leave it running.
- [ ] Check that the IMAP pool does not wrongly recycle dead connections.
- [ ] An OAuth access token expires and is refreshed while the application keeps running. §4 covers the revoke → re-login cycle and the refresh across a restart; neither exercises an expiry under load.
- [ ] The first `reclaim` pass (half an hour in) completes without stalling the UI or leaving the database locked — the only time this pass runs, whatever the length of the soak.
- [ ] Log-scan gate after every smoke and long run: `npm run release:scan-logs -- --since <start of the run>`, and either explain every group it lists or open an issue — a silent error path is exactly the class of bug from the 2026-06 review.

### 8.2 Overnight — what only wall-clock time buys

**Decided 2026-09-13: this is no longer a manual gate before publishing, and automation replaces it.** A manual overnight run before every publish is unusual, and this one had never once been performed: it held the publish back by a night for what the paragraph above says the length buys — the machine's daily cycle, not the application's timers. What replaces it, built 2026-09-17: deterministic tests for what the night was meant to cover — [SyncConnectionFaultGreenMailIT](src/test/java/org/voxrox/mailbackend/feature/mail/service/SyncConnectionFaultGreenMailIT.java) (connections dead after a sleep, a server that cannot be reached, a network that goes quiet) and [OAuthTokenExpiryGreenMailIT](src/test/java/org/voxrox/mailbackend/feature/mail/service/OAuthTokenExpiryGreenMailIT.java) (an access token the server stops accepting, a refresh token that was revoked), both in every `mvn verify` — and the weekly [soak.yml](../.github/workflows/soak.yml), whose [SyncSoakIT](src/test/java/org/voxrox/mailbackend/feature/mail/service/SyncSoakIT.java) runs the packaged backend for half an hour with shortened intervals, pauses it with `SIGSTOP`, kills and restarts it and fills its disk, and then checks the database, the mirror against the server, the heap, the WAL and the log signals listed below. Run it by hand with `mvn -Psoak verify -Dsoak.duration=PT10M` from `backend/`. The daily cycle on real machines is then observed on the beta, through the diagnostic dumps testers send. **For v0.1.0 this section does not run and goes into §9 as an accepted risk**: the audience is the closed beta, and a defect found after publishing is repaired through the updater. The items below stay as the list that automation has to cover.

- [ ] The application is still running in the morning, having crossed at least one sleep/wake of the machine.
- [ ] Check the memory footprint.
- [ ] Check the growth of the SQLite DB/WAL.
- [ ] Check that repeated syncs do not create duplicate messages.
- [ ] A passive log-watch for the transient hiccup **D** (`failed to create new store connection`) — wrapped in a bounded retry+backoff since #78, with the transient classified by [TransientMailErrors.java](src/main/java/org/voxrox/mailbackend/feature/mail/service/TransientMailErrors.java). `npm run release:scan-logs` sorts `logs\mail.log` and its rotated files into the three signals:
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

## Appendix — candidate sheet

One sheet per release candidate, for the sections above. It is a record, not an
argument: one line per section with the result and where the evidence is (a
run URL, a report, a log), plus the findings. Why a section was re-taken, and
anything else that explains a tick, belongs in the commit that records it. A
sheet that a re-cut replaces is deleted rather than kept below the new one; the
git history holds it, and a finding it still carried moves to the new sheet.

**The candidate is the tag, not a commit on `main`.** A tick recorded against a
branch commit goes stale as soon as anything merges; a tag cannot move, so
nothing merged later invalidates it. One window stays open because the process
needs it: §0–§2 run before the version bump and the changelog cut that get
tagged, so keep that window to minutes. The object id table is what lets
`npm run release:status` (from `frontend/`) tell whether the sheet still
describes `main` and the tag; it reads the newest sheet, its object id rows and
its `- [x] **§N**` lines, so keep those three shapes.

Shape:

````markdown
### Candidate YYYY-MM-DD — tag `vX.Y.Z` (`<commit>`)

```text
Platform:  Windows 11 Pro x64, machine <name>
Tester:    <who took §3–§9>
```

| path          | object id |
| ------------- | --------- |
| `backend/src` | `<id>`    |

- [ ] **§0 Version** — <result>; <evidence>
- [ ] **§9** — <decision>

Findings:

- <what went wrong or stays open, and what to do before the next run>
````

### Candidate 2026-09-19 — tag `v0.1.0` (see the object ids below)

```text
Platform:  Windows 11 Pro x64, machine lacina-hp-650 (§0–§2)
Tester:    machine half automated; §3–§9 are the maintainer's
```

| path                         | object id      |
| ---------------------------- | -------------- |
| `backend/src`                | `737194e2ed5e` |
| `backend/pom.xml`            | `63ece934432b` |
| `backend/scripts`            | `bd35a3977876` |
| `frontend/src`               | `231d9cd0fa14` |
| `frontend/src-tauri`         | `bdd1f88ef2df` |
| `frontend/package.json`      | `e46818dfa9c5` |
| `frontend/package-lock.json` | `3d4143705c65` |

- [x] **§0 Version** — `npm run check:versions` OK, `0.1.0` on all five files.
- [x] **§1 Backend build** — `clean verify` green: surefire 1333 and failsafe 93, 0 failures and 0 errors on both. The one skip is `MailSyncQresyncDovecotIT` ("disabledWithoutDocker is true and Docker is not available"), the local condition #511 documents; CI runs it. `mail-backend-0.1.0.jar` produced; sidecar packaged with the launcher, `app/`, `runtime/` and the production Google client-id — the packaging step's own check reports "Google client-id baked into the launcher; no placeholder", 0× `mail-local-`. `regen:licenses:all` ran first and moved one version, `devalue` 5.8.1 → 5.9.4 from #520, so `NOTICE.txt` and the two inventories are committed with this sheet. **Open:** the run on a clean profile without a system JDK.
- [x] **§2 Frontend automation** — `generate:api` (regenerated `schema.d.ts` with no drift), `check:i18n` (737 keys), `build`, and `test:e2e`, which is the wrapper that runs the functional project (283 passed) and then the a11y suite (65 passed). All green, no flake and no re-run.
- [ ] **§3 Fresh install** — machine half green: Release Candidate Smoke on the signed build of this tag, 22 of 22 checks, [run 35467196946](https://github.com/TheVoxRox/mail/actions/runs/35467196946). It covers the silent per-user install and what it carries, both shortcuts, a first start to ready in 16 s, `session.json`, health 200, `crypto.bin`, the WebView2 profile and the Tauri log under `VoxRoxMail`, no `org.voxrox.mail*` folder, the webview at boot phase ready, and a second start over the same `crypto.bin`. Open by hand: a first start while WebView2 is still installing, which a runner cannot see. Take the first finding below before it.
- [ ] **§3a Installer behaviour** — open. The same smoke run covers two items: the silent install records no answer about the startup update check, and a silent reinstall of the same version exits 0 and keeps `crypto.bin`. Open by hand: no UAC prompt (runners are administrators), the privacy page with a screen reader, `latest.json` read from the draft by hand, a reinstall over an installation that holds an account (the smoke's holds none), and the downgrade block, which no cut has covered.
- [ ] **§4 Account flows** — open. The Google login is the blocking item: it proves the production client-id is in the launcher `.cfg`.
- [ ] **§5 Mail workflows** — open, including a trash folder holding two IMAP copies of one Message-ID (both persist, `lastSyncAt` is set) and the tray items "Synchronizovat" and "Nová zpráva" (#490).
- [ ] **§6 Sidecar lifecycle** — open. Killing `voxrox-mail.exe` alone takes the sidecar with it on the smoke run above, gone 10.4 s after the kill. By hand: Task Manager lists both processes, killing only the sidecar and restarting it from the UI, and the damaged-database boot error view.
- [ ] **§7 Diagnostics** — open, including the dump's `processStartedAt`; the privacy half is taken by reading the dump, which since #518 must show `folder-<n>` for a folder the user named and `~` where a path enters the home directory.
- [ ] **§8 Long run** — §8.1 open; §8.2 does not run for this candidate (see §8.2).
- [ ] **§9** — open.

Findings:

- **No window on the first launch after a fresh install** (the 2026-09-10 cut, build `db11430`, a second machine without a system JDK). For about three minutes the process ran without a window; an uninstall and reinstall fixed it, and the logs went with the uninstall. Unconfirmed hypotheses: the WebView2 runtime still installing, or Microsoft Defender, the maintainer's leading suspect; the code argues against WebView2, since the installer waits for that install and a missing runtime ends the app. **A third hypothesis joined the two after the fact**, and it is the cheapest to test: the wry 0.55.1 bug recorded in `todo.md` — found 2026-09-17, a week after the incident, so it is not among the ones written above — ends in a window that is never shown while the process keeps running with a tray icon and only Task Manager gets you out, which is this symptom exactly. It leaves `failed to create webview` in the Tauri log, and that line is absent from the other two. Before the next §3 on that machine, start [collect-first-launch-evidence.ps1](../frontend/scripts/collect-first-launch-evidence.ps1) from an elevated PowerShell 7 window and install while it observes. It acts on nothing: it takes the WebView2 inventory before and after the window (a runtime directory appearing during it settles the WebView2 hypothesis), records Defender over the install and the first launch, times when a visible window, the sidecar process and `.ready` each appeared, and copies the Tauri log before any uninstall and scans it for that one line. On the same machine a normal start takes about 20 s with the window shown at once.
- **Candidates for §9 as accepted risk — three fewer than on the previous sheet.** Of the four §6 scenarios no cut had covered, a damaged database, restoring the pre-migration backup and a full disk are now code with tests behind them (#517 `DatabaseRecoveryTest` follows the OPERATIONS.md procedure on a real file; #519 `SqliteFullRecoveryTest` plus the soak's disk-full phase), which is what this cut was taken for. **Reboot mid-sync** remains, as do §8.2 and the §3a update smoke, which has no previous version to update from on a first ship.
- **Nothing manual carries from earlier cuts, and this is the fifth.** `v0.1.0` was first tagged on `db11430`; only that first build was taken by hand, and it is gone. That is also why this re-cut was free — §0–§2 are the machine half and §3–§9 were all open — and why the next one would not be. The reasons for each cut are in `CHANGELOG.md` under 0.1.0.
- **Main is frozen from the cut commit.** Only a release blocker merges before §3–§9 are finished: the app is unusable, loses data, or contradicts a published claim in `PRIVACY.md` / `SECURITY.md`. Anything else found on the way is recorded here and goes to 0.1.1. Without that rule the manual sections never finish, because each merge invalidates the build they were taken on.

### Helpers

Health and the diagnostic dump, reading the port and the key from `session.json`:

```powershell
$data = "$env:LOCALAPPDATA\VoxRox\Mail"
$s = Get-Content "$data\session.json" -Raw | ConvertFrom-Json
$s | Format-List appVersion, apiVersion, minClientVersion, dbSchemaVersion, port, baseUrl
Invoke-RestMethod "$($s.baseUrl)/internal/health" -Headers @{ 'X-API-KEY' = $s.apiKey }   # → 200 + status
# Invoke-WebRequest "$($s.baseUrl)/internal/diagnostic-dump" -Headers @{ 'X-API-KEY' = $s.apiKey } -OutFile "$env:TEMP\diag-dump.zip"
```

Log scan, from `frontend/` (the dev build's logs are under `Mail.dev`; `--strict=true` also fails on any other ERROR):

```powershell
npm run release:scan-logs
npm run release:scan-logs -- --logs "$env:LOCALAPPDATA\VoxRox\Mail.dev\logs" --since 2026-09-17T08:00
```
