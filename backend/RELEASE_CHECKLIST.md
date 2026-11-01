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

**Not every candidate re-takes every section.** A section taken by hand carries from the last sheet that ticked it when nothing it rests on has moved since, and only what moved is re-taken; the appendix holds the rule and what each section rests on. [RELEASE_PROCESS.md](../docs/RELEASE_PROCESS.md) "What a release runs" says what runs on every candidate regardless, and why a promotion of a finished beta to stable carries every manual section.

## 0. Version

- [ ] Change the version **only** through `cd frontend ; npm run bump:version <X.Y.Z>`. It lands at once on every file that carries the version — `frontend/package.json`, `frontend/src-tauri/tauri.conf.json`, `frontend/src/lib/version.ts`, `frontend/src-tauri/Cargo.toml` and `backend/pom.xml`, plus the project's own entry in both lockfiles, `frontend/package-lock.json` and `frontend/src-tauri/Cargo.lock` — and editing any of them by hand puts the rest out of step. The gate `npm run check:versions` (part of `npm run check`) catches the divergence, but only after it exists; the script will not allow it. The argument is validated against `SEMVER_RE`, so a typo does not get through.

## 1. Backend build

**Run `npm run regen:licenses:all` (Section 8a) before this build, not after it.** `NOTICE.txt` is bundled into the installer as a Tauri resource (`resources/NOTICE.txt` in [tauri.conf.json](../frontend/src-tauri/tauri.conf.json)), so a regen that changes anything once the candidate is built means the shipped package carries a component list that does not match it -- and the build has to be repeated. Section 8a is where that is verified, not where it is first run.

- [ ] `mvn -Dapp.data-dir=target/test-data clean verify` — one command, not `spotless:check` + `package` separately. `clean` because otherwise SpotBugs analyses the `__BeanDefinitions` left by an earlier `-Paot package` and fails on generated code; `verify` because `package` does not fire the failsafe integration tests. Spotless and SpotBugs both run inside it. From PowerShell, quote the `-D` argument (`'-Dapp.data-dir=target/test-data'`): unquoted, PowerShell splits it at the first dot and Maven stops on the unknown lifecycle phase `.data-dir=target/test-data` before building anything.
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
- [ ] `npm run test:e2e` (the functional suite, then the a11y suite)

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

**The machine half runs on a clean runner since 2026-09-13.** Actions → **Release Candidate Smoke** — started on its own after every successful Windows Signed Release, or dispatched from `main` with the tag — downloads the installer attached to the release, checks its SHA-256 and its build provenance, installs it on a clean `windows-latest` profile and runs [installed-app-smoke.mjs](../frontend/scripts/installed-app-smoke.mjs). It covers the silent install and that it records no answer about the startup update check (§3a), the per-user registration and install path, what the installation carries, both shortcuts, a first start to ready with `session.json`, `.ready`, health 200, `crypto.bin`, the WebView2 profile and the Tauri log under `VoxRox\Mail`, no JVM warning or error from the sidecar in that log, no `org.voxrox.mail*` folder, the webview reaching boot phase ready, a second start over the existing `crypto.bin`, reinstalling the same version without touching it (§3a), killing `voxrox-mail.exe` alone and the sidecar following it (§6), and the silent uninstall. A second job in the same run updates to the candidate from the latest published stable release (§3a, the update smoke and the downgrade block). A green run is the evidence for those items on a candidate's worksheet — record its URL there rather than repeating them by hand. It cannot see a first start while WebView2 is still being installed (runners have it preinstalled) or the absence of a UAC prompt (runners are administrators), so those stay manual, as does everything from §4 on.

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
- [ ] Downgrading to an older version is blocked. Release Candidate Smoke's update job checks it whenever the latest published stable release is older than the candidate: after the update it runs that release's installer, which must fail and leave the installation as it was.
- [ ] The signed release contains `voxrox-mail-<version>-windows-x64-setup.exe`, `.sig` and `latest.json`.
- [ ] **Signing pair match:** the public key bundled in the running app matches the signing private key (`TAURI_UPDATER_PUBKEY` ↔ `TAURI_SIGNING_PRIVATE_KEY`). **Since 2026-08-27 a build gate watches this**, not just this item: the step "Verify updater signature against the shipped pubkey" in `windows-signed-release.yml` verifies the installer signature against the `pubkey` from the generated release config and fails the build before publishing on a mismatch (`npm run tauri:verify-updater-signature`). The manual verification does **not** end there — the gate proves the pair matches, not that an update actually happens, so the update smoke vN-1 → vN stays mandatory for the rest of the chain (download, install, data survival). **Unfixable after shipping.**
- [ ] **Update smoke vN-1 -> vN on the real channel:** install the previous version, let the running application take the update, then confirm the data survived and the app starts. Until 2026-09-09 this lived only inside the item above and in `todo.md`, so the one step nothing else covers had no line of its own -- the build gate proves the key pair and the audit proves the trust chain, and neither proves that the NSIS installer overwrites `runtime/**` and `app/**` of a _running_ installation without a "file in use" failure. **Unfixable after shipping**: an updater broken in the version people already have cannot be repaired through the updater. **The installer half runs on a clean runner since 2026-10-20**: Release Candidate Smoke's update job, [installed-app-update-smoke.mjs](../frontend/scripts/installed-app-update-smoke.mjs), installs the latest published stable release, starts it, adds a contact and runs the candidate's installer over the running installation with the updater's `/P /UPDATE`. It checks that the shell and its sidecar are gone afterwards, that the contact and `crypto.bin` survived and the updated application reaches ready, that the update laid down every file a fresh install of the candidate does with the same content, and that nothing of the previous release is left in the installation or after its uninstall. Record its run on the worksheet. What stays by hand is the updater itself -- the check against the channel's `latest.json`, the download and its signature, the passage from the prompt to the installer: take it on the beta channel with the candidate's prerelease, on an installation of the previous release switched to beta in Settings -> About, before the stable promotion. The first ship, `v0.1.0`, had no previous release and accepted this item in Section 9.
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
- [ ] Generate the dump with **Stáhnout diagnostiku** in Settings → About (or `/api/internal/diagnostic-dump`).
- [ ] The ZIP contains `manifest.json`, `summary.json`, `accounts.json`, `folder-sync-states.json`, `message-counts.json`, `runtime.json`, `threads.txt` and `logs/` with `mail.log`, `mail-frontend.log` and `audit.log`; `manifest.json` lists every log file of the installation as `complete`.
- [ ] The ZIP, its `logs/` included, contains no full email addresses, OAuth tokens, internal API key or message content. Folder names, account names and paths are expected: the logs go in as they are on disk. Checked by machine twice — [DiagnosticDumpPrivacyIT](src/test/java/org/voxrox/mailbackend/core/diagnostic/DiagnosticDumpPrivacyIT.java) on synced mail under `mvn verify`, and `tauri:smoke:sidecar` on the packaged sidecar in the signed build — so by hand this is reading the dump of a real installation once, and running `npm run release:scan-logs -- --logs <unpacked>/logs` over its `logs/`.
- [ ] On a start that fails (the damaged DB of §6 is one), the error screen offers **Uložit logy**; it saves `voxrox-mail-logs-<UTC time>.zip` to Downloads with `logs/` and `about.txt`, the screen reader announces the file's name, and `logs/mail-frontend.log` in it holds the `Sidecar stderr:` and `Sidecar exited on its own:` lines of that start.

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
  - **Healthy:** `WARN` "Transient IMAP error during folder sync … reconnecting and retrying", or "… during folder listing …" for the listing a pass starts with ([MailSyncService.java](src/main/java/org/voxrox/mailbackend/feature/mail/service/MailSyncService.java)) followed by recovery on the next attempt — a couple per day is expected noise, just record the count.
  - **Escalate (should be ~0):** `ERROR` "Folder sync … still failing after N transient-retry attempt(s)", or "Folder listing … still failing after …" ([MailSyncService.java](src/main/java/org/voxrox/mailbackend/feature/mail/service/MailSyncService.java)) = the retry budget is exhausted → investigate the cause / raise `mail.client.retry.*`.
  - **Investigate the classifier:** `ERROR` "Critical error during folder sync … failed to create new store connection", or "Account sync … failed" with that text in its stack trace ([MailSyncService.java](src/main/java/org/voxrox/mailbackend/feature/mail/service/MailSyncService.java)) should no longer appear for a transient cause; if it does, `TransientMailErrors` missed it → extend the classifier.
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
  - the security overrides in `backend/pom.xml` `<properties>`: **none today.** The last three, Tomcat (CVE-2026-66299) and the Jackson 3 and Jackson 2 boms (five advisories of 2026-10-05), went away on 2026-10-27 with Spring Boot 4.1.2, which manages Tomcat 11.0.27, `jackson-bom` 3.1.7 and `jackson-2-bom` 2.21.7 (#789). The review therefore asks whether a new one has appeared, and a new one carries its removal condition in a comment beside it.
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
git history holds it, a finding it still carried moves to the new sheet, and a
section the new sheet carries from it names it on the line.

**The candidate is the tag, not a commit on `main`.** A tick recorded against a
branch commit goes stale as soon as anything merges; a tag cannot move, so
nothing merged later invalidates it. One window stays open because the process
needs it: §0–§2 run before the version bump and the changelog cut that get
tagged, so keep that window to minutes. The object id table is what lets
`npm run release:status` (from `frontend/`) tell whether the sheet still
describes `main` and the tag; it reads the newest sheet, its object id rows and
its `- [x] **§N**` lines, so keep those three shapes.

**A manual section carries when what it rests on has not moved.** A re-cut,
and the next release, re-take by hand only what changed. Each section from §3
to §8 carries from the last sheet that ticked it when none of its inputs in the
table below moved between that sheet's object ids and this sheet's, which are
the tag's. Its line then says it was carried from the `YYYY-MM-DD` sheet and why
the carry holds, as §3a on the 2026-10-19 sheet does. When an input moved, the
section is re-taken: the items the moved paths can reach are taken on this
candidate, and the line says what it carried for the rest. Carrying a section
whose inputs moved is the maintainer's decision, written on the line as one.
What never carries: §0 to §2, the signed build and Release Candidate Smoke,
which run on every candidate; §9, the decision on this candidate; and the
update smoke vN-1 → vN in §3a, since every release is a new pair of versions.
An input outside the repository is not in the table and is the line's to state:
§4 carries only while the OAuth clients and the Azure registration are the ones
the earlier sheet signed in with. Until 2026-10-20 a re-cut discarded §3 to
§8.1 whole, and v0.1.0 went through ten cuts that way, each re-taking the same
items by hand.

How the table is read, by `npm run release:status` and by hand:

- An input is compared by object id, as `check:audits` compares an audit with
  its `Code paths`. The earlier side is read under that sheet's own object id
  rows (`<tree id>:<path>`), which needs neither the commit it was taken on nor
  a tag a re-cut has moved since. So an input has to lie under one of the rows;
  one that does not cannot be compared, and its section is re-taken.
- A path ending in `#key` compares only that key of a JSON file, dot-separated.
  That names `tauri.conf.json` and the lockfile without the version
  `npm run bump:version` writes: no input names a file the bump writes whole, so
  a version bump alone moves nothing. The promotion in RELEASE_PROCESS "What a
  release runs" is the case where nothing else moved either.
- An audit document stands for its `Code paths` as `main` has them, so a path
  that joins the audit joins the section.
- A test that sits beside the code under a frontend path moves that path as
  code does. A carry over a change that touched only tests is the maintainer's
  decision like any other.

`release:status` walks the checklist's history on `main` for the last sheet
that ticked each open section and reports "carry from" it, or "re-take:" with
the inputs that moved. It reads this table by its header and the backticked
tokens of each row, so keep both shapes. A section the table does not name is
taken on every candidate.

| section | rests on                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                               |
| ------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| §3      | `frontend/src-tauri/windows`, `frontend/src-tauri/tauri.conf.json#bundle`, `frontend/package-lock.json#packages.node_modules/@tauri-apps/cli`, `frontend/src-tauri/src/lib.rs`, `frontend/src-tauri/src/main.rs`, `frontend/src-tauri/src/webview_defaults.rs`, `backend/scripts/package-sidecar-windows.ps1`, `backend/scripts/generate-aot-cache-windows.ps1`, `frontend/src/lib/bootstrap.ts`, `frontend/src/lib/backend`, `frontend/src/lib/components/boot`                                                                                                                                                                                                                                                                       |
| §3a     | `frontend/src-tauri/windows`, `frontend/src-tauri/tauri.conf.json#bundle`, `frontend/package-lock.json#packages.node_modules/@tauri-apps/cli`, `frontend/src-tauri/src/update_preference.rs`, `frontend/src/lib/stores/updateStartupCheck.ts`, `frontend/src/lib/components/settings/AboutSettings.svelte`                                                                                                                                                                                                                                                                                                                                                                                                                             |
| §4      | `docs/OAUTH_AUDIT.md`, `backend/src/main/resources/application.properties`, `backend/src/main/java/org/voxrox/mailbackend/feature/account`, `frontend/src/lib/accounts`, `frontend/src/lib/components/AccountForm.svelte`, `frontend/src/lib/components/account-form`, `frontend/src/routes/settings/accounts`                                                                                                                                                                                                                                                                                                                                                                                                                         |
| §5      | `docs/IMAP_SMTP_AUDIT.md`, `docs/CONTENT_RENDERING_AUDIT.md`, `backend/src/main/java/org/voxrox/mailbackend/feature/mail`, `backend/src/main/java/org/voxrox/mailbackend/feature/contact`, `frontend/src/routes`, `frontend/src/lib/components`, `frontend/src/lib/mail`, `frontend/src/lib/compose`, `frontend/src/lib/contacts`, `frontend/src/lib/stores`, `frontend/src/lib/shortcuts`, `frontend/src/lib/commands`, `frontend/src/lib/i18n`, `frontend/src/lib/updates.ts`, `frontend/src/lib/tray.ts`, `frontend/src-tauri/src/tray.rs`, `frontend/package-lock.json#packages.node_modules/svelte`, `frontend/package-lock.json#packages.node_modules/@sveltejs/kit`, `frontend/package-lock.json#packages.node_modules/bits-ui` |
| §6      | `frontend/src/lib/backend`, `frontend/src/lib/bootstrap.ts`, `frontend/src/lib/components/boot`, `frontend/src-tauri/src/lib.rs`, `backend/scripts/package-sidecar-windows.ps1`, `backend/src/main/java/org/voxrox/mailbackend/core/lifecycle`, `backend/src/main/java/org/voxrox/mailbackend/core/config/DatabaseConfig.java`, `backend/src/main/java/org/voxrox/mailbackend/core/backup`                                                                                                                                                                                                                                                                                                                                             |
| §7      | `backend/src/main/java/org/voxrox/mailbackend/core/diagnostic`, `backend/src/main/java/org/voxrox/mailbackend/util/LogMasker.java`, `backend/src/main/resources/logback-spring.xml`, `frontend/src-tauri/src/logs.rs`, `frontend/src-tauri/src/redact.rs`, `frontend/src/lib/logs.ts`, `frontend/src/lib/components/boot`, `frontend/src/lib/components/settings/AboutSettings.svelte`                                                                                                                                                                                                                                                                                                                                                 |
| §8      | `docs/IMAP_SMTP_AUDIT.md`, `docs/OAUTH_AUDIT.md`, `backend/src/main/java/org/voxrox/mailbackend/core/maintenance`                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                      |

Each row is what the section's by-hand items can see break. §3 is the
installer, the shell's start and the sidecar it packages, since Release
Candidate Smoke covers the rest of a first start on every build; §3a is the
installer's pages and the update-check answer it leaves behind; §4 is the OAuth
audit's scope with the registrations of `application.properties`, and the
account form; §5 is the mail backend and the whole UI with the three packages a
screen reader pass rests on; §6 and §7 are the sidecar's supervision, its
recovery and its logs; §8 is the sync and the token refresh under load, and the
`reclaim` pass. A directory moves with anything added under it, so a new file
inside one needs no row of its own.

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

### Candidate 2026-11-01 — tag `v0.2.0-beta.1` (see the object ids below)

```text
Platform:  Windows 11 Pro x64, machine stolak (§0–§2)
Tester:    machine half automated; §3–§9 are the maintainer's
```

| path                         | object id      |
| ---------------------------- | -------------- |
| `backend/src`                | `e7f162b1f756` |
| `backend/pom.xml`            | `b9f41b12f13b` |
| `backend/scripts`            | `8a0fe4f8bdc1` |
| `frontend/src`               | `1dbaab55e06e` |
| `frontend/src-tauri`         | `5b426e68f08b` |
| `frontend/package.json`      | `8cb96b3e0d5f` |
| `frontend/package-lock.json` | `dba15649aabf` |

- [x] **§0 Version** — `npm run bump:version 0.2.0-beta.1`, and `npm run check:versions` OK on all five files. The script writes neither lockfile, so both took the version by hand (`npm install --package-lock-only`, `cargo update -w`), which moved nothing else in either. Unchanged by the re-cut, which keeps the version.
- [x] **§1 Backend build** — `clean verify` on `main` at `f9e0f93` (#819), the tree this re-cut tags: 0 failures and 0 errors on surefire and failsafe; the one skip is `MailSyncQresyncDovecotIT` without Docker, which CI runs. SpotBugs reports no bug instance. `mail-backend-0.2.0-beta.1.jar` produced. Sidecar packaged as the signed build now packages it, extracted and without an AOT cache (#819), with the OAuth values from `backend/.env`: the application jar and 107 libraries in `lib\`, "Google client-id baked into the launcher; no placeholder", all 10 JVM options present in the launcher, "A copy of the image outside target\ started to ready and exited", and 14 of 14 modules declared for the runtime. `regen:licenses:all` after `npm ci` and the packaging changed nothing; the first cut had caught the backend list and `NOTICE.txt` up with what merged since 2026-10-18. The run without a system JDK is taken on the installed candidate.
- [x] **§2 Frontend automation** — on `main` at `f9e0f93`, the version already bumped: `generate:api` (no drift in `schema.d.ts`), `check:i18n`, `build`, `test:unit`, and `test:e2e`, the functional project and then the a11y suite, all passed with no flake and no re-run. The first cut took §2 before the bump, which could not see two tests that held the version as `0.1.0`, `readiness.test.ts` and the About page in `boot.functional.e2e.ts`; CI and the pre-push gate found them, and both now read `CLIENT_VERSION`.
- [x] **§3 Fresh install** — machine half: Release Candidate Smoke [41348557084](https://github.com/TheVoxRox/mail/actions/runs/41348557084) on the build of this tag, 23 of 23 checks, the installer from the draft with its SHA-256 and build provenance verified for `refs/tags/v0.2.0-beta.1` at `648a438`; no JVM warning or error in the Tauri log on either start. The rest taken by hand on the installed candidate by the maintainer (2026-11-01), who reported nothing.
- [x] **§3a Installer behaviour** — the installer half of the update smoke vN-1 → vN: Release Candidate Smoke's update job in the same run, 16 of 16, from the published `v0.1.0` over its running installation with `/P /UPDATE`, the contact and `crypto.bin` kept, the updated installation at ready and file for file a fresh install, nothing of 0.1.0 left, and the 0.1.0 installer refusing to go back (exit 2). The rest of the section by hand per the maintainer (2026-11-01), nothing reported. **The updater itself** (the check against the beta channel's `latest.json`, the download, its signature, the prompt to the installer) can only be taken once the prerelease is published — §9.
- [x] **§4 Account flows** — taken by hand on the installed candidate by the maintainer (2026-11-01), nothing reported.
- [x] **§5 Mail workflows** — taken by hand on the installed candidate by the maintainer (2026-11-01), nothing reported.
- [x] **§6 Sidecar lifecycle** — machine half: Release Candidate Smoke [41348557084](https://github.com/TheVoxRox/mail/actions/runs/41348557084), killing `voxrox-mail.exe` alone took the sidecar with it. The rest by hand per the maintainer (2026-11-01), nothing reported.
- [x] **§7 Diagnostics** — machine half: the diagnostic dump in Release Candidate Smoke carries every section. The rest by hand per the maintainer (2026-11-01), nothing reported.
- [x] **§8 Long run** — §8.1 by hand per the maintainer (2026-11-01), nothing reported. §8.2 is replaced by the weekly soak (see §8.2), whose last scheduled run passed on 2026-10-31 ([41190398430](https://github.com/TheVoxRox/mail/actions/runs/41190398430)).
- [x] **§9** — Blockers: none open. Known issues, as in `CHANGELOG.md` and `docs/release-notes/v0.2.0-beta.1.md`: Gmail only for accounts on the test list; the transient IMAP connection failure; a hostile server can still run the service out of memory when two such accounts sync at once, with the sync of an account paused after three restarts in a row. Accepted risk: the updater itself is taken on the beta channel from an installation of `v0.1.0` right after publishing and before the beta is announced (§3a); a prerelease reaches only installations switched to Beta. Named, not a risk: the scheduled vulnerability scans of 2026-10-29 and 2026-10-30 failed on DOMPurify in the swagger-ui webjar, which only the dev build serves, fixed by #810 and green on `main` by hand ([41331419332](https://github.com/TheVoxRox/mail/actions/runs/41331419332)). Approved: the maintainer, 2026-11-01.

Findings:

- **Blocker of the first cut, fixed: the installed backend did not start off the build machine (#819).** Release Candidate Smoke [41341411496](https://github.com/TheVoxRox/mail/actions/runs/41341411496) failed both jobs on the tag at `e3283f7`, a fresh install and the update from `v0.1.0`: the sidecar stopped in Spring Boot's `JarLauncher` on a null `CodeSource` location, because on JDK 25 a class loaded from an AOT cache takes its code source from the directory the cache was trained in (JDK-8376576, fixed in JDK 28). Reproduced locally by moving the image and renaming the training directory. The tag and its draft were deleted unpublished; the backend now ships extracted and without a cache, and packaging starts a copy of the image outside the build tree. Release Candidate Smoke on this cut's build is the evidence that it starts installed.
- **The first release that updates from a predecessor.** The §3a update smoke vN-1 → vN, accepted in §9 of `v0.1.0` for want of a previous version, is taken on this candidate: Release Candidate Smoke's update job from `v0.1.0`, and the updater itself on the beta channel from an installation of `v0.1.0` before the beta is announced.
- **Found and fixed while cutting: `previousAppVersion` in the `app_started` audit record would have named the new version after the first update (#816).** The backup a version takes before migrating is the newest file when the record is written; the record now skips it. §3a's update smoke on the beta channel can read the record as the evidence of the version updated from.
- **§0–§2 taken before the bump cannot see a test that holds the version.** Two did (§2), found by the pre-push gate and by CI on the cut's pull request rather than by §2. Until no test hard-codes it, the next cut runs `test:unit` and `test:e2e` once more after `bump:version`.
- **This cut takes everything merged since `v0.1.0`:** the pull requests #752 to #819, among them the first new features (#797, #804, #805, #814, #815). Each fix that merges from here adds its number to the next item.
- **Waiting for the next cut:** none.

### Candidate 2026-10-19 — tag `v0.1.0` (see the object ids below)

```text
Platform:  Windows 11 Pro x64, machine stolak (§0–§2)
Tester:    machine half automated; §3–§9 are the maintainer's
```

| path                         | object id      |
| ---------------------------- | -------------- |
| `backend/src`                | `0812dec49210` |
| `backend/pom.xml`            | `231ed8a68720` |
| `backend/scripts`            | `28a0fb115d0c` |
| `frontend/src`               | `93f49e5bbcfe` |
| `frontend/src-tauri`         | `0895a400510b` |
| `frontend/package.json`      | `e220104666cd` |
| `frontend/package-lock.json` | `4881be6f20f8` |

- [x] **§0 Version** — `npm run check:versions` OK, `0.1.0` on all five files; unchanged by this cut, which re-takes the same version.
- [x] **§1 Backend build** — `clean verify` green: surefire 1686 and failsafe 168, 0 failures and 0 errors on both. The one skip is `MailSyncQresyncDovecotIT` without Docker, the local condition #511 documents; CI runs it. SpotBugs reports no bug instance. `mail-backend-0.1.0.jar` produced; sidecar packaged with `-EnableAotCache`, as the signed build packages it, with the launcher, `app/`, `runtime/` and the production Google client-id — the packaging step’s own checks report "Google client-id baked into the launcher; no placeholder", all 10 JVM options present in the launcher, "The JVM mapped app\mail.aot from outside the image directory and started on it", and 14 of 14 modules declared for the runtime. `regen:licenses:all` ran after `npm ci` and the packaging and changed no inventory: its only output was the date in `NOTICE.txt` and the order of the two `sqlite-jdbc` classifiers, both left as they were. The run without a system JDK: on lacina-hp-650 the installed sidecar loads `jvm.dll` from its own `runtime\` although `JAVA_HOME` names a system JDK; a machine with no JDK at all ran the 2026-10-13 build.
- [x] **§2 Frontend automation** — `generate:api` (regenerated `schema.d.ts` with no drift), `check:i18n` (784 keys), `build`, and `test:e2e`, which is the wrapper that runs the functional project (336 passed) and then the a11y suite (168 passed). All green, no flake and no re-run.
- [x] **§3 Fresh install** — machine half: Release Candidate Smoke [39305458066](https://github.com/TheVoxRox/mail/actions/runs/39305458066) on the build of this tag. On lacina-hp-650 with the previous installation removed: the installer from the draft, SHA-256 `c1e5bed8…888d` matching its `.sha256` and `gh attestation verify` naming `refs/tags/v0.1.0` at `0d84ec6`; first start ready in 8 s, no error dialog, `crypto.bin`, `session.json` with a random port, `.ready`, health 200, `webview\` and `logs\`, no `org.voxrox*` folder (`audit.log`: `crypto_bootstrapped`, `app_started … previousAppVersion=<none>`). `mail-frontend.log` does carry the JVM's AOT cache warning — first finding below.
- [x] **§3a Installer behaviour** — carried from the 2026-10-13 sheet by the maintainer's decision (2026-10-20): since `8728d51` the template changed only in the reinstall cleanup and the downgrade block (#729), not in its pages, so no UAC prompt, the language, the privacy page and its answer stand. On this install: both shortcuts point at `voxrox-mail.exe`, the uninstall entry names it, `UpdateStartupCheck` is written, and `latest.json` on the draft names 0.1.0 with the installer's `.sig` as its signature. A silent reinstall of the same version: Release Candidate Smoke. **Not taken:** the downgrade block, for want of an older installer, and the update smoke vN-1 → vN — §9.
- [x] **§4 Account flows** — Google sign-in against a real account on this build (`audit.log`: `account_create`, `oauth2_login`, `token_refresh`), so Google accepts the client-id the launcher carries, none of its three OAuth values a placeholder; a seznam.cz password account from its preset. The rest carried from the 2026-10-13 sheet: nothing under the OAuth audit's `Code paths` or in `application.properties` changed since `8728d51`, and the three OAuth secrets date from 2026-06-17. The account form with a screen reader (#733) per the maintainer, who reported nothing on it; #735 removes the template list together with its i18n keys, and #746 is covered by the functional suite.
- [x] **§5 Mail workflows** — the screen reader pass on SvelteKit 3 (#721), the skip link a second time (#742) and forgetting a history suggestion (#747), per the maintainer: Esc does not reliably close an open message (second finding), nothing else reported. #722 is covered by `MarkdownBodyRendererTest`; #737 changes colour only and was not taken by hand. The rest carried from the 2026-10-13 sheet by the maintainer's decision (2026-10-20).
- [x] **§6 Sidecar lifecycle** — on this install: the sidecar's JVM killed with `Stop-Process`, restarted on its own and healthy 13 s later, with one launcher and one JVM after it (#718); killed four times within a minute, the error view reads "Službu aplikace ukončil systém (kód -1)" (#734) and **Restartovat službu** restored health and `/api/v1/accounts`; a file that is not a database put in as `mail.db`: exit 65 once, no restart, the view says the database is damaged and offers **Uložit logy** (#734), and the database put back started healthy. The Restart of the boot views not reaching the next backend as a crash (#750) is the independent pass's measurement on this tag (#752); killing `voxrox-mail.exe` with the sidecar following it is Release Candidate Smoke's; the reboot during a sync is carried from the 2026-10-13 sheet.
- [x] **§7 Diagnostics** — the dump from `/api/internal/diagnostic-dump` holds every entry §7 lists plus `client-boot.json` and `startup-timings.json`, which carries `processStartedAt`; `manifest.json` gives the three logs as `complete` with no replacement. `release:scan-logs` over its `logs/`: 0 leaks, and the 2 CRITICAL, 2 ERROR and 2 WARN all come from the deliberately damaged database. **Uložit logy** on the error view of a backend that never started: `voxrox-mail-logs-<UTC time>.zip` in Downloads with `logs/` (three logs, stored) and `about.txt`, its `mail-frontend.log` holding the `Sidecar stderr:` lines and `Sidecar exited on its own: code Some(65)`; the status region names the file. A real installation's dump carried from the 2026-10-13 sheet.
- [x] **§8 Long run** — §8.1 carried from the 2026-10-13 sheet by the maintainer's decision (2026-10-20); the folder listing's retry (#738, #739) and the sync pause (#740, #745) are carried by their tests and the B1 passes (#749, #752). §8.2 does not run for this candidate (see §8.2) — §9.
- [x] **§9** — Blockers: none open. Known issues, as in `CHANGELOG.md` and `docs/release-notes/v0.1.0.md`: the transient IMAP hiccup; Gmail only for accounts on the test list; a hostile server can run the service out of memory (B1-14, B1-21), with the sync of that account paused after three restarts in a row. Accepted risks: §8.2 not run; the §3a update smoke vN-1 → vN, with no previous version on a first ship; the §3a downgrade block, untested; the AOT cache unused on an installed build (first finding). Open, not blocking by the maintainer's decision (2026-10-20): Esc on an open message (second finding). Approved: the maintainer, 2026-10-20.

Findings:

- **New, accepted for 0.1.0 (maintainer, 2026-10-20): the AOT cache does not map on an installed build, so #719's speed-up does not reach users.** On every start the JVM writes `mail-backend-0.1.0.jar … timestamp has changed`, then `Unable to map shared spaces`, and starts without the cache. The cache records the jar's size and modification time; the signed build's own check maps it on the jpackage image before bundling, and after the NSIS install every file in `app\` carries a time rounded to the second, so somewhere between the two the jar's time moves. Startup only: the backend runs, and the four lines are WARN in `mail-frontend.log`. Release Candidate Smoke does not look at it. Fixed after the release, with that smoke checking the cache maps.
- **New, open and not blocking (maintainer, 2026-10-20): Esc does not reliably close an open message with a screen reader.** The path is unchanged since `8728d51` apart from #721's import aliases: Esc is handled on the window and relayed from the body frame's forwarder, and the functional suite covers it from a list row. Not yet told apart from the screen reader taking the first Esc to leave focus or forms mode; to be investigated after the release.
- **Blocker of the 2026-10-13 sheet, fixed since: the app never started on a Windows profile named with diacritics (closed-beta tester, 2026-10-14 and 15).** The tester's logs hold up to sixteen backends alive at once on one database and no account ever added. The JVM wrote its AOT cache warning, which carries the install path, to standard output in the system code page; the shell plugin reported the line as an error, and the supervisor restarted on top of a running backend (#718). The AOT cache it warned about did not map on any path: its `-XX:AOTCache` was relative to the working directory, and from the install directory it was refused with `runtime\lib\modules … size has changed` (#719). On this signed build the JVM still writes an AOT warning on every start (first finding), and the shell logs it as WARN and restarts nothing, one backend running; an install path with diacritics was not taken.
- **An installation of an earlier candidate does not start on this build until its `%LOCALAPPDATA%\VoxRox\Mail\db` is removed, the tester's included.** #741 moved the crash guard's three columns from a V2 migration into `V1__init.sql`, which changes V1's checksum, and the backend refuses a database migrated under the old one. A fresh profile is not affected, and nothing published carries the old checksum.
- **The independent pass over the B1-23 fix (#750) ran alongside this cut (maintainer, 2026-10-19) and confirmed it against this tag, so the tag stands (#752).** It found no confidentiality or integrity finding and no regression of normal sync, the criteria that would have voided the tag: in the running app none of 40 Restarts handed the killed backend's exit over, against 10 of 10 on the shell before the fix. The B1 verdict reads "PASS, two findings accepted" (audit 1.76).
- **B1-23 voided the previous tag, `7f45c48`, and is fixed in this cut (#749, #750).** The independent pass over the B1-22 fix confirmed that fix at 1.74 and found that the Restart of the boot views handed the backend it kills over as a crash, so three Restarts during an account's sync paused it: the regression of normal sync that sheet named. B1-24, a body download beside a crash that counts an innocent account, is accepted for 0.1.0.
- **Fixed in this cut and confirmed on the signed build: Uložit logy on the error screen of a backend that never started, and a sidecar killed on Windows reported as stopped by the system rather than as a failed start (#734).** The first was a known issue of the 2026-10-18 sheet and leaves the provisional list; the second was its Low finding on "kód -1". §6 and §7 record both.
- **Fixed in this cut: a dead pooled connection met while listing folders is retried within the cycle (#738, #739).** It was the Low finding of the 2026-10-13 run, where seznam.cz showed `MAIL_SYNC_CONNECTION_FAILED` until the next cycle while Gmail retried. Carried by its tests; §8.1 was not re-run.
- **New in this cut: the sync of an account the backend keeps dying in pauses after three restarts in a row (#740, #745).** The provisional known issue on running out of memory says so now. Nothing in §3 to §8 provokes it; the unit and repository tests carry it, and the B1-22 pass confirmed it (#749).
- **Minimizing during start stays gone on this build (Tauri 2.12.4, #729).** The `todo.md` repro posted `SC_MINIMIZE` 377 ms after the process started, when the window held only `WRY_WEBVIEW` and no Chromium child yet; 5 s later the window was there, minimized, with its webview, `SW_RESTORE` brought it back, and the log has no `failed to create webview`.
- **Every fix goes into 0.1.0 for now (maintainer, 2026-09-21), and the merge freeze stays suspended.** A merge moves `main`, not the tag, so what merges reaches 0.1.0 only through another cut, and that cut comes **before §3 starts**: manual evidence taken on this tag would be discarded with it, as the 2026-10-13 sheet's §3 to §8.1 were.
- **This cut takes everything merged since `8728d51`.** Up to the 2026-10-18 sheet: #716 (the manual sections of the 2026-10-13 sheet), #720 (the updater without its `zip` feature and `rustls` 0.23.46), #718 and #719 (the blocker above), #721 (SvelteKit 3 and adapter-static 4), #727 and #723 (development and build tools only), #729 (Tauri 2.12.4 and the installer template), #722 (commonmark 0.30.1), #730 (`zip` 9.0.5 and `serde_json` 1.0.154 in the shell) and #731 (the npm SBOM under SvelteKit 3). Since it: #732 (that sheet, whose tag was never moved), #733 (the account form for a screen reader, and Seznam's note), #734 (Uložit logy and the Windows kill, above), #735 and #736 (the "Šablona poskytovatele" list and the endpoint only it used), #737 (the compose label tone), #738 and #739 (the folder listing retry), #740, #741 and #745 (the sync pause, its columns in V1, and B1-22 fixed), #742 (the skip link a second time), #743 (development tooling only), #744 (the IMAP/SMTP audit re-verified at 1.72), #746 (Czech sample addresses) and #747 (forgetting a history suggestion in the typeahead). Since the 2026-10-19 sheet whose tag B1-23 voided: #748 (that sheet), #749 (the B1-22 pass at 1.74, which found B1-23) and #750 (B1-23 fixed). Each fix that merges from here adds its number to the next item.
- **Waiting for the next cut:** #752 (the independent pass over the B1-23 fix, audit 1.76; documents only).

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
