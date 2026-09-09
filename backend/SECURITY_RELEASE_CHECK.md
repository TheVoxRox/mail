# Security release check

|                |                                                                                                                                                                                             |
| -------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **Version**    | 2.8                                                                                                                                                                                         |
| **Date**       | 2026-09-09                                                                                                                                                                                  |
| **Applies to** | VoxRox Mail V0.1.0                                                                                                                                                                          |
| **Role**       | Cross-cutting pre-release security gate: verdict index for the per-subsystem audits + the primary record of the cross-cutting checks (secret/artifact scan, log hygiene, dependency audits) |

The detailed claims of the individual audits live **exclusively** in the audit
documents (`docs/*_AUDIT.md`, process in
[docs/AUDIT_GUIDE.md](../docs/AUDIT_GUIDE.md)); this file does not duplicate
them. The cross-cutting checks below (secret scan, log hygiene, cargo audit)
are the other way round — their primary record is here, and each section is a
dated snapshot of one specific run.

## Verdict index for the per-subsystem audits

| Boundary                | Audit                                                            | Tier    | Version | Date       | Audited commit | Verdict                                             |
| ----------------------- | ---------------------------------------------------------------- | ------- | ------- | ---------- | -------------- | --------------------------------------------------- |
| B1 external mail server | [IMAP_SMTP_AUDIT.md](../docs/IMAP_SMTP_AUDIT.md)                 | full    | 1.6     | 2026-09-06 | `02ff962`      | **PASS** — B1-1 and B1-2 (Medium DoS) fixed in code |
| B2 OAuth handshake      | [OAUTH_AUDIT.md](../docs/OAUTH_AUDIT.md)                         | focused | 1.2     | 2026-08-15 | `cad05cb`      | **PASS** — re-verified after the guard on scopes    |
| B3 sidecar HTTP API     | [API_SURFACE_AUDIT.md](../docs/API_SURFACE_AUDIT.md)             | full    | 1.4     | 2026-08-08 | `d45e253`      | **PASS** — A1 (Low, defense-in-depth) fixed         |
| B4 WebView ↔ SPA        | [CONTENT_RENDERING_AUDIT.md](../docs/CONTENT_RENDERING_AUDIT.md) | full    | 1.9     | 2026-09-03 | `0c1b61b`      | **PASS** — F1/F2/F3/F4/F5 fixed                     |
| B5 crypto + filesystem  | [CRYPTO_STORAGE_AUDIT.md](../docs/CRYPTO_STORAGE_AUDIT.md)       | focused | 1.1     | 2026-08-08 | `cad05cb`      | **PASS** — no code change                           |
| B6 Tauri updater        | [UPDATER_AUDIT.md](../docs/UPDATER_AUDIT.md)                     | full    | 1.12    | 2026-09-07 | `0165149`      | **PASS** — U-1 fixed, no open findings              |

The verdict + link is also carried by the
[SECURITY_THREAT_MODEL.md](../SECURITY_THREAT_MODEL.md) §7 change log; updating
an audit changes (a) the audit itself, (b) the row here, (c) the one-line entry
in the threat model's change log.

## Secret & artifact scan — snapshot 2026-04-30 (updated 2026-05-08)

Dated record of the pre-release secret/artifact audit; the specific numbers
(test counts, SBOM components) hold as of the run date. The local secret audit
was repeated after the Tauri release build on 2026-05-08.

### Verified

- [x] Backend build passed: `mvn package`
- [x] Tests passed: `Tests run: 568, Failures: 0, Errors: 0, Skipped: 0`
- [x] SBOM generated through the CycloneDX Maven plugin:
  - `target/bom.xml`
  - `target/bom.json`
- [x] The SBOM contains 155 components.
- [x] The Maven dependency tree resolves without conflicts: `mvn dependency:tree`.
- [x] The resulting JAR does not contain `application-it.properties`.
- [x] The resulting JAR does not contain `.env`.
- [x] The resulting JAR does not contain `session.json`.
- [x] The resulting JAR does not contain `crypto.bin`.
- [x] A quick binary scan of the resulting JAR found none of:
  - `MAIL_CRYPTO_KEY=`
  - `MAIL_CRYPTO_SALT=`
  - `test-api-key`
  - `dev-api-key`
  - `dummy-secret`
  - `google-client-secret`
  - `internal_api_key`
- [x] `.env.example` leaves `MAIL_CRYPTO_KEY` and `MAIL_CRYPTO_SALT` empty.
- [x] The production `application.properties` refers to env variables and carries no concrete OAuth secret.
- [x] Neither the current Tauri sidecar app JAR nor the launcher config carries the quick secret patterns or `session.json` / `crypto.bin`.
- [x] The current local `backend/.env` is gitignored and has only local variables filled in; the values are not written into the audit log.
- [x] The root `.gitignore` gained global safety nets for `.env`, local logs/DB and signing key/cert files (`*.key`, `*.pem`, `*.p12`, `*.pfx`), with an explicit exception for template/test env files.
- [x] Tauri desktop mode does not forward `MAIL_CRYPTO_KEY`/`MAIL_CRYPTO_SALT` from `backend/.env` by default; the release/dev sidecar uses the local `crypto.bin` bootstrap, and explicit env crypto is opt-in.
- [x] Desktop bootstrap mode repairs a stale `crypto.fingerprint` from `crypto.bin`. The internal handshake API key is in-memory only (generated on every sidecar start, written to `session.json`, never persisted in the DB), so rotating crypto material does not affect it. Unreadable user encrypted credentials are not overwritten; the account is flagged for a fresh login.
- [x] A direct scan of the current release artifacts against the filled-in values from `backend/.env` found no match:
  - `backend/target/mail-backend-0.1.0.jar`
  - `frontend/src-tauri/target/release/app.exe`
  - `frontend/src-tauri/target/release/mail.exe`
  - `frontend/src-tauri/target/release/bundle/nsis/Mail_0.1.0_x64-setup.exe`
  - `frontend/src-tauri/target/release/bundle/msi/Mail_0.1.0_x64_en-US.msi`
- [x] A direct scan of the text files that `git ls-files --others --exclude-standard` would allow to be added found no filled-in value from `backend/.env`.
- [x] Frontend `npm audit --json` reports 0 vulnerabilities after the lockfile hardening.
- [x] Frontend dependency hardening:
  - `@sveltejs/kit -> cookie` override to `0.7.2` because of GHSA-pxg6-pf52-xh8x.
  - `postcss` override to `8.5.12` because of GHSA-qx2v-qp2m-jg93.
  - `svelte-i18n -> esbuild` override to `0.28.0` because of GHSA-67mh-4wv8-2f99.
- [x] Frontend verification after the hardening: `npm run check`, `npm run build`, `npm ls svelte-i18n esbuild postcss cookie`.

### Originally open items (all closed)

- [x] Run a vulnerability scan over `target/bom.json`, or over the Maven dependency tree directly, in a tool with a current CVE database.
  - 2026-05-08: OWASP Dependency-Check did not finish locally because of NVD API 429 without an API key; the SBOM `target/bom.json` was regenerated.
  - Resolved by the scheduled `vuln-scan.yml` workflow (OWASP Dependency-Check with `NVD_API_KEY` + retry on flaky NVD, Trivy SBOM gate, cargo/npm audit); the box was ticked during the doc-truing pass on 2026-07-09.
- [x] Decide whether OWASP Dependency-Check joins the CI build. Recommendation: wire it into the CI/release pipeline with an NVD API key rather than as a mandatory local build step.
  - Decided and wired in exactly per the recommendation: scheduled `vuln-scan.yml` (Mon-Fri 04:00 UTC) with an NVD API key, not a mandatory local step; the box was ticked during the doc-truing pass on 2026-07-09.
- [x] After the current local installer/sidecar packaging, repeat the secret scan over the distributed bundle, not only over the backend JAR and the current `src-tauri/binaries/app`.
- [x] Before the first GitHub push, rotate the local Google OAuth secret found in the ignored `backend/.env`, because it had already been used in local development.
  - The rotation happened — confirmed by the owner on 2026-07-09 during the doc-truing pass. A gitleaks scan of the git history reported 0 leaks, so this was a defense-in-depth measure.

### Commands used

```powershell
mvn -Dmaven.repo.local=C:\dev\java\mail-backend\.m2repo -Dapp.data-dir=C:\dev\java\mail-backend\target\test-data package
mvn -Dmaven.repo.local=C:\dev\java\mail-backend\.m2repo -Dapp.data-dir=C:\dev\java\mail-backend\target\test-data -DskipTests cyclonedx:makeAggregateBom
mvn -Dmaven.repo.local=.m2repo -Dapp.data-dir=target/test-data -DskipTests org.owasp:dependency-check-maven:check
mvn -Dmaven.repo.local=C:\dev\java\mail-backend\.m2repo dependency:tree
jar tf target\mail-backend-0.1.0.jar
npm audit --json
npm run check
npm run build
npm ls svelte-i18n esbuild postcss cookie
```

## Log hygiene audit (2026-06-13)

A static review of both log streams (the backend's `mail.log` + `audit.log`,
the frontend's `tauri-plugin-log`) for leaks of credentials, OAuth tokens,
message bodies/subjects and unmasked PII.

- [x] Production log level `INFO` (`src/main/resources/application.properties`); per the header of `application-dev.properties`, the dev profile with `DEBUG`/`show-sql` does not reach the release JAR.
- [x] No JavaMail debug flag (`session.setDebug` / `mail.debug`) — the IMAP/SMTP protocol, including AUTH lines and bodies, is not dumped.
- [x] Passwords, OAuth access/refresh tokens and the internal API key are not logged; the token refresh (`OAuth2TokenService`) logs only a masked email, the expiry and the scope.
- [x] Neither message bodies nor subjects are logged — the single occurrence of the word "Body" (`MailContentService` ~ line 56) logs `uid`+`folder`, not content.
- [x] All 7 sites that log an email go through `LogMasker.maskEmail()` / `lazyEmail()`; `AuditLog` has explicit rules (no tokens, emails masked only) and writes to a separate `audit.log`.
- [x] No Lombok `@Data` / `@ToString` in the codebase (0 occurrences) → no auto-toString leak through an entity.
- [x] Diagnostics carry neither content nor unmasked PII: `ClientBootDiagnosticsRequest` = timings only, `DiagnosticDumpService` = masked email + message counts.
- [x] The frontend has no console→file bridge (no `attachConsole`); `errorReporting` / `clientErrors.ts` posts only to loopback with `X-API-KEY`, the payload is truncated to 4000 characters, and there is no external egress.
- [x] **Fixed:** `AccountEntity.toString()` and `AccountCredentialEntity.toString()` now mask email/username through `LogMasker` (previously unmasked — a latent leak had the entity surfaced in a Hibernate exception or a future log statement; no current caller was found). Verified with `mvn spotless:apply compile`.

Residue / notes:

- The backend sink `/internal/client-errors` does **not exist** yet (the frontend self-disables on HTTP 404); once it does, it must log bounded and without echoing PII/stacks into `audit.log`.
- `LogMasker.maskEmail` keeps the full domain (`j***k@seznam.cz`) — a deliberate trade-off for debugging. `AccountEntity.toString` keeps `displayName`/`accountName` (user labels, not a unique identifier).

## Log hygiene re-audit (2026-07-10)

A re-audit of both log streams against `2a1c865` (main after the B1-1 fix #139
and the hostile-content harness #141). Enumeration (reproducible):
287 log sites in 62 files (`rg -c "log\.(trace|debug|info|warn|error)\("
backend/src/main/java`), 68 AuditLog calls in 21 files
(`rg "AuditLog\.(success|failure|critical)\("`), 68 callers of
`LogMasker.maskEmail/lazyEmail` in 24 files.

- [x] **CWE-117 addressed structurally:** `logback-spring.xml` switches `%m`/`%msg`/`%message` to `CrlfSafeMessageConverter` (CR/LF strip) — this holds for every appender including `audit.log`, so attacker-influenced values (IMAP folder names, URIs, exception texts) cannot forge log lines. A chokepoint, not per-site discipline.
- [x] Prod level INFO (`org.hibernate.SQL=ERROR`), dev DEBUG only under the dev profile. Rotation: `mail.log` 10 MB/7 days/100 MB cap; `audit.log` 10 MB/365 days/500 MB cap, `additivity=false`. Both under `${app.data-dir}/logs` (private ACL of the data directory — see the B5 audit).
- [x] No `session.setDebug`/`mail.debug` (0 occurrences) — the IMAP/SMTP protocol, including AUTH and bodies, is not dumped.
- [x] Message content does not reach the log: search logs only the **character count** of the query (`MailReadController`), the remote-image allowlist deliberately does not log the sender ("allowed for a sender on account {}"), the B1-1 oversize path logs only uid + limit, and `MimePartExtractor` logs only cid/limits. Entity toString in log statements: 0 occurrences (multiline grep).
- [x] The audit stream (68 events) holds to the `AuditLog` rules: actor = `account=<id>` / `system` / masked email; detail = event codes, IDs and `getClass().getSimpleName()` — no tokens, no raw emails, no bodies.
- [x] Frontend: in prod `tauri-plugin-log` writes only to `logs/mail-frontend*`, and the Rust side logs 4 static paths (start, data root, log dir, WebView2 dir); **no `attachConsole`** → the JS console is not persisted anywhere. `clientErrors.ts`: truncation at 4000, loopback only with `X-API-KEY`, self-disable on 404/501; the sink `/internal/client-errors` still does not exist (the residue from 2026-06-13 stands).
- [x] **Fixed (L1, Low): raw email in internal exception texts.** `GlobalExceptionHandler` logs `ex.getMessage()` at WARN and `ContactBulkService` returns/logs a per-item `e.getMessage()` — four internal texts carried an unmasked email and so bypassed the masker: `AccountAlreadyExistsException`, `DuplicateContactException`, `ContactService.addEmail` (address already on the contact) and `ContactService.checkNoDuplicatesWithinAccount` (duplicate within the request). The internal texts now mask through `LogMasker.maskEmail`; the localised response to the client (messageKey + raw argument) is unchanged. Side effect: the bulk-create per-item message carries a masked email — the item is still identified unambiguously by `index` + `errorCode`.

Residue / notes:

- `db_backup_failed` is the only audit event with a raw `e.getMessage()` (SQLite) — local in origin (our own DB, not attacker-controlled), CRLF-safe, kept for recovery diagnostics.
- Jackson "unreadable JSON" does not echo the payload into the log — source-in-location is off by default in Jackson 3; we rely on that default (with no explicit test).
- Upgrade path: masking email patterns inside `CrlfSafeMessageConverter` itself would enforce the "no raw email in the log" rule through a chokepoint for future log sites too; today the rule is held by per-site discipline plus this re-audit.

## cargo audit — Rust/Tauri dependencies (2026-06-13)

`cargo audit` (RustSec advisory DB) over `frontend/src-tauri/Cargo.lock`
(597 crate dependencies).

- [x] **0 vulnerabilities** (exit 0). The 20 findings are all `warning` of type `unmaintained` (17) and `unsound` (3); none of type `vulnerability`.
- [x] 11 of the 20 are the Linux-only GTK3/glib stack (`atk*`, `gdk*`, `gtk*`, `glib`) — transitive deps of webkit2gtk through Tauri. V0.1.0 is Windows-only, so those crates are not compiled into the release binary.
- [x] The rest are transitive: `proc-macro-error` (build-time proc-macro, not in the runtime binary), `fxhash`, `unic-*` (Unicode), `rand` 0.7.3/0.8.5 (unsound only with a custom global logger calling `rand::rng()` during init — a pattern the app does not use).
- [x] The accepted warnings are recorded in `frontend/src-tauri/.cargo/audit.toml` (ignore per advisory ID with a justification) → the scheduled report then shows only NEW advisories. A per-ID ignore does not hide a future `vulnerability` in the same crate (it has a different ID).
- [x] **Wired into CI** (complementing the existing Trivy SBOM scan, which covered Rust only through a generic DB, HIGH/CRITICAL and report-only):
  - `ci.yml` job `tauri` — a blocking per-PR gate (fails on `vulnerability`).
  - `vuln-scan.yml` job `cargo-audit-tauri` — a scheduled JSON report (artifact) + gate.
  - cargo-audit pinned to `0.22.2` and cached (the same pattern as `cargo-cyclonedx`).

## Change log

| Version | Date                     | Summary                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                             |
| ------- | ------------------------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 2.8     | 2026-09-09               | Translated to English. This is a document a security reader lands on, and it indexes six English audits while being the only Czech link in that chain; the repo now records the language split by reader in [CLAUDE.md](../CLAUDE.md). No claim, verdict, date or anchor changed — translation only.                                                                                                                                                                                                                                                                                                                                                                |
| 2.7     | 2026-09-09               | The B2 and B5 anchors in the verdict index were corrected to `cad05cb` — they stood on `5799e8b`, a pre-squash SHA that does not exist in the repo (`git cat-file -t` cannot find it), so the index gave no route to the audited tree. Both audits themselves carry `cad05cb` and correctly cite `5799e8b` only as "recorded pre-squash as". The verdicts do not change (**PASS** for both). This is the second occurrence of the same shape — the first was B6 in version 2.2 — with the same cause: `npm run check:audits` compares object ids, but this index is maintained by hand per step (c) in [CLAUDE.md](../CLAUDE.md), so a dead SHA here trips no gate. |
| 2.6     | 2026-09-06               | The B1 row was aligned with [IMAP_SMTP_AUDIT.md](../docs/IMAP_SMTP_AUDIT.md) v1.6 (`02ff962`) — a revision driven by splitting the IMAP connection into two lanes, not by the ledger cap: one claim in §1 had stopped holding. The verdict is unchanged (**PASS**), with no new finding.                                                                                                                                                                                                                                                                                                                                                                            |
| 2.5     | 2026-09-02               | The B1 row was aligned with [IMAP_SMTP_AUDIT.md](../docs/IMAP_SMTP_AUDIT.md) v1.5 (`885b98a`) — a re-verification triggered by the ledger cap, not by a failure. The verdict is unchanged (**PASS**), with no new finding. A **non-existent SHA** was fixed at the same time: the row pinned B1 to `806528e`, a pre-squash anchor that `git log` cannot find — the same trap 2.2 recorded for B6, unnoticed this time because the audit itself carried the post-squash `cad05cb` alongside it.                                                                                                                                                                      |
| 2.4     | 2026-08-08               | The remaining four audits (B2, B4, B5, B6) were re-verified and re-anchored to `5799e8b` — after which [docs/audit-freshness.json](../docs/audit-freshness.json) is empty, so all six boundaries are aligned with the code. No verdict changes (**PASS** everywhere).                                                                                                                                                                                                                                                                                                                                                                                               |
| 2.3     | 2026-08-08               | The B1 row was aligned with [IMAP_SMTP_AUDIT.md](../docs/IMAP_SMTP_AUDIT.md) v1.3 (`806528e`) — a re-verification after 21 commits of drift; the new finding B1-2 (Medium DoS, quadratic subject normalisation) was found and fixed. The verdict is unchanged (**PASS**).                                                                                                                                                                                                                                                                                                                                                                                           |
| 2.2     | 2026-08-08               | The B6 row was aligned with [UPDATER_AUDIT.md](../docs/UPDATER_AUDIT.md) v1.3 (`3162e6a`) — the original anchor `e2b8d8d` did not exist in the repo (a pre-squash SHA), found by `npm run check:audits`. The verdict is unchanged (**PASS**).                                                                                                                                                                                                                                                                                                                                                                                                                       |
| 2.1     | 2026-08-08               | The B3 row in the verdict index was aligned with [API_SURFACE_AUDIT.md](../docs/API_SURFACE_AUDIT.md) v1.4 (`d45e253`) — the audit re-counted the controller enumeration after #230/#232. The verdict is unchanged (**PASS**).                                                                                                                                                                                                                                                                                                                                                                                                                                      |
| 2.0     | 2026-07-20               | Restructuring (documentation consolidation): five per-boundary summary sections were replaced by the verdict index — the detailed claims live only in `docs/*_AUDIT.md`, which shortens the propagation of an audit claim to 2 places (audit + threat model). A version header was added; the dated cross-cutting sections were marked as snapshots of specific runs. No claim changes in substance.                                                                                                                                                                                                                                                                |
| —       | 2026-04-30 to 2026-07-12 | The append-only era with no version header (the header claimed "state as of 2026-04-30" while the content grew into 2026-07-11) — the full history is in git.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                       |
