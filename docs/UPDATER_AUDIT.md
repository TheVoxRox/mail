# VoxRox Mail — Auto-Updater Audit

|                    |                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                          |
| ------------------ | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **Version**        | 1.8                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                      |
| **Date**           | 2026-08-28                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                               |
| **Applies to**     | VoxRox Mail V0.1.0                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                       |
| **Audited commit** | `c6744a1` (re-verified 2026-08-28 after the signing-key gate and the install-ordering fix; v1.4 anchor: `cad05cb`, recorded pre-squash as `5799e8b`; v1.2/1.3 anchor: `3162e6a` (#144), v1.0/1.1 baseline: `d55b753`)                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                    |
| **Code paths**     | `frontend/src-tauri/src`, `frontend/src-tauri/tauri.conf.json`, `frontend/src-tauri/capabilities`, `frontend/src/lib/updates.ts`, `frontend/src/lib/components/UpdatePromptDialog.svelte`, `frontend/src/lib/components/UpdateFailureDialog.svelte`, `frontend/src/lib/components/settings/AboutSettings.svelte`, `.github/workflows/windows-signed-release.yml`, `.github/workflows/beta-channel.yml`, `frontend/scripts/beta-channel-guard.mjs`, `frontend/scripts/generate-tauri-latest-windows.mjs`, `frontend/scripts/verify-updater-signature.mjs`, `frontend/scripts/lib/minisign.mjs`, `frontend/scripts/prepare-tauri-windows-release-config.mjs`, `frontend/scripts/prepare-tauri-updater-config.mjs`, `frontend/scripts/lib/tauri-config.mjs` |
| **Subsystem**      | Tauri auto-updater — Boundary 6 of [SECURITY_THREAT_MODEL.md](../SECURITY_THREAT_MODEL.md)                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                               |
| **Verdict**        | **Security: PASS** — nothing in the trust chain, in three audits running. The one finding this audit raised (U-1, workflow script injection, **Low**) is fixed; two procedural/informational notes stay open. The v1.5 code changes came from the operational review in `todo.md`, not from this audit.                                                                                                                                                                                                                                                                                                                                                                                                                                                  |

Per-subsystem release audit of the update channel **"GitHub release →
signature-verified install"**. This is the one path that cannot be fixed after a
bad ship, so the audit focuses on: signature verification (no bypass), a
fail-closed build/sign pipeline (no unsigned or placeholder-keyed release), HTTPS
transport, downgrade protection, safe handling of the attacker-controllable
`latest.json` fields, and graceful failure. Method: static trace of the config,
the frontend check/install flow, and the CI signing workflow + config-prep
scripts.

## 1. Signature verification — no bypass (confirmed)

- The pubkey is **pinned** in the shipped config (minisign/Ed25519). Verification
  happens **natively in the Rust updater plugin** before an update is offered or
  installed — [updates.ts](../frontend/src/lib/updates.ts) never sees or handles
  a signature, so there is no JS-side path to skip it.
- The WebView holds **no updater plugin permissions at all**
  ([capabilities/default.json](../frontend/src-tauri/capabilities/default.json)
  grants nothing under `updater:`); the renderer reaches the updater only
  through **three** app-defined commands in
  [lib.rs](../frontend/src-tauri/src/lib.rs) — `check_for_update(channel)`,
  `download_pending_update(expectedVersion)` and
  `install_pending_update(expectedVersion)`. The `channel` argument accepts only
  the known names `stable` / `beta` (anything else is rejected) and
  `expectedVersion` is a version **to be matched**, never a URL or a path, so a
  compromised renderer can pick between two pinned endpoints but can never
  supply either.
- **The verified bytes never cross into the renderer.** `download_pending_update`
  verifies the signature inside `Update::download` and parks the package in Rust
  managed state (`DownloadedUpdate`); `install_pending_update` installs from
  there. `Update::install` does not re-check, so routing the package through JS
  would put the one unverified copy on the install path — the split into two
  commands (§5) was made without moving the bytes for exactly that reason.
- **The version pin is checked twice.** Every check replaces the pending slot and
  clears any downloaded package, so `take_expected_update` fails unless the slot
  still holds the version the prompt named; the downloaded package then carries
  its own `version` and is compared again at install, because a check landing
  between the two calls moves the slot on while the bytes stay behind. A stale or
  hostile prompt cannot install a build the user did not approve — it errors out.
- **One event flows the other way**, added in v1.7: `download_pending_update`
  emits `update://download-progress` (byte counts, throttled to whole percents)
  so the dialog can show a bar. It is outbound Rust → webview only, carries no
  handle to the update, and nothing in the install path reads it — a renderer
  that ignores, spoofs or never receives it still gets exactly the same
  signature-verified package. Its payload does reach the DOM, which §4 covers.
- A hijacked `latest.json` cannot cause code execution: it can point `url`
  anywhere, but the downloaded artifact is verified against the pinned pubkey, so
  a forged installer fails the Ed25519 check and the install aborts. Worst case
  is a bogus "update available" prompt whose install fails — a recoverable
  annoyance, not a compromise.

## 2. Fail-closed build & sign pipeline (confirmed)

The release **always** builds with `--config tauri.release.conf.json`
([windows-signed-release.yml](../.github/workflows/windows-signed-release.yml)),
generated by [prepare-tauri-windows-release-config.mjs](../frontend/scripts/prepare-tauri-windows-release-config.mjs)
→ `buildUpdaterPlugin()` in [lib/tauri-config.mjs](../frontend/scripts/lib/tauri-config.mjs):

- **Pubkey is `requireEnv('TAURI_UPDATER_PUBKEY')`** — an empty/missing key
  throws and aborts the build. A release therefore **cannot** ship with a
  placeholder or missing updater key (the analogue of the OAuth
  `invalid_client` placeholder trap, here closed).
- **Endpoints must contain ≥ 1 URL**, else the build throws.
- **`dangerousInsecureTransportProtocol`** (Tauri's flag to allow `http`
  endpoints) is **opt-in behind `TAURI_UPDATER_DANGEROUS_INSECURE_TRANSPORT_PROTOCOL === 'true'`**
  and is not set — transport is HTTPS-only (`github.com`).
- The workflow separately **throws if `TAURI_SIGNING_PRIVATE_KEY` is absent**
  ("Validate Tauri updater signing secrets"), so `.sig` artifacts are always
  produced.
- **The two halves of the key pair are compared** ("Verify updater signature
  against the shipped pubkey" →
  [verify-updater-signature.mjs](../frontend/scripts/verify-updater-signature.mjs),
  verifier in [lib/minisign.mjs](../frontend/scripts/lib/minisign.mjs)). Both
  inputs are read from the **generated artifacts** rather than from the env vars
  they came from: the pubkey out of `tauri.release.conf.json`, the signature out
  of the `latest.json` the updater will actually fetch, checked against the
  installer in `bundle/`. A manifest naming an installer this build did not
  produce fails the same step. Dependency-free on purpose — widening the supply
  chain of the one step whose job is to distrust the build would be the wrong
  trade.
- [generate-tauri-latest-windows.mjs](../frontend/scripts/generate-tauri-latest-windows.mjs)
  only selects a **signed** artifact (one with a sibling `.sig`), **throws on an
  empty signature**, **throws unless the artifact's name carries the version
  being released** (v1.8), and builds the download `url` with
  `encodeURIComponent`. The version check is the one that catches a manifest
  announcing this version while pointing at an older build — until v1.8 it was
  a preference in a score rather than a condition, so a previous release left in
  `bundle/` could win. Note that **the signature gate above cannot catch that**:
  it verifies the artifact the manifest names, and a stale artifact carries its
  own valid signature. The two checks answer different questions — "is this
  signed by our key" and "is this the build we are releasing".
- Defense in depth beyond the signature: a Sigstore **build-provenance
  attestation** ties the installer to the workflow + commit, and a published
  **SHA-256** checksum accompanies each installer.

**What "fail-closed" did not cover until 2026-08-27, recorded so the claim is
not read backwards.** Up to and including v1.4 this section verified that each
half of the key pair was _present_ — never that they _matched_.
`TAURI_SIGNING_PRIVATE_KEY` produces the `.sig` and `TAURI_UPDATER_PUBKEY` is
baked into the app, they are unrelated secrets, and a rotated or mistyped half
let the workflow finish green while every installation that took the release
lost its updater permanently: the app can only be repaired by an update it now
refuses to accept. The gate above closes that, and it is the one control here
that **has never run on a real release** — the verifier is proven against a
genuine `tauri signer sign` artifact (the test fixture) and the npm wiring is
smoked, but the happy path needs a full release build.

## 3. Config & transport hardening (confirmed)

- `bundle.windows.allowDowngrades: false` ([tauri.conf.json](../frontend/src-tauri/tauri.conf.json)).
- Updater traffic is native (Rust), so it is **not** gated by the WebView CSP —
  and correctly, the WebView `connect-src` stays loopback + `ipc:` only (no
  `github.com`), i.e. the update fetch does not widen the renderer's network
  surface.
- `installMode: currentUser` / `passive` is the deliberate elevation-free
  auto-update posture already recorded as residual **AR-2** in the threat model
  (a same-user attacker who could swap the installed binary is already out of
  scope). Not re-litigated here.
- **Update channels (stable / beta)** keep the endpoint set pinned. `stable`
  uses the endpoints baked into the release updater config (their single source
  of truth); `beta` swaps in the compile-time constant
  `https://github.com/TheVoxRox/mail/releases/download/beta/latest.json`
  (overridable only at **build** time via `TAURI_UPDATER_BETA_ENDPOINT` /
  `option_env!` — nothing at runtime can move it). Both endpoints are HTTPS
  `github.com`, and both manifests are verified against the **same pinned
  pubkey**, so switching channels never changes the trust anchor. The moving
  `beta` release is refreshed by
  [beta-channel.yml](../.github/workflows/beta-channel.yml), guarded against
  version regression by
  [beta-channel-guard.mjs](../frontend/scripts/beta-channel-guard.mjs); the
  manifest stays attacker-shapeable in principle, but exactly as in §1/§4 the
  installer signature remains the trust anchor.

## 4. Attacker-controlled `latest.json` fields — no XSS (confirmed)

The update prompt ([UpdatePromptDialog.svelte](../frontend/src/lib/components/UpdatePromptDialog.svelte))
renders **only** `version` / `currentVersion`, and only through svelte-i18n
message interpolation inside a `{...}` expression — text-escaped, never `{@html}`.
The `body` / `notes` field (the most free-form, attacker-shapeable field in a
hijacked `latest.json`) is **not rendered at all**. No mail-content-style
sanitizer is needed because no untrusted markup reaches the DOM.

**Download progress is the one remote-influenced value that reaches a style
attribute** ([UpdatePromptDialog.svelte](../frontend/src/lib/components/UpdatePromptDialog.svelte),
v1.7). The denominator is the server's `Content-Length` and the numerator is
bytes actually received, so a hostile host controls both. Neither is ever
interpolated as text: the shell only emits a total it has checked is `> 0`, the
dialog divides and rounds, clamps with `Math.min(100, …)`, and the result is a
finite number in `0..=100` before it becomes `aria-valuenow` and a `width`
percentage. An absurd `Content-Length` therefore yields a bar stuck near 0, not
a malformed style. Where no `Content-Length` arrives at all the shell sends
`null`, `aria-valuenow` is omitted and the bar renders indeterminate — the
correct ARIA for an unknown value rather than a guessed one.

The failure dialog ([UpdateFailureDialog.svelte](../frontend/src/lib/components/UpdateFailureDialog.svelte))
is the one place where remote-influenced text can reach the screen: the error
string bubbled out of the Rust commands can quote a manifest version (the
mismatch messages in `take_expected_update` and `install_pending_update` do). It
renders through `{message}` in a `<span>` — Svelte text interpolation, no
`{@html}` — so it is escaped on the same terms as the prompt.

## 5. Install sequence & failure handling (confirmed)

- **Startup check** ([bootstrap.ts](../frontend/src/lib/bootstrap.ts) →
  `checkForUpdateAndPrompt`) is fire-and-forget and **fails silently** (a
  `console.warn`, no dialog) — a transient network error or an
  as-yet-unpublished release must not throw an alarming dialog on every cold
  start (announced to screen-reader users). Gated on `PROD` + Tauri +
  `VITE_ENABLE_AUTO_UPDATE_CHECK`.
- **The install runs download → stop the backend → install, in that order**
  (`installPromptedUpdate` in [updates.ts](../frontend/src/lib/updates.ts)). The
  NSIS installer overwrites the sidecar launcher, its `app/` and the whole
  bundled JRE inside the install directory, and Windows will not overwrite a
  file a live process holds open; `install_inner` in tauri-plugin-updater ends
  the app with `std::process::exit(0)`, so the `beforeunload` hook that normally
  kills the sidecar never runs. The seam between the two commands is where the
  backend is stopped, which replaces that race with a sequence. Only the middle
  position is correct — stopping before the download leaves the app dead for the
  length of it — so the unit tests assert the **order**, not just the calls.
  Re-confirmed by mutation during this revision: dropping the stop fails two
  tests, moving it ahead of the download fails three, and both name the
  ordering test.
- **The dialog names which of the three steps is running** and shows a progress
  bar for the download (v1.7). Before that it held one static "installing" label
  for the whole sequence, including a multi-minute download of an installer that
  carries a JRE, with both buttons disabled and nothing announced. Phase changes
  and coarse percentage steps go through the app-wide polite live region rather
  than making the bar itself live, so a screen reader hears the transitions
  instead of a hundred percentages. Progress is advisory end to end: a shell that
  cannot deliver the event leaves the bar indeterminate and the install proceeds.
- A failed install puts the backend back through
  `bootstrap({ restartSidecar: true })` — a plain respawn is not enough, the new
  sidecar comes up on a fresh port with a fresh handshake key — and then surfaces
  the failure dialog.
- **Manual check** ([AboutSettings.svelte](../frontend/src/lib/components/settings/AboutSettings.svelte))
  surfaces a prominent failure UI with a fallback link to the releases page
  (`RELEASES_URL`).
- A per-version **dismissal** is persisted best-effort in `localStorage`
  (wrapped in try/catch for private-mode).
- Covered by [updates.test.ts](../frontend/src/lib/updates.test.ts): fail-silent
  startup, prompt-when-available, manual-failure-dialog, channel routing (stable
  default, stored beta preference), download/stop/install ordering, the
  backend restart after a failed install, and the guard that refuses a second
  run rather than starting a second download.
- **Not proven by any test:** that the installer really does overwrite
  `runtime/**` and `app/**` without a file-in-use failure. Only a real vN-1 → vN
  smoke shows that, and `CheckIfAppIsRunning` in the bundled NSIS template still
  guards only `${MAINBINARYNAME}.exe`, so a **manual** reinstall while the app
  runs (a path outside this flow) has nothing covering the sidecar. Tracked in
  `todo.md`, not claimed here.

## 6. Findings and informational notes

- **U-1 — script injection in both release workflows (Low, FIXED 2026-08-28).**
  The tag name was interpolated straight into `run:` blocks:
  [beta-channel.yml](../.github/workflows/beta-channel.yml) read
  `${{ github.event.release.tag_name }}` into a bash string,
  [windows-signed-release.yml](../.github/workflows/windows-signed-release.yml)
  did the same with `inputs['release-tag']` into a pwsh one. Git refname rules
  allow `` ` ``, `$`, `;`, `&`, `|` and quotes, so a crafted tag was executable
  code inside a job holding `contents: write` — including the updater signing
  secrets. Rated Low because creating that tag already requires write access to
  the repository, so it escalated an existing privilege rather than granting
  one; it was a **hardening** item, not a way in. Both workflows have been in
  `Code paths` since v1.2 and no revision before v1.5 looked at their injection
  surface.
  **Fix:** every value originating outside the workflow file now arrives through
  a step-level `env:` and is read as a variable — `${TAG_INPUT:-$RELEASE_EVENT_TAG}`
  in bash, `$env:RELEASE_TAG_INPUT` / `$env:REF_TYPE` / `$env:REF_NAME` in pwsh.
  The two boolean inputs (`inputs.force`, `inputs['skip-backend-tests']`) cannot
  carry a payload and were converted anyway, so that **no `${{ }}` remains inside
  any `run:` block in either file** — a reader checking this property does not
  have to judge each interpolation, and a new one is visible as an exception
  rather than as one more of a mixed set. Verified mechanically over both files
  after the change; the other repository workflows were scanned at the same time
  and two still interpolate, neither with attacker-shaped input: `ci.yml` passes
  commit SHAs (`github.event.pull_request.base.sha` / `github.event.before`, both
  40-hex from GitHub), and `vuln-scan.yml` passes the NVD API key — a
  log-hygiene item for a secret, not an injection, and outside this boundary.
- **Base-config updater block is a dev reference, not the release source of
  truth.** [tauri.conf.json](../frontend/src-tauri/tauri.conf.json) carries a
  hardcoded pubkey + `TheVoxRox/mail` endpoint; the shipped values come from the
  CI `TAURI_UPDATER_*` env via the release config. A bare `npm run tauri:build`
  would use the base values **and** (because `createUpdaterArtifacts` is unset
  there) produce **no `.sig`**, so such a build cannot be a valid release — but
  the guard against publishing one is **procedural** (RELEASE_CHECKLIST §3a),
  not enforced in code. Pubkeys are public by nature, so committing one leaks no
  secret.
- **`dangerousInsecureTransportProtocol`** is env-gated and unset; a future CI
  lint that asserts it is never `true` for a release would make the HTTPS-only
  guarantee explicit rather than default. Open since v1.0 (2026-07-09) — the
  signature gate added in v1.5 covers a different property (key-pair identity),
  so this note is not closed by it.

## 7. References

- [SECURITY_THREAT_MODEL.md](../SECURITY_THREAT_MODEL.md) — Boundary 6 STRIDE matrix + AR-2.
- [API_SURFACE_AUDIT.md](API_SURFACE_AUDIT.md) / [CONTENT_RENDERING_AUDIT.md](CONTENT_RENDERING_AUDIT.md) — companion boundary audits.
- [backend/SECURITY_RELEASE_CHECK.md](../backend/SECURITY_RELEASE_CHECK.md) — per-release security gate.

## 8. Change log

- **1.8** (2026-08-28) — the manifest generator's version match became a
  **condition** instead of a preference, and §2 says so. Carrying the released
  version in the artifact name was worth +10 in a score, which on a clean runner
  always won and anywhere else lost to a previous release left in `bundle/` —
  producing a `latest.json` that announced this version while pointing at that
  build. §2 also records why nothing else caught it: the signature gate added in
  v1.5 verifies the artifact the manifest **names**, and a stale artifact carries
  its own valid signature, so the two checks answer different questions. Proven
  by a new suite that runs the real script against a throwaway bundle directory
  (the script had none); removing the condition fails four of its eight tests.
  Trust chain untouched, verdict unchanged (**PASS**).
- **1.7** (2026-08-28) — the install now reports what it is doing, which adds the
  **first Rust → webview event** in this boundary and the **first
  remote-influenced number that reaches a style attribute**; both are recorded
  above rather than left to be noticed later. `update://download-progress`
  carries byte counts only, is outbound, throttled to whole percents, and is read
  by nothing in the install path — a renderer that never receives it gets the
  same signature-verified package (§1). The numbers it carries do reach the DOM,
  and §4 now states why that is safe: the shell only emits a total it has checked
  is `> 0`, the dialog divides, rounds and clamps to `0..=100`, and no value is
  interpolated as text into CSS. §5 records the phase labels and the progress bar
  themselves, including the decision to announce phases and coarse steps through
  the app-wide polite region instead of making the bar live. Trust chain
  untouched, verdict unchanged (**PASS**). The `Audited commit` stays `c6744a1`;
  the drift is recorded in [audit-freshness.json](audit-freshness.json).
- **1.6** (2026-08-28) — **U-1 fixed**, the finding v1.5 had raised one revision
  earlier. Both release workflows now take every outside value through a
  step-level `env:` and read it as a variable, so no `${{ }}` remains inside any
  `run:` block in either file — including the two boolean inputs, which could not
  carry a payload but were converted so the property is checkable by looking
  rather than by judging each site. Nothing else moved: same commands, same
  pinned pubkey, same manifest, same verdict (**PASS**). The `Audited commit`
  stays `c6744a1`; the drift these two files now carry is exactly what this entry
  describes, recorded in [audit-freshness.json](audit-freshness.json).
- **1.5** (2026-08-28) — re-verified against `c6744a1` and **corrected**, rather
  than acknowledged for a fourth time. The operational review recorded in
  `todo.md` found nothing in the trust chain but showed three places where this
  document had stopped describing the code: §1 enumerated **two** renderer
  commands where there are three (`download_pending_update`) and said the
  install command takes no arguments, when it has taken `expectedVersion` since
  v1.2; §2 called the pipeline "fail-closed" while it only checked that each
  half of the key pair was **present**, never that they matched; and the
  injection surface of the two release workflows had never been looked at,
  although both have been in `Code paths` since v1.2. §1 now describes all three
  commands, the double version pin and why the verified bytes stay Rust-side;
  §2 records the new key-pair gate together with what it did not cover before;
  §5 covers the download → stop-backend → install ordering; §6 records the
  injection as **U-1** (Low, open — it needs repo write access to reach). Every
  claim kept from v1.4 was re-read in the tree, not inferred from the diff:
  `requireEnv('TAURI_UPDATER_PUBKEY')` still throws on an empty key, the
  endpoint list still throws when empty, `dangerousInsecureTransportProtocol` is
  still env-gated and unset, `capabilities/default.json` still grants the
  WebView no `updater:` permission, `allowDowngrades` is still `false`, the
  channel map still sends `stable` to `None` and `beta` to the compile-time
  constant, and the manifest generator still takes only a signed artifact and
  throws on an empty `.sig`. `Code paths` gained
  `verify-updater-signature.mjs`, `lib/minisign.mjs` and
  `UpdateFailureDialog.svelte`. Verdict unchanged (**PASS**).

- **1.4** (2026-08-08) — re-verified against `5799e8b`, bringing B6 to the same standard as the other five audits re-checked the same day. The three drift commits are non-material (see 1.3), and the §2 pipeline claims were re-checked in code rather than inferred from them: `requireEnv('TAURI_UPDATER_PUBKEY')` still throws on an empty key, the endpoint list still throws when empty, `dangerousInsecureTransportProtocol` is still env-gated and unset so transport stays HTTPS-only, `capabilities/default.json` still grants the WebView no `updater:` permission, and the channel map still sends `stable` to `None` (config endpoints) and `beta` to the compile-time constant. Verdict unchanged (**PASS**).
- **1.3** (2026-08-08) — corrected the audited-commit anchor; no re-audit, no
  verdict change. `e2b8d8d` is not an object in this repository
  (`git cat-file -t` fails) — it was the pre-squash SHA of the branch behind
  #144, so the row pointed at a code state nobody could check out, which is
  the one failure the row exists to prevent. `npm run check:audits` surfaced
  it on its first run. The anchor is `3162e6a`, the squash commit that
  carries every change the v1.2 entry below describes _and_ that revision of
  this document: `beta-channel.yml` and `beta-channel-guard.mjs` added,
  `capabilities/default.json` stripped of updater permissions, the
  `check_for_update` / `install_pending_update` commands added to `lib.rs`,
  and `@tauri-apps/plugin-updater` dropped from `package.json`. The three
  commits touching `Code paths` since then were read and are non-material to
  the trust chain (see `docs/audit-freshness.json`).
- **1.2** (2026-07-11) — re-audited for release channels (stable/beta):
  updater plugin permissions dropped from the WebView capability set entirely;
  check/install moved to app-defined shell commands
  (`check_for_update(channel)` / `install_pending_update`) that accept only
  known channel names, never URLs; beta endpoint recorded as a compile-time
  constant with the same pinned-pubkey trust anchor; beta manifest refresh
  workflow + version-regression guard recorded; `@tauri-apps/plugin-updater`
  npm dependency removed (JS guest bindings no longer used). Verdict unchanged
  (**PASS**).
- **1.1** (2026-07-09) — added the audited-commit header row (`d55b753`,
  claims re-verified during the truing pass). No content change.
- **1.0** (2026-07-09) — initial audit.
