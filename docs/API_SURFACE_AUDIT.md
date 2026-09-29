# VoxRox Mail — Sidecar HTTP API Surface Audit

|                    |                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                             |
| ------------------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **Version**        | 1.9                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                         |
| **Date**           | 2026-09-27                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                  |
| **Applies to**     | VoxRox Mail V0.1.0, cut at or after the audited commit — the draft `v0.1.0` tag (`d626a9b`) predates #574, #577 and #578 (§3, §6)                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                           |
| **Audited commit** | `55fdd1b` (every claim re-verified 2026-09-27)                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                              |
| **Code paths**     | `backend/src/main/java/org/voxrox/mailbackend/feature/mail/controller`, `backend/src/main/java/org/voxrox/mailbackend/feature/account/controller`, `backend/src/main/java/org/voxrox/mailbackend/feature/contact/controller`, `backend/src/main/java/org/voxrox/mailbackend/core/clientconfig`, `backend/src/main/java/org/voxrox/mailbackend/core/diagnostic`, `backend/src/main/java/org/voxrox/mailbackend/core/system`, `backend/src/main/java/org/voxrox/mailbackend/core/security`, `backend/src/main/java/org/voxrox/mailbackend/core/config/SecurityConfig.java`, `backend/src/main/java/org/voxrox/mailbackend/core/init/HandshakeService.java`, `backend/src/main/java/org/voxrox/mailbackend/exception`, `backend/src/main/java/org/voxrox/mailbackend/feature/mail/service/AttachmentService.java`, `backend/src/main/java/org/voxrox/mailbackend/feature/mail/dto`, `backend/src/main/java/org/voxrox/mailbackend/feature/contact/dto`, `backend/src/main/java/org/voxrox/mailbackend/feature/account/dto`, `backend/src/main/java/org/voxrox/mailbackend/feature/account/mapper/AccountMapper.java`, `backend/src/main/java/org/voxrox/mailbackend/util/LogMasker.java`, `backend/src/main/resources/application.properties`, `frontend/src/lib/api/clientBootDiagnostics.ts` |
| **Auditor**        | Claude (1.0 in #134; 1.8 re-verified by Claude Opus 5.5) + owner review                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                     |
| **Subsystem**      | Sidecar REST API — Boundary 3 of [SECURITY_THREAT_MODEL.md](../SECURITY_THREAT_MODEL.md)                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                    |
| **Verdict**        | **Security: PASS** (no exploitable finding). Three Low findings **fixed**: **A1** (unbounded JSON write-body), **A2** (a user-named folder in the dump's client-boot route) and **A3** (a user-named folder kept by name in the dump's folder lists); informational notes recorded.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                         |

Per-subsystem release audit of the **loopback HTTP API** the Tauri WebView calls:
authentication (`X-API-KEY`), request authorization, input validation on every
controller, error-response hygiene, attachment streaming (path traversal), and
the diagnostic/data-exposure endpoints. Method: static trace of the Spring
Security filter chain, enumeration of all 17 `@RestController`s (13 public +
the 4 `/api/internal` ones) and their request DTOs, and a data-flow check of
the two highest-risk paths (attachment download, diagnostic dump). The audit
relies on these tests as dynamic cover: a request without the key answered
302 (`SecurityDispatcherTypeTest`), the dump's privacy on real data
(`DiagnosticDumpPrivacyIT`, §5, A2 and A3; the `~` in the dump's paths is
covered by `DiagnosticDumpServiceTest`, since the IT's data directory need not
sit under the home directory), the regression tests each finding
in §6 names, and the throwaway probes the 1.8 change log describes for the
statuses in §4 and §7. Other claims rest on a static trace, even where a unit
test also covers them (several §2 bounds have one). Nothing tests the wrong-key
401, the constant-time comparison or the `DraftRequest` caps of A1.

`Code paths` covers the chokepoints the sections trace. What else they cite is
knowingly left out, because it changes often for reasons that rarely touch this
boundary and would run the acknowledgement cap down:

- §1: `backend/pom.xml` (the springdoc exclusion), the committed OpenAPI
  snapshot, and `FileSystemService` (the private permissions on
  `session.json`).
- §2: the service and types behind the named bounds (`ContactService`,
  `EmailLabel`, `SyncProperties`, `ClientConfigProperties`).
- §3: the services that write exception text (the mail and auth services, the
  sync, SMTP send and draft save), `ContactBulkService`, `AccountLastErrorCode`,
  `SyncHealthIndicator`, the message bundles, and the rest of
  `backend/src/main/resources`.
- §4: the error mapping in `ImapFolderExecutor`.
- §5: `StartupTimingService` and `ImapConnectionManager`'s pool statistics.
- §6: the client caps behind A1's residual (`AttachmentPicker.svelte` and the
  client-config store), and the client's routes (`frontend/src/routes`), which
  A2's residual rests on.

A literal pathspec also sees only the files it names: 1.7's paths named no
resource at all, which is how #574's deletion of one went unseen. Where a claim
rests on a list of such files, §3 gives the command that regenerates the list;
the rest are re-read by each re-verification.

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
Boundary 1 by the full audit [IMAP_SMTP_AUDIT.md](IMAP_SMTP_AUDIT.md) (see the
audit map in [AUDIT_GUIDE.md](AUDIT_GUIDE.md)).

## 1. Authentication & authorization (confirmed)

- **Chokepoint.** [ApiKeyFilter](../backend/src/main/java/org/voxrox/mailbackend/core/security/ApiKeyFilter.java)
  runs before `UsernamePasswordAuthenticationFilter`. A present-but-wrong
  `X-API-KEY` on a request that reaches it is rejected **fail-fast** with 401
  and an `AuditLog.failure` entry (logout and CORS preflights are answered by
  filters ahead of it, so a wrong key there changes nothing); a matching key
  populates the `SecurityContext`; a request without the
  header passes through unauthenticated and is left to the default-deny
  below. The comparison is
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
  A request without the key to any other path is redirected to `/login` (302),
  not answered 401.
- **Keyless pages Spring Security serves itself.** A few paths are answered by
  the framework's own filters before authorization runs, so they are outside
  the allow-list: because `oauth2Login()` is configured without a login page of
  its own, `GET /login` serves the generated page listing the two providers,
  `/logout` redirects to `/login?logout`, and `/default-ui.css` serves that
  page's stylesheet. A CORS preflight (`OPTIONS`) on any path is answered by
  the CORS filter the same way: 200 with an empty body, or 403 on `/api/**` for
  an origin that is not on the list. They show nothing beyond the provider names the
  OAuth flow exposes anyway, and change no stored data.
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
  content, attachment download, flags, move, delete, reply, forward) are
  reachable without an `accountId`,
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
logs the stack trace server-side only. No handler returns a stack frame. The
text the handlers do return:

- **Validation errors** (the three handlers of §2) list each field with its
  constraint's message, which comes from the DTO's annotations and the message
  bundles, not from an exception. An unreadable request body gets a fixed
  message; the parser's own text goes to the log only.
- **`AppException`** resolves its message key against the bundles with the
  exception's `messageArgs`, and falls back to the exception's own message when
  there is no key or it does not resolve. Most subclasses put an id, a limit, a
  name or the request's own value (an unknown flag type) into those arguments.
  **The mail layer puts a cause code there** (since 1.9). A
  `MailConnectionException` or `MailOperationException` that wraps a caught
  exception carries a `MailFailureCause` as the `{0}` of
  `error.mail.connectionFailed` / `error.mail.operationFailed` — connection,
  unknown host, timeout, TLS, rejected sign-in, rejected request, rejected
  recipients, refused response, local database, local file or unexpected —
  which the message source renders in the request's language and JSON renders
  by name. The caught exception stays the cause, and its text goes to the log
  the handler writes; until 1.9 eight throw sites built the message from it
  instead, so a server's reply, a host name or a TLS error reached `detail`
  and `messageArgs` in English, inside the Czech sentence. A mail exception
  thrown with no cause still passes the message the app wrote as `{0}`: an
  English sentence, not a library's (the OAuth sign-in messages among them).

Regenerate the throw sites that build a message from a caught exception (the
`-A2` catches a message built on the line after the constructor call):

```sh
grep -rnE -A2 'new Mail(Connection|Operation)Exception\(' backend/src/main/java \
  | grep 'getMessage()'                        # 0 throw sites since 1.9
```

**An account's last error carries a cause code too.** Every endpoint that
returns an `AccountResponse` carries `lastError` and `lastErrorArgs`. The
sync, SMTP send and draft save store a failure's `MailFailureCause` under
`cause`, never its text, and `AccountMapper` renders it through the
`account.lastError.*` templates in the reader's language; a stored argument
that is not a cause's name renders as the unexpected one. The fallback text
stored beside the code names the exception's class and the cause. Until 1.9
they stored `getMessage()` as `detail` — for the sync from a
`catch (Exception)`, so Hibernate's text of a failed SQL statement could reach
`GET /api/v1/accounts`. Regenerate the sites that store a cause:

```sh
grep -rn 'AccountLastErrorCode.CAUSE' backend/src/main/java   # 5 sites in 3 services
```

**The health endpoint shows statuses only** (since 1.9).
`GET /api/internal/health` lists its components by name and status
(`management.endpoint.health.show-details=never`,
`show-components=always`). Until 1.9 it showed their details: Spring's
database indicator reports a failure as the exception's class and message, and
the disk-space indicator the absolute working directory, which usually
contains the Windows account name. Nothing read more than the status. The one
mail-layer message that carried a local path of its own, the temp file an
attachment race loses, now reports the local-file cause and keeps the path in
the log.

The bulk contact endpoints also return, per failed item, an `AppException`'s
own message — the English fallback text the app wrote, not a library's.

An error Spring MVC never sees — raised in the filter chain, or sent with
`sendError` — ends at Spring Boot's `/error` instead, and production keeps that
page bare: `spring.web.error.include-message=never` and
`include-stacktrace=never` in `application.properties`. The page's other
switches are not pinned and stay at the framework defaults: `include-exception`
(`false`), `include-binding-errors` (`never`) and `include-path` (`always`,
which returns only the request's own path). `application.properties` is
the only `application*` file among the jar's resources, so no profile can
loosen these settings from inside the jar:

```sh
git ls-files backend/src/main/resources | grep -E '/application[^/]*\.(properties|ya?ml)$'   # 1 file
```

Configuration outside the jar still can — the environment the sidecar
inherits, or a `config/` directory or `application.properties` in its working
directory — and whoever can write those is a same-user actor, whom the threat
model already puts out of scope. Until #574 the jar also carried
`application-dev.properties`, which did loosen them once the `dev` profile was
switched on (change log, 1.7). The draft `v0.1.0` tag at `d626a9b` predates
#574, so this paragraph holds for V0.1.0 only once it is cut at or after
`77abf9b`.

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
**count**, per-account id + `LogMasker.maskEmail` + provider name + server
host/port/SSL + auth-type name + last sync time + booleans (`active`,
`requiresReauth`, `lastErrorPresent`), folder sync state (UIDs), message
counts, JVM/runtime info, the backend's startup timings, and the latest
client-boot snapshot (below). Every folder but `INBOX`, whose name IMAP fixes,
is named by a pseudonym (`folder-<n>`, the same in both files that list
folders), and its `role` says which system folder it is (A3); the data, database and
log paths replace the user's home directory with `~`, so the Windows account
name does not travel either. The client-boot snapshot keeps its route only in
route-id shape (A2). `DiagnosticDumpPrivacyIT` fetches the dump over HTTP after
a real sync of two folders the user named — one of them with a role keyword in
its name, synced under the role detection gives it — a cached token and a
client-boot report sent from a route inside a user's folder, and finds none of
the canary values in any entry. **No**
credentials, OAuth tokens, message
bodies, subjects, or senders; the `lastError` text is reduced to a boolean.
[ClientBootDiagnosticsService](../backend/src/main/java/org/voxrox/mailbackend/core/diagnostic/ClientBootDiagnosticsService.java)
self-defends against its unauthenticated-shaped input: timing keys are
allow-listed, values range-bounded, text capped at 512 chars (the route also
loses its query and fragment, and is cut at its first segment that carries a
value — A2), and only the latest snapshot is retained (no unbounded growth).

## 6. Findings

### A1 (Low) — unbounded JSON write-body — FIXED 2026-07-09

**What.** The send and draft-save endpoints consume
`application/json` (`@RequestBody MailRequest` / `DraftRequest`), **not**
multipart, so `spring.servlet.multipart.max-file-size/​max-request-size=50MB`
does **not** apply to them. Before the fix, `body` had no length cap, the
`attachments` list had no count cap, and the only bound — per-attachment
`@Size(max = 70 MiB)` on `base64Data` — is evaluated by bean-validation **after**
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
the true pre-deserialization aggregate bound is not closed in code. It is not
even 50 × 70 MiB: the address fields (`to`, `cc`, `bcc`) and the threading
headers (`inReplyTo`, `references`) carry no `@Size` on either DTO, and
Jackson's own limits stop only a single string over 100 million characters,
with no limit on the whole document. Accepted for V0.1.0 for the same reason the finding is Low
(loopback + authenticated + same-user out of scope + client-enforced 25 MB
total). **Upgrade path:** a container-level `Content-Length` request-size filter
on the write endpoints (rejecting oversize bodies with 413 before Jackson
reads them) if the boundary is ever hardened toward a lower-trust caller.

### A2 (Low) — a user-named folder in the dump's client-boot route — FIXED 2026-09-27

**What.** The pseudonyms of §5 keep the name of a folder the user created out
of the dump's folder lists, but `client-boot.json`, in the same dump, carried
the route the client reported at its last boot: `window.location.pathname`,
with only the query and fragment cut on the backend. A boot re-runs from the
open page when the user restarts a failed backend, so a route like
`/mail/<accountId>/<encoded folder>/<stableId>` put the folder name back into
the bundle. Found by the verification pass over 1.8.

**Severity: Low.** No boundary is crossed: the dump is fetched behind the key
and exported by the user, and goes only where the user sends it. The harm is
that it carried a personal value that §5 and the privacy policy say it does
not — the same class of leak #518 closed for the folder lists.

**Fix (#577).** The client reports the SvelteKit route id
(`/mail/[accountId]/[folderName]/[stableId]`) instead of the path, and
[ClientBootDiagnosticsService](../backend/src/main/java/org/voxrox/mailbackend/core/diagnostic/ClientBootDiagnosticsService.java)
keeps a route only up to its first segment that is neither a lowercase static
word nor a `[param]` placeholder, so a path with values keeps its leading
static part (`/mail`). Regression tests: `DiagnosticDumpPrivacyIT` posts a
report from the folder route and finds the folder name, raw or URL-encoded, in
no entry; `ClientBootDiagnosticsServiceTest` and `clientBootDiagnostics.test.ts`
cover each half. The IT fails against the unfixed backend and the frontend test
against the unfixed client.

**Residual (accepted).** The backend guard judges a segment by its shape, so a
lowercase-only value ahead of the first id would pass. No route has one: every
route that carries values starts them with a numeric account or record id.
**Upgrade path:** a server-side list of the static segments, if a route ever
gains a free-text segment before its first id.

### A3 (Low) — a user-named folder kept by name in the dump's folder lists — FIXED 2026-09-27

**What.** Since #518 the dump kept the name of a folder with a role, on the
premise that the provider chose it. When a server does not mark a folder with
a special-use attribute, the role comes from the folder's own name instead
(`FolderRole.fromNameFallback`, a substring match): a folder the user called
"Newsletter from Dr Novak" is `NEWSLETTERS` whatever the server says, and one
called "Robinson" is `TRASH` when nothing else claimed Trash. The account pass
syncs such a folder under that role, and it went into
`folder-sync-states.json` and `message-counts.json` under its real name. Found
by the third verification pass over 1.8, shown at runtime.

**Severity: Low**, for the reasons A2 gives: no boundary is crossed, and the
dump carried a personal value that §5 and the privacy policy say it does not.

**Fix (#578).**
[DiagnosticDumpService](../backend/src/main/java/org/voxrox/mailbackend/core/diagnostic/DiagnosticDumpService.java)
keeps only `INBOX`'s name and gives every other folder a pseudonym. The stored
state cannot tell a provider's name from a user's, so this is the rule that
needs no such judgement; the `role` next to each pseudonym still says which
system folder it is. Regression tests: `DiagnosticDumpPrivacyIT` syncs a folder
named with a role keyword under the role `ImapFolderService` detects for it
(`NEWSLETTERS`, asserted) and finds the name in no entry;
`DiagnosticDumpServiceTest` checks the pseudonyms and the roles next to them.
Both fail against the unfixed service.

**Residual.** None in the dump. Support loses the provider's own spelling of a
system folder's name, which the role replaces.

## 7. Informational notes (no change required)

- **CORS `file://*` origin.** `corsConfigurationSource` allows five origin
  patterns — `http://localhost:[*]`, `http://127.0.0.1:[*]`,
  `http://tauri.localhost`, `tauri://localhost` and `file://*` — with
  `allowCredentials(true)`. `http://tauri.localhost` is the packaged Windows
  webview's origin and is why the installed app is not answered with 403 before
  the key filter runs; `tauri://localhost` is the same origin on macOS and
  Linux, kept for parity; there is no `https://` pattern. Not exploitable: API
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
- **Exception text reached the client (§3), until 1.9.** Through the
  mail-layer errors, an account's last error and the health endpoint's
  details. It had at every anchor until 1.9: the throw sites, and the last
  error a failed send stores, were already there at `d55b753`, the first.
  Not a finding, because of who receives it, and closed at 1.9 for the user's
  sake rather than for security: the text was a library's, in English, inside
  a Czech sentence. The recipient is whoever holds the API key — the app's own
  WebView, which shows the first two to the user whose mail server, provider
  or database produced them and never calls the health endpoint, or a
  same-user process, which the threat model puts out of scope and which can
  read the database and `mail.log`, where the handler and the sync log the same
  text, directly. The same holds for the working directory the health endpoint
  returns and the temp-file path of an attachment race. Versions up to 1.7 said the opposite; see 1.8 in the change log.
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

- **1.9** (2026-09-28) — **exception text kept out of responses** (#599).
  The mail layer and an account's last error carry a `MailFailureCause`,
  rendered in the reader's language, instead of a caught exception's text,
  which goes to the log only; the health endpoint shows statuses without
  details; the attachment temp-file race no longer reports its path (§3). §7's
  note says it held until 1.9. Verdict unchanged; drift acknowledged, anchor
  unchanged at `55fdd1b`.

- **1.8** (2026-09-27) — **re-verified against `55fdd1b`**, every claim,
  after the independent pass that [AUDIT_GUIDE.md](AUDIT_GUIDE.md) §5 requires
  found 1.7 wrong. 1.7 went into the verdict index and the threat model in the
  commit that wrote it; the pass, run afterwards, found four things. §3's "no
  exception message ... reaches the client" never held: mail-layer errors carry
  the underlying exception's text into `detail`. 1.7's new sentence,
  "`application.properties` is the only configuration file the jar carries",
  held only from #574 (`77abf9b`), not at the anchor `6365fae` 1.7 kept, and
  overstated twice: the jar carries other resources too, and configuration
  outside the jar can still loosen the error settings. The 1.7 entry below
  names two of the four error settings the dev profile set (it also set
  `include-exception=true` and `include-binding-errors=on_param`), calls a
  standalone `mvn spring-boot:run` its only documented use when the file's own
  header also offered `-Dspring.profiles.active=dev` on any run, and says no
  code under this audit's paths changed, which held for the diff of #574 only.
  And `Code paths` left out what §1, §3 and §4 rest on, which is why
  `check:audits` did not see #574. A first draft of 1.8 fixed those, found
  three more stale claims while re-verifying (§1 called the diagnostic dump
  documented in OpenAPI, which `springdoc.paths-to-exclude=/api/internal/**`
  has prevented since the initial import; §4 said a non-numeric MIME path
  segment ends in 4xx, and it ends in 500; §5 listed the dump's contents as a
  closed list that was not), and went to a second independent pass, which did
  not accept it either. It found a second path for exception text, an
  account's last error, through which SQL can reach the client (§3). It found
  **A2**: the client-boot route put a user-named folder into the dump (§6,
  fixed in #577). It found that the widened `Code paths` still saw only the
  files they name, so a profile file added or removed beside
  `application.properties` — the case of #574 — would still go unseen (the
  method statement now says what is knowingly left out, and §3 regenerates
  those lists), and
  that `Applies to V0.1.0` clashed with a draft tag that predates #574. It also
  found §1's keyless surface incomplete (Spring Security's own `/login`,
  `/logout` and stylesheet), the method statement's dynamic cover overstated,
  draft-send counted among A1's JSON-body endpoints, two endpoints missing from
  the bare-`stableId` list, both Tauri origins called the packaged one, and the
  scope paragraph still naming no audit for Boundary 1. A third pass over the
  result found **A3**: role detection also reads a role from a folder's own
  name, so a folder the user named "Newsletter ..." kept that name in the
  dump's folder lists (§6, fixed in #578). It found a third path for exception
  text, the health endpoint's details (§3), and the list of what `Code paths`
  leaves out still incomplete, which is why that list now goes section by
  section and the paths gain `AccountMapper`, `LogMasker` and the client half
  of A2. Smaller corrections from it: the CORS preflight among the keyless
  paths, the dynamic cover restated, the error page's third default
  (`include-path`), the bulk contact endpoints' own messages, A1's residual
  (the address and threading fields are unbounded, and the cap is 70 MiB),
  and, in the threat model, the B3 **S**, **T**, **I** and **D** rows. All of
  it is fixed here; the **S** row, which said the key filter rejects a request
  without the key, now says the default-deny refuses any request outside the
  public paths without the right key. The header gains the `Auditor` row §2 of
  the guide requires. The four enumeration commands give the same numbers (17,
  4, 12, 3); three new ones regenerate §3's lists. The statuses in §4 and §7
  come from throwaway MockMvc and HTTP probes that are not in the tree. A fourth
  independent pass accepted the result, reproducing the runtime claims and the
  regression tests' failures against the unfixed code; its minor corrections
  are applied here (preflights on any path, the wrong-key 401 only where the
  key filter is reached, an attachment race's temp path in §3, what the privacy
  IT does and does not cover, and more of what `Code paths` leaves out). The
  new anchor clears the acknowledgements recorded since 1.6. The verdict is
  unchanged: **PASS**.
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
