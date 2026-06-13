# VoxRox Mail — Threat Model

| | |
|---|---|
| **Version** | 1.2 |
| **Last revised** | 2026-06-13 |
| **Applies to** | VoxRox Mail V0.1.0 |
| **Status** | Active — read by PR reviewers when changes cross a trust boundary |

Single-source threat model for the VoxRox Mail desktop application. The
goal is to make every security-relevant decision in the codebase
traceable to (a) the asset it protects, (b) the threat it counters and
(c) the mitigation code path. Reviewers use this document as a
checklist when auditing changes that touch authentication, IPC, mail
content rendering, or the bundled sidecar.

V0.1.0 scope: single-user desktop client that talks to one or more
remote IMAP/SMTP servers (optionally via provider OAuth) and runs as a
Tauri WebView + bundled Spring Boot sidecar on Windows. macOS and Linux
builds inherit the same model but are not part of the V0.1.0 release.

## 1. Adversary model

Capabilities the threat model defends against:

| Adversary | Capability | Defended? |
|---|---|---|
| **Passive network observer** | Reads cleartext traffic on the user's LAN / hostile Wi-Fi. | Yes — TLS for all external traffic; loopback only for sidecar. |
| **Active network attacker (MITM)** | Modifies / spoofs IMAP/SMTP/OAuth traffic. | Yes — certificate validation, OAuth state nonce + PKCE, SSL guard for OAuth credentials. |
| **Unprivileged local process (same user)** | Reads the loopback port; tries to talk to the sidecar. | Yes — `X-API-KEY` filter + per-process random key, no TLS needed inside the user profile. |
| **Hostile mail content** | Email body with `<script>`, tracking pixels, `formaction` injection, SVG with `onload`. | Yes — `content-sanitizer.ts` is the chokepoint; 29 unit tests cover the matrix. |
| **Hostile updater feed** | Hijacks `latest.json` or installer download URL. | Yes — Tauri Ed25519 signature + Authenticode signature on the installer. |
| **Compromised dependency** | Malicious upstream package gets pulled into the build. | Partial — CodeQL + Dependabot + Trivy (planned via `vuln-scan.yml`). Build-time supply chain (signing key, CI runner) is out of scope. |

Capabilities **out of scope** (explicitly):

- Kernel / Administrator-level adversary on the same machine — at that level the encryption key, JVM memory, and Tauri IPC handles are observable.
- Adversary with physical access who can dump RAM or attach a debugger.
- Multi-profile Windows where two users share a single per-user install (the threat model assumes per-machine install + separate `%LOCALAPPDATA%` per profile).
- Compromise of the Tauri Ed25519 signing key or the Anthropic / GitHub Actions runner used to build releases — this is a release-engineering procedure, not an in-app mitigation.

## 2. Assets

Severity uses C-I-A on a 4-step scale: Critical / High / Medium / Low.

| Asset | Where it lives | C | I | A |
|---|---|---|---|---|
| IMAP/SMTP passwords | Encrypted in `account_credentials.secret_encrypted` (AES/GCM via [CryptoService](backend/src/main/java/org/voxrox/mailbackend/core/security/CryptoService.java)) | **Crit** | High | Med |
| OAuth refresh tokens (Google, Microsoft) | Same column, same encryption | **Crit** | High | Med |
| OAuth access tokens (in-memory) | [TokenCache](backend/src/main/java/org/voxrox/mailbackend/feature/auth/service/TokenCache.java) — process memory only | High | Med | Low |
| Internal API key (sidecar ↔ frontend) | In-memory only, generated per JVM start; written to `session.json` only on local filesystem | High | High | Med |
| Crypto root key + salt | `crypto.bin` (Windows: DPAPI-protected, USER scope) + `crypto.fingerprint` in user data dir | **Crit** | **Crit** | High |
| User mail bodies & attachments | SQLite DB + temp files under user data dir; not encrypted at rest (accepted risk AR-1, §5) | Med | Med | Med |
| Contact list (addresses, names) | SQLite DB | Med | Med | Med |
| Tauri updater public key fingerprint | Hardcoded at build time | — | **Crit** | Low |

The "user data dir" is `%LOCALAPPDATA%\VoxRox\Mail` on Windows (or
`~/.voxrox/mail` for the bare-mvn dev fallback). It inherits the user
profile's filesystem ACLs; the threat model assumes those are intact
(no shared-machine without per-user profiles).

## 3. Trust boundaries

```mermaid
flowchart TB
    subgraph EXT["External network"]
        MAIL[Mail providers<br/>Gmail · Outlook · Seznam<br/>IMAPS · SMTPS · HTTPS]
        OAUTH[OAuth providers]
        UPD[GitHub Releases<br/>latest.json + installer]
    end

    subgraph BROWSER["External OS browser"]
        BR[Default browser<br/>OAuth redirect flow]
    end

    subgraph DESKTOP["VoxRox Mail desktop app"]
        SIDECAR["<b>Sidecar JVM</b><br/>Spring Boot · 127.0.0.1<br/>X-API-KEY filter"]
        TAURI["<b>Tauri shell</b><br/>WebView2 + plugin-http<br/>Minimal capabilities"]
        SPA["<b>SvelteKit SPA</b><br/>CSP-locked<br/>HTML sanitized"]
    end

    subgraph FS["Local filesystem"]
        DB[(SQLite DB · attachments)]
        CRYPTO[(crypto.bin + fingerprint)]
        SESSION[(session.json + .ready)]
    end

    MAIL <-->|TLS · XOAUTH2 / PASSWORD| SIDECAR
    OAUTH <-->|HTTPS · PKCE · state| BR
    BR <-->|loopback redirect URI| SIDECAR
    UPD <-->|HTTPS · Ed25519 + Authenticode| TAURI

    SIDECAR <-->|HTTP loopback · X-API-KEY| TAURI
    TAURI <-->|IPC commands · capabilities| SPA

    SIDECAR --- CRYPTO
    SIDECAR --- DB
    SIDECAR --- SESSION
    TAURI --- SESSION

    classDef ext fill:#fee,stroke:#c00,color:#000
    classDef app fill:#cef,stroke:#06f,color:#000
    classDef fs fill:#efe,stroke:#0a0,color:#000
    class MAIL,OAUTH,UPD ext
    class SIDECAR,TAURI,SPA app
    class DB,CRYPTO,SESSION fs
```

Trust boundary crossings (numbered, threats indexed below):

1. **External mail server ↔ sidecar** — IMAPS/SMTPS over TLS; XOAUTH2 SASL or plaintext PASSWORD-on-TLS.
2. **External OAuth provider ↔ external browser ↔ sidecar** — loopback redirect URI (`http://localhost/login/oauth2/code/<provider>`).
3. **Sidecar ↔ Tauri WebView (loopback HTTP)** — every request carries `X-API-KEY` derived from per-process random.
4. **Tauri WebView ↔ SvelteKit SPA** — IPC commands restricted by `frontend/src-tauri/capabilities/default.json`.
5. **Filesystem ↔ sidecar / Tauri** — encrypted credentials, plaintext DB, session handshake files.
6. **Tauri updater ↔ GitHub releases** — signed `latest.json` + signed installer.

## 4. STRIDE per boundary

Severity uses a CVSS-light rubric:

- **Critical** — direct asset compromise (credential leak, RCE, signing-key bypass)
- **High** — credential exposure under specific preconditions, persistent integrity loss
- **Medium** — transient data exposure, DoS recoverable by restart
- **Low** — UX degradation, log noise, theoretical exposure

### Boundary 1: external mail server

| ST | Threat | Vector | Severity | Mitigation |
|---|---|---|---|---|
| **S** | Spoofed mail server | Hostile DNS / MITM steers user to a fake IMAP server | High | TLS certificate validation (default JavaMail behaviour); the OAuth defense-in-depth `requireSslForOAuth2` in [SmtpTransportFactory.java](backend/src/main/java/org/voxrox/mailbackend/feature/mail/service/SmtpTransportFactory.java) blocks the access token from ever travelling over a plaintext SMTP socket |
| **T** | Mail tampered in transit | Active MITM modifies fetched mail | Medium | TLS as above; UID + UIDVALIDITY cross-check between fetches detects mailbox-level desync |
| **I** | Plaintext credentials over a downgraded connection | Forced STARTTLS strip | **Critical** | SSL/TLS enforcement; the IMAP probe in [MailConnectionProbe.java](backend/src/main/java/org/voxrox/mailbackend/feature/mail/service/MailConnectionProbe.java) rejects OAuth2 + non-SSL combination *before* opening the socket |
| **D** | Slow / unresponsive server hangs sidecar | Bad server / saturated link | Low | Per-protocol read & connection timeouts (60 s / 30 s for IMAP, 10 s / 15 s for SMTP) configured through `mail.imap.*` / `mail.smtp.*` properties — fixed in Phase 6.15 (the values used to be ignored by JavaMail) |
| **R / E** | — | Not applicable: read-only RX from this side. | — | — |

### Boundary 2: OAuth handshake

| ST | Threat | Vector | Severity | Mitigation |
|---|---|---|---|---|
| **S** | Stolen authorization code | Attacker redeems an intercepted code | High | PKCE (`code_challenge_method=S256`) for both Google and Microsoft (Spring Security default); state nonce |
| **T** | Phishing app posing as VoxRox Mail | Hostile redirect endpoint | Medium | Verified publisher (planned, blocker for Microsoft enterprise tenants — see todo:280); Google brand-verified consent screen; loopback redirect URI prevents redirect to a third-party host |
| **R** | User denies authorising the app | — | Low | Provider's own consent log + on-device `oauth_*` audit entries in `mail.log` |
| **I** | Refresh token leaks from disk | DB exfiltration | **Critical** | AES/GCM encryption with per-account derived key (PBKDF2, 600k iterations) — same path as for passwords |
| **D** | Provider revokes the registration | App-level outage | Medium | Explicit `requires_reauth` error code; the UI re-shows the OAuth wizard ([OAuth2TokenService](backend/src/main/java/org/voxrox/mailbackend/feature/auth/service/OAuth2TokenService.java)) |
| **E** | Excessive scopes granted | Backend asks for more than IMAP/SMTP | Medium | Scopes hard-coded per provider; no in-app scope escalation path |

### Boundary 3: sidecar HTTP API

| ST | Threat | Vector | Severity | Mitigation |
|---|---|---|---|---|
| **S** | Same-machine process spoofs the frontend | Sibling process sends HTTP requests to the sidecar | High | `ApiKeyFilter` rejects any request without `X-API-KEY`; the key lives in `session.json` which only the user profile can read (filesystem ACLs); key is regenerated on every JVM start |
| **T** | Tampered response (no TLS for loopback) | Local TLS strip | Low | Process-only loopback (127.0.0.1) and per-process API key make TLS unnecessary; CSP `connect-src` is locked to `http://localhost:* http://127.0.0.1:*` in [tauri.conf.json](frontend/src-tauri/tauri.conf.json) |
| **I** | SSRF / open redirect from the SPA | Malicious base URL persuades the SPA to leak the API key | High | `validateBackendBaseUrl` in `frontend/src/lib/api/session.ts` rejects any base URL that is not a numeric loopback host; the API key never goes anywhere else |
| **D** | A neighbouring process saturates the port | Port squatting / DoS | Low | Port is OS-assigned; failure leads to a clear `SessionLoadError` in the boot screen, not a hang (Phase 6.15) |
| **R / E** | — | The sidecar runs as the user; no privilege boundary to elevate across. | — | — |

### Boundary 4: WebView ↔ SPA

| ST | Threat | Vector | Severity | Mitigation |
|---|---|---|---|---|
| **T / I** | XSS in mail body | Malicious HTML in an email | **Critical** | [content-sanitizer.ts](frontend/src/lib/mail/content-sanitizer.ts) strips `<script>`, inline event handlers, `javascript:` / `data:` URLs (except whitelisted inline images), SVG `onload`, `formaction`, `srcdoc`; 29 unit tests cover the matrix (R3 audit). Defense-in-depth: even a missed vector cannot execute, because the body renders inside a fully sandboxed iframe (`sandbox=""`, no `allow-scripts`) carrying its own `default-src 'none'` CSP ([MessageContent.svelte](frontend/src/lib/components/message-detail/MessageContent.svelte)) |
| **E** | Untrusted page invokes a Tauri command | Hostile content reaches the IPC bridge | High | Capabilities zeroed out in [capabilities/default.json](frontend/src-tauri/capabilities/default.json) to the minimum needed (`core:path:default`, `core:window:default`, `shell:allow-open`, `updater:allow-check`, `updater:allow-download-and-install`, plus the explicit `fs:*` / `http:*` allow lists and `shell:allow-spawn` / `shell:allow-kill` constrained to the `binaries/mail` sidecar with `args: false`). [lib.rs](frontend/src-tauri/src/lib.rs) registers **no** hand-written `#[tauri::command]` and **no** `invoke_handler` — the entire native surface is official plugins bound by these capabilities, so there is zero bespoke IPC to audit. The in-app HTTP plugin is loopback-only (`http:default` allows `localhost`/`127.0.0.1` only) and built without a cookie jar |
| **I** | Inline images exfiltrate via tracking pixels | Remote image as user fingerprint | Medium | Remote images are unconditionally stripped by [content-sanitizer.ts](frontend/src/lib/mail/content-sanitizer.ts) — only inline `data:image/*;base64` survives `isInlineImage`; a remote `<img>` loses its `src` and is then removed. Reinforced by the body iframe's own `img-src data:` CSP and `sandbox=""` ([MessageContent.svelte](frontend/src/lib/components/message-detail/MessageContent.svelte)). There is no remote-image-loading toggle, so there is no opt-in exfil path |
| **S / D / R** | — | Sandboxed Webview; no spoofing path from a single SPA origin. | — | — |

### Boundary 5: local filesystem

| ST | Threat | Vector | Severity | Mitigation |
|---|---|---|---|---|
| **T** | crypto.bin tampered | An attacker who can write to the user profile flips bytes | **Critical** | Fingerprint file checked on every start ([StorageContextInitializer.java](backend/src/main/java/org/voxrox/mailbackend/core/init/StorageContextInitializer.java)); mismatch hard-stops the app. On Windows a tampered DPAPI blob also fails closed at `unprotect` (boot aborts, never falls back to plaintext) |
| **I** | crypto.bin copied off-machine to decrypt credentials | A backup tool or attacker grabs the whole data dir | High | Windows: `crypto.bin` is DPAPI-protected in USER scope + app entropy ([WindowsDpapiSecretStore](backend/src/main/java/org/voxrox/mailbackend/core/security/secret/WindowsDpapiSecretStore.java)) — a copy under another user/machine cannot be unprotected, so the AES/GCM credentials column stays sealed. Mail bodies in `mail.db` remain plaintext (DB is not encrypted at rest) |
| **I** | Other-user read on multi-profile Windows | Shared `%LOCALAPPDATA%` (rare) | High | Out of scope — the threat model assumes per-user profiles with default ACLs |
| **D** | Disk full, missing permissions | Storage failure | Medium | Pre-migration backup gate; clear error state in the boot screen ("backup-pre-v*" snapshot exists) |
| **S / R / E** | — | Filesystem is the user; no spoofing primitive. | — | — |

### Boundary 6: Tauri updater

| ST | Threat | Vector | Severity | Mitigation |
|---|---|---|---|---|
| **T** | Tampered installer | Modified `voxrox-mail-*-setup.exe` served from a hostile host | **Critical** | Tauri Ed25519 signature verification (public key bundled in the running app); Authenticode signature on the installer for OS-level reputation; `latest.json` over HTTPS |
| **E** | Malicious package replaces the sidecar | Hostile installer with privilege | **Critical** | Installer is signed; sidecar binary lives in the install dir, which on `installMode: perMachine` requires elevation to write |
| **D** | Bad release published | Updater fetches a broken build | Medium | `allowDowngrades: false` plus the manual override path in OPERATIONS.md |
| **S / I / R** | — | Update channel is signed and observable. | — | — |

## 5. Accepted residual risks

Risks consciously accepted for a given release rather than mitigated in
code. Each is re-evaluated per release; an entry leaving this table means
either the risk was closed in code or the product decided it is no longer
acceptable.

| # | Risk | Residual exposure | Why accepted (V0.1.0) | Mitigation / upgrade path |
|---|---|---|---|---|
| **AR-1** | Mail DB not encrypted at rest | An adversary with **offline access to the powered-off disk** (lost/stolen device, a copied backup of the data dir) can read all cached mail bodies, subjects, senders and contacts straight from `db/mail.db`. Credentials and OAuth tokens stay sealed (DPAPI). The same-user-while-logged-in adversary is already out of scope (§1). | Column-level encryption is incompatible with the FTS5 search index + `ORDER BY` over subject/sender/received_at ([V1__init.sql](backend/src/main/resources/db/migration/V1__init.sql)); whole-DB encryption (SQLCipher) was deferred to keep the audited SQLite core and the release scope stable. The asset is rated Med/Med/Med (§2). | **Now:** OS full-disk encryption (BitLocker) + per-user filesystem ACLs — documented for end users in [PRIVACY.md](PRIVACY.md) and the end-user README, and recorded as a decision in `todo.md`. **Upgrade path:** SQLCipher via the drop-in `Willena/sqlite-jdbc-crypt` fork (Apache 2.0, native libs bundled per OS), with the DB key sealed by the existing `SecretStore`/DPAPI — this raises mail bodies to the *same* at-rest protection credentials already have, and keeps schema, FTS5 and the sync core unchanged. |

## 6. Review checklist for PR authors

When touching code that crosses one of the boundaries above:

- [ ] If the change reads or writes credentials, it goes through `CryptoService` and never logs the plaintext.
- [ ] If the change adds a new IPC command or Tauri permission, it appears in `capabilities/default.json` with a justification.
- [ ] If the change renders user-supplied HTML, it runs through `sanitizeMailHtml()` ([content-sanitizer.ts](frontend/src/lib/mail/content-sanitizer.ts)) **and** is rendered only inside the `sandbox=""` body iframe (or an equivalent pure transform).
- [ ] If the change adds an outbound network call from the SPA, the host appears in the CSP `connect-src` allow list and there is a reason it cannot use the existing loopback API.
- [ ] If the change touches the OAuth flow, redirect URIs in `application.properties` match the provider registration verbatim.
- [ ] If the change touches the boot / handshake path, stale `.ready` and `session.json` are cleared before the new sidecar starts.

## 7. Change log

| Version | Date | Summary |
|---|---|---|
| 1.0 | 2026-06-02 | Initial document — V0.1.0 baseline; covers 6 boundaries × STRIDE matrix with CVSS-light severity; Mermaid diagram; adversary model. |
| 1.1 | 2026-06-07 | Added §5 "Accepted residual risks": formalized the plaintext mail-DB-at-rest decision (AR-1) with BitLocker as the standing mitigation and SQLCipher (drop-in `Willena/sqlite-jdbc-crypt`, DB key under DPAPI) as the documented upgrade path. |
| 1.2 | 2026-06-13 | Tauri capability/CSP/IPC audit — trued §4 Boundary 4 to the shipping code: documented the sidecar-scoped `shell:allow-spawn`/`shell:allow-kill` (`args: false`) and the zero-custom-command finding in [lib.rs](frontend/src-tauri/src/lib.rs); corrected the tracking-pixel mitigation (remote images are unconditionally stripped — the previously documented per-sender "UI toggle" does not exist) and recorded the `sandbox=""` body-iframe + per-frame CSP as the defense-in-depth layer; fixed the stale `sanitizeMessageHtml()` reference to `sanitizeMailHtml()`. No code change — posture confirmed strong; optional global-CSP tightening (`script-src 'self'`, drop unused `customprotocol:`) deferred to the release smoke. |

## 8. References

- [SECURITY.md](SECURITY.md) — disclosure process for vulnerabilities.
- [backend/SECURITY_RELEASE_CHECK.md](backend/SECURITY_RELEASE_CHECK.md) — per-release security audit checklist.
- [PRIVACY.md](PRIVACY.md) / [PRIVACY.en.md](PRIVACY.en.md) — what data is stored where.
- [OPERATIONS.md](backend/OPERATIONS.md) — recovery procedures (backup restore, crypto rotation).
- [SECURITY_RELEASE_CHECK.md](backend/SECURITY_RELEASE_CHECK.md) — pre-release security gate.
