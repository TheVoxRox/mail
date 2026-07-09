# VoxRox Mail — Sidecar HTTP API Surface Audit

| | |
|---|---|
| **Version** | 1.3 |
| **Date** | 2026-07-09 |
| **Applies to** | VoxRox Mail V0.1.0 |
| **Audited commit** | `d55b753` (claims re-verified 2026-07-09) |
| **Subsystem** | Sidecar REST API — Boundary 3 of [SECURITY_THREAT_MODEL.md](../SECURITY_THREAT_MODEL.md) |
| **Verdict** | **Security: PASS** (no exploitable finding). One Low defense-in-depth finding (**A1** — unbounded JSON write-body) **fixed**; informational notes recorded. |

Per-subsystem release audit of the **loopback HTTP API** the Tauri WebView calls:
authentication (`X-API-KEY`), request authorization, input validation on every
controller, error-response hygiene, attachment streaming (path traversal), and
the diagnostic/data-exposure endpoints. Method: static trace of the Spring
Security filter chain, enumeration of all 15 `@RestController`s (12 public +
the 3 `/api/internal` ones) and their request DTOs, and a data-flow check of
the two highest-risk paths (attachment download, diagnostic dump).

Scope is Boundary 3 (sidecar ↔ WebView, loopback + `X-API-KEY`). The
WebView↔SPA / mail-content boundary is Boundary 4, covered separately by
[CONTENT_RENDERING_AUDIT.md](CONTENT_RENDERING_AUDIT.md), and the updater
(Boundary 6) by [UPDATER_AUDIT.md](UPDATER_AUDIT.md). OAuth (Boundary 2) and
crypto/filesystem (Boundary 5) have focused verification audits —
[OAUTH_AUDIT.md](OAUTH_AUDIT.md) / [CRYPTO_STORAGE_AUDIT.md](CRYPTO_STORAGE_AUDIT.md);
Boundary 1 is covered by the TLS-hardening PRs and the 2026-06-06 IMAP
sync/write review (see the audit map in [AUDIT_GUIDE.md](AUDIT_GUIDE.md)).

## 1. Authentication & authorization (confirmed)

- **Chokepoint.** [ApiKeyFilter](../backend/src/main/java/org/voxrox/mailbackend/core/security/ApiKeyFilter.java)
  runs before `UsernamePasswordAuthenticationFilter`. A present-but-wrong
  `X-API-KEY` is rejected **fail-fast** with 401 and an `AuditLog.failure`
  entry; a matching key populates the `SecurityContext`. The comparison is
  constant-time (`MessageDigest.isEqual` over SHA-256 digests of both sides — no
  length or timing leak). The key is per-JVM random, in-memory only, written to
  `session.json` (user-profile ACLs), never persisted to the DB.
- **Default-deny.** [SecurityConfig](../backend/src/main/java/org/voxrox/mailbackend/core/config/SecurityConfig.java)
  ends the chain with `anyRequest().authenticated()`. The `PUBLIC_ENDPOINTS`
  allow-list is minimal and intentional: the OAuth flow (served by the system
  browser without a key, integrity-protected by state + PKCE), the two static
  `auth-*.html` pages, `/error`, and the springdoc paths (disabled in the prod
  sidecar via `springdoc.*.enabled=false`). The `ASYNC`/`ERROR` dispatcher types
  are permitted — they are server-internal continuations of an
  already-authorized request and cannot be triggered from outside the container.
- **Internal endpoints are behind the key.** `/api/internal/**`
  (diagnostic-dump, threading recompute, client-boot, actuator `health`) is not
  in the allow-list, so it inherits `authenticated()`. The two support/QA hooks
  are `@Hidden` from OpenAPI.
- **IDOR — accepted by design.** A single API key models a single-user desktop
  app; endpoints do not enforce per-account ownership (documented inline in
  `SecurityConfig.PUBLIC_ENDPOINTS`). Bare-`stableId` endpoints (detail,
  content, flags, delete, reply, forward) are reachable without an `accountId`,
  but every account belongs to the same OS user, so there is no cross-tenant
  boundary to cross. Contact endpoints are still rooted under `{accountId}` and
  return 404 (not 403) for a foreign id, so they do not leak existence.

## 2. Input validation (confirmed)

Ten of the 15 controllers are `@Validated` — every one that declares
constrained parameters. The five without it give bean validation nothing to
enforce: four expose parameterless endpoints (system readiness, client-config,
diagnostic-dump, the SSE notification stream) and the fifth, client-boot,
sanitizes its DTO field-by-field in the service instead (§7). Path/query params
carry `@Positive`, `@NotBlank`, `@Size`, `@Min`, and request bodies are
`@Valid @RequestBody` with per-field constraints on the DTOs. Pagination is capped server-side
(`apiMaxPageSize`), search queries are length-bounded (`searchQueryMaxLength`),
enum params (`EmailLabel`, `MessageFlag`) reject unknown values with 400. Bulk
contact operations are capped at 100 items; contact merge at 9 sources / 10
emails. The `HandlerMethodValidationException` /
`MethodArgumentNotValidException` / `ConstraintViolationException` handlers all
produce a clean RFC 9457 `ProblemDetail`.

## 3. Error-response hygiene (confirmed)

[GlobalExceptionHandler](../backend/src/main/java/org/voxrox/mailbackend/exception/GlobalExceptionHandler.java)'s
catch-all returns a fixed, localized `"An internal server error occurred."` and
logs the stack trace server-side only; `AppException` returns a controlled,
i18n-resolved message. Production pins `spring.web.error.include-message=never`
and `include-stacktrace=never` (the dev-only `application-dev.properties`
override never ships). No exception message, SQL, or stack frame reaches the
client.

## 4. Attachment streaming — no path traversal (confirmed)

[MailReadController.downloadAttachment](../backend/src/main/java/org/voxrox/mailbackend/feature/mail/controller/MailReadController.java)
→ [AttachmentService](../backend/src/main/java/org/voxrox/mailbackend/feature/mail/service/AttachmentService.java):

- `partPath` is a **MIME structure index** (e.g. `"2.1"`), not a filesystem
  path. `findPartByPath` splits on `.` and `Integer.parseInt`s each segment; a
  non-numeric segment throws `MessagingException` → 4xx. It only ever indexes
  into `jakarta.mail` `Multipart` children — it never touches the filesystem.
- The temp file is `Files.createTempFile(privateTempDir, "attach_" + stableId + "_", ".tmp")`,
  created **after** `messageRepository.findByStableId(stableId)` succeeds, so
  `stableId` is a stored, app-generated value; `Files.createTempFile` also
  rejects any path separator in the prefix. No attacker-controlled value reaches
  a path.
- Response headers are safe: the download filename is sanitized to a strict
  ASCII subset (`sanitizeAsciiFileName` drops `" \ / ;` and control chars) plus
  an RFC 5987 UTF-8 form — no header/`Content-Disposition` injection.
- Resource cleanup is correct: `DeleteOnCloseFileInputStream` unlinks the temp
  file on stream close, and an `@Async` `ApplicationReadyEvent` sweep removes
  `attach_*` temp files older than 1 h left by a crash.

## 5. Diagnostic dump — no secret / PII exposure (confirmed)

[DiagnosticDumpService](../backend/src/main/java/org/voxrox/mailbackend/core/diagnostic/DiagnosticDumpService.java)
(`GET /api/internal/diagnostic-dump`, behind the key) emits only operational
metadata: counts, IMAP pool stats, cached-token **count**, per-account
`LogMasker.maskEmail` + server host/port/SSL + auth-type name + booleans
(`active`, `requiresReauth`, `lastErrorPresent`), folder sync state (UIDs),
message counts, and JVM/runtime info. **No** credentials, OAuth tokens, message
bodies, subjects, or senders; the `lastError` text is reduced to a boolean.
[ClientBootDiagnosticsService](../backend/src/main/java/org/voxrox/mailbackend/core/diagnostic/ClientBootDiagnosticsService.java)
self-defends against its unauthenticated-shaped input: timing keys are
allow-listed, values range-bounded, text capped at 512 chars, and only the
latest snapshot is retained (no unbounded growth).

## 6. Finding A1 (Low) — unbounded JSON write-body — FIXED 2026-07-09

**What.** The send / draft / draft-send endpoints consume
`application/json` (`@RequestBody MailRequest` / `DraftRequest`), **not**
multipart, so `spring.servlet.multipart.max-file-size/​max-request-size=50MB`
does **not** apply to them. Before the fix, `body` had no length cap, the
`attachments` list had no count cap, and the only bound — per-attachment
`@Size(max = 70 MB)` on `base64Data` — is evaluated by bean-validation **after**
Jackson has already deserialized the whole payload into memory. A caller in
possession of the API key could therefore submit an arbitrarily large JSON body
(large `body`, or many large attachments) and exhaust the sidecar heap.

**Severity: Low.** Boundary 3 rates local DoS `D` as Low: the endpoint is
loopback-only and requires the `X-API-KEY` from `session.json`, so the actor is
a same-user process — already out of scope (§1 of the threat model; such an
actor can read the plaintext DB and crash the JVM by other means). The
legitimate client enforces a 10 MB/attachment and 25 MB/total cap in
[AttachmentPicker.svelte](../frontend/src/lib/components/compose/AttachmentPicker.svelte).
This is a defense-in-depth hardening gap, not an exploitable vulnerability.

**Fix.** Added per-field bounds consistent with the codebase's existing caps
(page size, search query, base64 data): `@Size(max = 10 MiB)` on `body` and
`@Size(max = 50)` on the `attachments` list, on both
[MailRequest](../backend/src/main/java/org/voxrox/mailbackend/feature/mail/dto/MailRequest.java)
and [DraftRequest](../backend/src/main/java/org/voxrox/mailbackend/feature/mail/dto/DraftRequest.java);
corrected the misleading "aligned with multipart 50 MB" comment. Regression
tests: `MailWriteControllerTest.sendBodyTooLong` and `sendTooManyAttachments`
(both assert 400 + no service call).

**Residual (accepted).** The per-field `@Size` caps run post-deserialization, so
the true pre-deserialization aggregate bound (worst case ≈ 50 × 70 MB) is not
closed in code. Accepted for V0.1.0 for the same reason the finding is Low
(loopback + authenticated + same-user out of scope + client-enforced 25 MB
total). **Upgrade path:** a container-level `Content-Length` request-size filter
on the write endpoints (rejecting oversize bodies with 413 before Jackson
reads them) if the boundary is ever hardened toward a lower-trust caller.

## 7. Informational notes (no change required)

- **CORS `file://*` origin.** `corsConfigurationSource` allows `file://*` (and
  `http(s)://localhost:[*]`) with `allowCredentials(true)`. Not exploitable: API
  auth is a custom `X-API-KEY` **header** (not a cookie), which a cross-origin
  page cannot obtain (random port + key in `session.json`); "credentials" in the
  CORS sense (cookies/HTTP auth) only gate the OAuth session, which is
  state/PKCE-protected. Left as-is; a future tightening could drop `file://*`.
- **Runtime paths in the dump.** `runtime.json` includes absolute data/db/log
  paths, which contain the OS username. Acceptable for a user-initiated support
  artifact the user chooses to share.
- **`ClientBootDiagnosticsController` has no `@Valid`.** Cosmetic — the DTO
  carries no bean-validation constraints and the service sanitizes every field
  (§5), so nothing is unenforced.

## 8. References

- [SECURITY_THREAT_MODEL.md](../SECURITY_THREAT_MODEL.md) — Boundary 3 STRIDE matrix.
- [CONTENT_RENDERING_AUDIT.md](CONTENT_RENDERING_AUDIT.md) — Boundary 4 companion audit.
- [backend/SECURITY_RELEASE_CHECK.md](../backend/SECURITY_RELEASE_CHECK.md) — per-release security gate.

## 9. Change log

- **1.3** (2026-07-09) — added the audited-commit header row (`d55b753`,
  claims re-verified during the truing pass); boundary-coverage paragraph now
  points at the new B2/B5 focused audits and the audit map in AUDIT_GUIDE.md.
- **1.2** (2026-07-09) — corrected the boundary-coverage claim: Boundaries 2
  (OAuth) and 5 (crypto/filesystem) do **not** have dedicated audit documents
  (the previous wording said they "each have their own audit pass"); their
  coverage lives in the threat-model STRIDE rows and hardening records. No
  change to any Boundary 3 verdict.
- **1.1** (2026-07-09) — corrected the controller enumeration (15 total: 12
  public + 3 `/api/internal`, previously misstated as 12) and the `@Validated`
  claim (10 of 15 carry it; the five without have no constrained parameters).
  No change to any verdict.
- **1.0** (2026-07-09) — initial audit.
