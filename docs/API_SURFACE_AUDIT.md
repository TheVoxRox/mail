# VoxRox Mail — Sidecar HTTP API Surface Audit

|                    |                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                              |
| ------------------ | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **Version**        | 1.8                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                          |
| **Date**           | 2026-09-27                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                   |
| **Applies to**     | VoxRox Mail V0.1.0                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                           |
| **Audited commit** | `dd2225d` (every claim re-verified 2026-09-27)                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                               |
| **Code paths**     | `backend/src/main/java/org/voxrox/mailbackend/feature/mail/controller`, `backend/src/main/java/org/voxrox/mailbackend/feature/account/controller`, `backend/src/main/java/org/voxrox/mailbackend/feature/contact/controller`, `backend/src/main/java/org/voxrox/mailbackend/core/clientconfig`, `backend/src/main/java/org/voxrox/mailbackend/core/diagnostic`, `backend/src/main/java/org/voxrox/mailbackend/core/system`, `backend/src/main/java/org/voxrox/mailbackend/core/security`, `backend/src/main/java/org/voxrox/mailbackend/core/config/SecurityConfig.java`, `backend/src/main/java/org/voxrox/mailbackend/core/init/HandshakeService.java`, `backend/src/main/java/org/voxrox/mailbackend/exception`, `backend/src/main/java/org/voxrox/mailbackend/feature/mail/service/AttachmentService.java`, `backend/src/main/java/org/voxrox/mailbackend/feature/mail/dto`, `backend/src/main/java/org/voxrox/mailbackend/feature/contact/dto`, `backend/src/main/java/org/voxrox/mailbackend/feature/account/dto`, `backend/src/main/resources/application.properties` |
| **Auditor**        | Claude (1.0 in #134; 1.8 re-verified by Claude Opus 5.5) + owner review                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                      |
| **Subsystem**      | Sidecar REST API — Boundary 3 of [SECURITY_THREAT_MODEL.md](../SECURITY_THREAT_MODEL.md)                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                     |
| **Verdict**        | **Security: PASS** (no exploitable finding). One Low defense-in-depth finding (**A1** — unbounded JSON write-body) **fixed**; informational notes recorded.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                  |

Per-subsystem release audit of the **loopback HTTP API** the Tauri WebView calls:
authentication (`X-API-KEY`), request authorization, input validation on every
controller, error-response hygiene, attachment streaming (path traversal), and
the diagnostic/data-exposure endpoints. Method: static trace of the Spring
Security filter chain, enumeration of all 17 `@RestController`s (13 public +
the 4 `/api/internal` ones) and their request DTOs, and a data-flow check of
the two highest-risk paths (attachment download, diagnostic dump). Dynamic
cover is limited to what the tests named below exercise — a request without
the key refused (`SecurityDispatcherTypeTest`), the dump's privacy (§5) and the
A1 bounds (§6). The wrong-key 401 and the constant-time comparison are static
only.

Regenerate the enumeration every count below rests on (the `\b` matters —
without it `@RestControllerAdvice` on `GlobalExceptionHandler` inflates the
total by one; `OAuth2CallbackController` is a plain `@Controller` serving the
redirect pages and is deliberately outside the count):

```sh
C=$(grep -rlE '@RestController\b' backend/src/main/java)
echo "$C" | wc -l                              # 17 controllers
echo "$C" | xargs grep -l '/api/internal' | wc -l   # 4 internal (13 public)
echo "$C" | xargs grep -l '@Validated' | wc -l      # 12 @Validated
echo "$C" | xargs grep -l '@Hidden' | wc -l         # 3 hidden from OpenAPI
```

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
  `auth-*.html` pages, `/error`, and the springdoc paths, which serve nothing
  in the sidecar: `springdoc.api-docs.enabled` and `springdoc.swagger-ui.enabled`
  default to `false` in `application.properties`, and the `repackage` execution
  in `backend/pom.xml` leaves springdoc out of the jar altogether
  (`excludeGroupIds`). The `ASYNC`/`ERROR` dispatcher types
  are permitted — they are server-internal continuations of an
  already-authorized request and cannot be triggered from outside the container.
- **Internal endpoints are behind the key.** `/api/internal/**`
  (diagnostic-dump, threading recompute, correspondent rebuild, client-boot,
  actuator `health`) is not in the allow-list, so it inherits
  `authenticated()`. None of them is in the OpenAPI document:
  `springdoc.paths-to-exclude=/api/internal/**` leaves the whole prefix out,
  as the committed snapshot
  (`backend/src/test/resources/openapi/api-docs.json`) shows. The three
  support/QA hooks — client-boot, threading recompute and correspondent
  rebuild — also carry `@Hidden`, which is what the fourth enumeration command
  counts; the diagnostic dump does not, and is left out all the same.
- **IDOR — accepted by design.** A single API key models a single-user desktop
  app; endpoints do not enforce per-account ownership (documented inline in
  `SecurityConfig.PUBLIC_ENDPOINTS`). Bare-`stableId` endpoints (detail,
  content, flags, delete, reply, forward) are reachable without an `accountId`,
  but every account belongs to the same OS user, so there is no cross-tenant
  boundary to cross. The address book is app-wide rather than per-account since
  #265, so the contact endpoints sit at `/api/v1/contacts` and
  `/api/v1/contact-labels` and address a contact by a bare `contactId`, exactly
  as the mail endpoints do with `stableId`; e-mail uniqueness is enforced across
  the whole book. Only `GET /api/v1/contacts/autocomplete` still takes an
  `accountId`, as a `@RequestParam @Positive`, because it suggests
  correspondents seen on one account. That leaves no foreign id to probe for, so
  the 404-rather-than-403 reasoning earlier versions of this section relied on no
  longer applies — and does not need to: authorization here is path-independent
  (`PUBLIC_ENDPOINTS` plus `anyRequest().authenticated()` behind the key), so
  moving a path changes no posture.

## 2. Input validation (confirmed)

Twelve of the 17 controllers are `@Validated` — every one that declares
constrained parameters. The five without it give bean validation nothing to
enforce: four expose parameterless endpoints (system readiness, client-config,
diagnostic-dump, the SSE notification stream) and the fifth, client-boot,
sanitizes its DTO field-by-field in the service instead (§7). Path/query params
carry `@Positive`, `@NotBlank`, `@Size`, `@Min`, and request bodies are
`@Valid @RequestBody` with per-field constraints on the DTOs. Pagination is capped server-side
(`apiMaxPageSize`), search queries are length-bounded (`searchQueryMaxLength`),
enum params (`EmailLabel`, `MessageFlag`) reject unknown values with 400. Bulk
contact operations are capped at 100 items; contact merge at 9 sources / 10
emails; contact-label assignment at 100 contacts and 50 labels per direction,
label names at 60 chars; compose autocomplete bounds its query
(`contactQueryMaxLength`) and clamps `limit` to
`contactAutocompleteMaxLimit`. The `HandlerMethodValidationException` /
`MethodArgumentNotValidException` / `ConstraintViolationException` handlers all
produce a clean RFC 9457 `ProblemDetail`.

## 3. Error-response hygiene (confirmed, with one informational note)

[GlobalExceptionHandler](../backend/src/main/java/org/voxrox/mailbackend/exception/GlobalExceptionHandler.java)'s
catch-all returns a fixed, localized `"An internal server error occurred."` and
logs the stack trace server-side only. No handler returns a stack frame or
SQL. The text the handlers do return:

- **Validation errors** (the three handlers of §2) list each field with its
  constraint's message, which comes from the DTO's annotations and the message
  bundles, not from an exception. An unreadable request body gets a fixed
  message; the parser's own text goes to the log only.
- **`AppException`** resolves its message key against the bundles with the
  exception's `messageArgs`, and falls back to the exception's own message when
  there is no key or it does not resolve. Most subclasses put values the app
  itself holds into those arguments — an id, a limit, a folder or contact name.
  **The mail layer does not.**
  `MailConnectionException` and `MailOperationException` pass their whole
  message as `{0}` of `error.mail.connectionFailed` /
  `error.mail.operationFailed`, and eight throw sites build that message from a
  caught exception's `getMessage()`: the IMAP and SMTP connection test, the
  IMAP connection manager and folder executor, attachment download,
  message-text extraction and the OAuth token refresh. Whenever one of them
  ends a request, the text a mail library or a provider wrote — a server's
  reply, a host name, a TLS or HTTP error — reaches the client in `detail`, and
  again in `messageArgs`. §7 records why this is not a finding. A rejected login
  is kept out: the connection test maps `AuthenticationFailedException` to
  `MailAuthenticationException`, which does not carry the server's text.

Regenerate the throw sites (the `-A2` catches a message built on the line after
the constructor call):

```sh
grep -rnE -A2 'new Mail(Connection|Operation)Exception\(' backend/src/main/java \
  | grep 'getMessage()'                        # 8 throw sites
```

An error Spring MVC never sees — raised in the filter chain, or sent with
`sendError` — ends at Spring Boot's `/error` instead, and production keeps that
page bare: `spring.web.error.include-message=never` and
`include-stacktrace=never` in `application.properties`. That file is the only
Spring configuration file in the jar (the other resource, `logback-spring.xml`,
configures logging only), and there is no `application-<profile>.properties`,
so no profile can loosen the two settings from inside the jar. Configuration
outside it still can — the environment the sidecar inherits, or a `config/`
directory or `application.properties` in its working directory — and whoever
can write those is a same-user actor, whom the threat model already puts out of
scope. Until #574 the jar also carried `application-dev.properties`, which did
loosen them once the `dev` profile was switched on (change log, 1.7).

## 4. Attachment streaming — no path traversal (confirmed)

[MailReadController.downloadAttachment](../backend/src/main/java/org/voxrox/mailbackend/feature/mail/controller/MailReadController.java)
→ [AttachmentService](../backend/src/main/java/org/voxrox/mailbackend/feature/mail/service/AttachmentService.java):

- `partPath` is a **MIME structure index** (e.g. `"2.1"`), not a filesystem
  path. `findPartByPath` splits on `.` and `Integer.parseInt`s each segment; a
  non-numeric segment throws `MessagingException` before any file is created.
  It only ever indexes into `jakarta.mail` `Multipart` children — it never
  touches the filesystem. The request then fails with the wrong status, which
  is untidy but not a traversal: the folder executor turns the
  `MessagingException` into a 500 `MAIL_CONNECTION_ERROR`, and an index past
  the last part, whose message says "not found", into a 404 that names the
  folder instead (§7).
- The temp file is `Files.createTempFile(privateTempDir, "attach_" + stableId + "_", ".tmp")`,
  created **after** `messageRepository.findByStableId(stableId)` succeeds, so
  `stableId` is a stored, app-generated value; `Files.createTempFile` also
  rejects any path separator in the prefix. No attacker-controlled value reaches
  a path.
- Response headers are safe: the download filename is sanitized to a strict
  ASCII subset (`sanitizeAsciiFileName` replaces `" \ / ;`, control characters
  and anything outside printable ASCII with `_`) plus an RFC 5987 UTF-8 form —
  no header/`Content-Disposition` injection.
- Resource cleanup is correct: `DeleteOnCloseFileInputStream` unlinks the temp
  file on stream close, and an `@Async` `ApplicationReadyEvent` sweep removes
  `attach_*` temp files older than 1 h left by a crash.

## 5. Diagnostic dump — no secret / PII exposure (confirmed)

[DiagnosticDumpService](../backend/src/main/java/org/voxrox/mailbackend/core/diagnostic/DiagnosticDumpService.java)
(`GET /api/internal/diagnostic-dump`, behind the key) emits only operational
metadata: app and API versions, counts, IMAP pool stats, cached-token
**count**, per-account `LogMasker.maskEmail` + provider name + server
host/port/SSL + auth-type name + last sync time + booleans (`active`,
`requiresReauth`, `lastErrorPresent`), folder sync state (UIDs), message
counts, JVM/runtime info, the backend's startup timings, and the latest
client-boot snapshot (below). A folder the user created is named by a
pseudonym (`folder-<n>`, the same in both files that list folders) and only a
folder with a provider role, or `INBOX`, keeps its name; the data, database and
log paths replace the user's home directory with `~`, so the Windows account
name does not travel either. `DiagnosticDumpPrivacyIT` fetches the dump over
HTTP after a real sync and cached token and finds none of the canary values in
any entry. **No** credentials, OAuth tokens, message
bodies, subjects, or senders; the `lastError` text is reduced to a boolean.
[ClientBootDiagnosticsService](../backend/src/main/java/org/voxrox/mailbackend/core/diagnostic/ClientBootDiagnosticsService.java)
self-defends against its unauthenticated-shaped input: timing keys are
allow-listed, values range-bounded, text capped at 512 chars (the route also
loses its query and fragment), and only the latest snapshot is retained (no
unbounded growth).

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

- **CORS `file://*` origin.** `corsConfigurationSource` allows five origin
  patterns — `http://localhost:[*]`, `http://127.0.0.1:[*]`,
  `http://tauri.localhost`, `tauri://localhost` and `file://*` — with
  `allowCredentials(true)`. The two `tauri` ones are the packaged webview's own
  origins and are why the installed app is not answered with 403 before the key
  filter runs; there is no `https://` pattern. Not exploitable: API
  auth is a custom `X-API-KEY` **header** (not a cookie), which a cross-origin
  page cannot obtain (random port + key in `session.json`); "credentials" in the
  CORS sense (cookies/HTTP auth) only gate the OAuth session, which is
  state/PKCE-protected. Left as-is; a future tightening could drop `file://*`.
- **Runtime paths in the dump — closed by #518.** `runtime.json` used to carry
  the absolute data, database and log paths, which contain the OS username, and
  this note accepted that as the price of a support artifact the user chooses to
  share. It is no longer paid: the paths are written relative to `~` (§5). The
  note stays so the reasoning that accepted it does not get re-applied to
  something else.
- **`ClientBootDiagnosticsController` has no `@Valid`.** Cosmetic — the DTO
  carries no bean-validation constraints and the service sanitizes every field
  (§5), so nothing is unenforced.
- **Mail-layer error text reaches the client (§3).** It has at every anchor:
  the throw sites were already there at `d55b753`, the first. Not a finding,
  because of who receives it. The recipient is whoever holds the API key —
  the app's own WebView, which shows the text to the user whose mail server or
  provider wrote it, or a same-user process, which the threat model puts out
  of scope and which could read the same text in `mail.log`, where the handler
  logs it. Versions up to 1.7 said the opposite; see 1.8 in the change log.
- **Framework-level request errors reach the catch-all.** A request Spring MVC
  rejects before the controller runs — a path variable that is not a number,
  an HTTP method the path does not map — has no handler of its own, so the
  catch-all answers it: 500 with the fixed message, and a `CRITICAL` log line
  with the stack trace. Hygiene holds, since nothing but the fixed text goes
  out; the status is wrong and the log is noisy. The attachment path of §4 is
  the same kind of untidiness.

## 8. References

- [SECURITY_THREAT_MODEL.md](../SECURITY_THREAT_MODEL.md) — Boundary 3 STRIDE matrix.
- [CONTENT_RENDERING_AUDIT.md](CONTENT_RENDERING_AUDIT.md) — Boundary 4 companion audit.
- [backend/SECURITY_RELEASE_CHECK.md](../backend/SECURITY_RELEASE_CHECK.md) — per-release security gate.

## 9. Change log

- **1.8** (2026-09-27) — **re-verified against `dd2225d`**, every claim, after
  the independent pass that [AUDIT_GUIDE.md](AUDIT_GUIDE.md) §5 requires found
  1.7 wrong. 1.7 went into the verdict index and the threat model in the commit
  that wrote it, and the pass, run afterwards, found four things. §3's "no
  exception message ... reaches the client" never held: mail-layer errors carry
  the underlying exception's text into `detail` (§3, §7). 1.7's new sentence,
  "`application.properties` is the only configuration file the jar carries",
  held only from #574 (`77abf9b`), not at the anchor `6365fae` that 1.7 kept,
  and overstated twice: the jar also carries `logback-spring.xml`, and
  configuration outside the jar can still loosen the error settings. The 1.7
  entry below names two of the four error settings the dev profile set (it also
  set `include-exception=true` and `include-binding-errors=on_param`), calls a
  standalone `mvn spring-boot:run` its only documented use when the file's own
  header also offered `-Dspring.profiles.active=dev` on any run, and says no
  code under this audit's paths changed, which held for the diff of #574 only.
  And `Code paths` left out what §1, §3 and §4 rest on, which is why
  `check:audits` did not see #574. It now adds `SecurityConfig.java` (§1
  allow-list and default-deny, §7 CORS), `HandshakeService.java` (§1 key),
  `AttachmentService.java` (§4), the whole `exception/` package in place of
  `GlobalExceptionHandler.java` alone (§3) and `application.properties` (§1
  springdoc, §3 error settings). Re-verifying found three more. §1 said the
  diagnostic dump is documented in OpenAPI, but
  `springdoc.paths-to-exclude=/api/internal/**` has kept every internal
  endpoint out of it since the initial import. §4 said a non-numeric MIME path
  segment ends in 4xx; it ends in 500, and an index past the last part in a 404
  about the folder (§7). §5 gave the dump's contents as a closed list and left
  out the provider name, the last sync time, the startup timings and the
  client-boot snapshot. The header gains the `Auditor` row §2 of the guide
  requires, and the method statement names what is static only. The four
  enumeration commands give the same numbers (17, 4, 12, 3), and a fifth
  regenerates §3's throw sites. The statuses in §4 and §7 come from a
  throwaway MockMvc probe that is not in the tree, over the static trace of how
  the folder executor maps the exception. The new anchor clears the three
  acknowledgements recorded since 1.6. The verdict is unchanged: **PASS**.
- **1.7** (2026-09-26) — §3 corrected, anchor unchanged. It said the dev-only
  `application-dev.properties` override "never ships", and it did: the file sat
  in `src/main/resources`, so every jar carried it as
  `BOOT-INF/classes/application-dev.properties`, checked in the sidecar built
  from the 2026-09-23 candidate. It was inactive unless the `dev` profile was
  switched on, which the sidecar never does, but switching it on (for instance
  with `SPRING_PROFILES_ACTIVE=dev` in the environment) would have loosened the
  two settings this section rests on (`include-message=always`,
  `include-stacktrace=on_param`) and turned on DEBUG and SQL logging. Its only documented use
  was a standalone `mvn spring-boot:run`, which is not part of any workflow, so
  #574 deleted it rather than exclude it from the jar; `application.properties`
  is now the only configuration file there is. No code under this audit's paths
  changed, and the verdict is unchanged: **PASS**.
- **1.6** (2026-09-19) — **re-verified against `6365fae`**, which clears the
  acknowledgement run in [audit-freshness.json](audit-freshness.json) at 6 of 8
  rather than letting it reach the cap. Every claim was re-checked against the
  code, not only the paths that had drifted; the four enumeration commands were
  re-run and all four numbers are unchanged (17 controllers, 4 internal, 12
  `@Validated`, 3 `@Hidden`), as are §1 authentication, §2 every named bound,
  §3, §4, §5 and the A1 fix and its residual. Two claims had gone stale, and
  both are the kind an acknowledgement is bad at catching, because each one
  reads a diff for whether a verdict moves rather than for whether the prose is
  still true. §1 said the contact endpoints were "still rooted under
  `{accountId}`" and returned 404 for a foreign id; #265 made the address book
  app-wide, so they sit at `/api/v1/contacts` with a bare `contactId` and there
  is no foreign id to probe — the acknowledgement of 2026-08-08 correctly
  judged that authorization is path-independent and therefore that no verdict
  moved, which is why the sentence describing the paths survived anyway. §7
  still accepted the OS username travelling in `runtime.json`, which #518 had
  already closed and §5 had already been updated for. The verdict is unchanged:
  **PASS**.
- **1.5** (2026-09-17) — §5 revised, anchor unchanged (the drift in
  `core/diagnostic` is acknowledged in `audit-freshness.json`). The dump used to
  carry the names of folders the user created and, through the data paths, the
  Windows account name; neither was on the list above, which was written from
  the fields rather than from their values. Both are gone now, and §5 names
  the integration test that checks the claim on real data. The verdict is
  unchanged.
- **1.4** (2026-08-08) — enumeration re-counted against `d45e253`; no verdict
  or mitigation changes. The controller surface grew after v1.3 without the
  audit noticing: `ContactLabelController` (#230) and
  `CorrespondentInternalController` (#232) landed, so **17** controllers
  (13 public + **4** `/api/internal`), **12** `@Validated` (the same five
  without it, unchanged), and **three** `@Hidden` support hooks. This is the
  exact failure mode [AUDIT_GUIDE.md](AUDIT_GUIDE.md) §3 rule 1 exists to
  prevent — a bare number with no way to regenerate it — so §"Method" now
  carries the commands, and they use `@RestController\b` because
  `@RestControllerAdvice` otherwise counts as an 18th. §2 also names the
  bounds on the two new surfaces (label assignment, compose autocomplete).
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
