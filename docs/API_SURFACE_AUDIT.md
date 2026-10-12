# VoxRox Mail — Sidecar HTTP API Surface Audit

|                    |                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                               |
| ------------------ | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **Version**        | 1.15                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                          |
| **Date**           | 2026-10-12                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                    |
| **Applies to**     | VoxRox Mail V0.1.0, cut at or after the audited commit — the draft `v0.1.0` tag (`d626a9b`) predates #574, #577, #578, #623 and #700 (§1, §3, §5, §6)                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                         |
| **Audited commit** | `5def679` (every claim re-verified 2026-10-12, clearing eight acknowledgements at the ledger's cap; 1.14 anchor `55f4afa`, re-verified 2026-10-05; 1.8–1.13 anchor `55fdd1b`, re-verified 2026-09-27)                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                         |
| **Code paths**     | `backend/src/main/java/org/voxrox/mailbackend/feature/mail/controller`, `backend/src/main/java/org/voxrox/mailbackend/feature/account/controller`, `backend/src/main/java/org/voxrox/mailbackend/feature/contact/controller`, `backend/src/main/java/org/voxrox/mailbackend/core/clientconfig`, `backend/src/main/java/org/voxrox/mailbackend/core/diagnostic`, `backend/src/main/java/org/voxrox/mailbackend/core/system`, `backend/src/main/java/org/voxrox/mailbackend/core/security`, `backend/src/main/java/org/voxrox/mailbackend/core/config/SecurityConfig.java`, `backend/src/main/java/org/voxrox/mailbackend/core/init/HandshakeService.java`, `backend/src/main/java/org/voxrox/mailbackend/exception`, `backend/src/main/java/org/voxrox/mailbackend/feature/mail/service/AttachmentService.java`, `backend/src/main/java/org/voxrox/mailbackend/feature/mail/dto`, `backend/src/main/java/org/voxrox/mailbackend/feature/contact/dto`, `backend/src/main/java/org/voxrox/mailbackend/feature/account/dto`, `backend/src/main/java/org/voxrox/mailbackend/feature/account/mapper/AccountMapper.java`, `backend/src/main/java/org/voxrox/mailbackend/util/LogMasker.java`, `backend/src/main/resources/application.properties`, `frontend/src/lib/api/clientBootDiagnostics.ts`, `frontend/src-tauri/src/logs.rs` |
| **Auditor**        | Claude (1.0 in #134; 1.8 re-verified by Claude Opus 5.5; 1.14 re-verified by Claude Opus 5.5, a separate agent from the author of 1.9–1.13; 1.15 re-verified by Claude, an independent agent and not the author of #700 or #701) + owner review                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                               |
| **Subsystem**      | Sidecar REST API — Boundary 3 of [SECURITY_THREAT_MODEL.md](../SECURITY_THREAT_MODEL.md)                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                      |
| **Verdict**        | **Security: PASS** for the HTTP API, with **one Low finding open: A4**, raised by the 1.15 pass — the shell's Save logs ZIP carries the logs without the redaction the diagnostic dump applies (§6). **A1** (unbounded JSON write-body) is fixed, its pre-deserialization residual accepted. **A2** and **A3** (a user-named folder in the dump) were fixed on 2026-09-27 and are **superseded by the owner's decision of 2026-10-12** that what the user's own log holds may go to the developers the user sends the bundle to (§5, §6). Informational notes recorded. The default-deny of §1 was passed by the session an OAuth sign-in leaves in the browser: **B2-1** (High), fixed 2026-10-05 and confirmed by the independent verification pass, recorded in [OAUTH_AUDIT.md](OAUTH_AUDIT.md).                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                          |

Per-subsystem release audit of the **loopback HTTP API** the Tauri WebView calls:
authentication (`X-API-KEY`), request authorization, input validation on every
controller, error-response hygiene, attachment streaming (path traversal), and
the diagnostic/data-exposure endpoints. Method: static trace of the Spring
Security filter chain, enumeration of all 18 `@RestController`s (13 public +
the 5 `/api/internal` ones) and their request DTOs, and a data-flow check of
the two highest-risk paths (attachment download, diagnostic dump). The audit
relies on these tests as dynamic cover: a request without the key answered
302 (`SecurityDispatcherTypeTest`); a wrong key answered 401 with an audit
record before the rest of the chain, and the right one granted the key's
authority (`ApiKeyFilterTest`, since #583); the session an OAuth sign-in leaves
refused without the key, ended by `/success`, and the key still accepted
(`OAuth2SignInSessionTest`, B2-1); the dump's privacy on real data
(`DiagnosticDumpPrivacyIT`, §5; the `~` in the dump's paths is covered by
`DiagnosticDumpServiceTest`, since the IT's data directory need not sit under
the home directory); what the dump's copy of the logs keeps and what it
replaces (`DiagnosticDumpServiceTest`, `DiagnosticLogRedactorTest`, §5); the
bounds on a client error report (`ClientErrorReportServiceTest`, §5); the
statuses of requests Spring MVC rejects (`FrameworkRequestRejectionTest`, §7)
and of a malformed attachment part path (`MailReadControllerTest`, §4); the
regression tests each finding in §6 names; and the throwaway probes the 1.8 and
1.14 change log entries describe. Other claims rest on a static trace, even
where a unit test also covers them (several §2 bounds have one). Nothing
measures the timing of the constant-time comparison, and no test sends a client
error report through the security chain: that it needs the key rests on the
trace of §1.

**Method (1.14).** Every file under `Code paths` read in full against
`55f4afa` (`git ls-files` on the pathspec lists them), and the whole delta
since the previous anchor (`git diff 55fdd1b 55f4afa -- <Code paths>`) read as
one change rather than as the eight acknowledgements it had been recorded in;
the four enumeration commands below and the three of §3 re-run. Measured with
throwaway tests that are not in the tree: a `@SpringBootTest` driving a real
sign-in (provider faked by WireMock, as `OAuth2SignInSessionTest` does) through
MockMvc and over HTTP on the sidecar's own port, then calling the API with the
session it left, with and without the key (§1); and a `@WebMvcTest` of the
attachment download's 404 through the async dispatch the streamed response
takes (§4).

**Method (1.15).** Every file under `Code paths` read in full against
`5def679`, and the whole delta since the previous anchor
(`git diff 55f4afa 5def679 -- <Code paths>`) read as one change rather than as
the eight acknowledgements it had been recorded in: the attachment lookup of
B1-12 (#630, #634), a `SecurityConfig` javadoc (#636), the draft save keeping
its typed recipients before the 202 (#641), two keys added to
`application.properties` (#650, #695), the PBKDF2 iteration count made a
constant (#698), and #700, which turned the diagnostic dump into a support
bundle carrying the logs and added `POST /api/internal/client-errors`. #700
is most of that delta, and its acknowledgement said itself that the audit's
text no longer described it; §1, §2, §5, §6 and §7 are rewritten against it. The Save logs path #701 added to the shell was read
too, because it is the dump's stand-in when the backend does not start (A4).
The four enumeration commands below and the three of §3 were re-run. Static
only: this pass built nothing and ran no test or probe. Where it cites a test,
that test passed in the CI run of `main` at `5def679`, `DiagnosticDumpPrivacyIT`
among them.

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
- §5: `StartupTimingService`, `ImapConnectionManager`'s pool statistics,
  `AccountLastErrorCode` (the arguments the dump keeps of a last error),
  `backend/src/main/resources/logback-spring.xml` (the CR/LF guard a client
  error's log line relies on), and the client that sends those reports
  (`frontend/src/lib/api/clientErrors.ts`), whose every field the backend bounds
  again.
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
echo "$C" | wc -l                              # 18 controllers
echo "$C" | xargs grep -l '/api/internal' | wc -l   # 5 internal (13 public)
echo "$C" | xargs grep -l '@Validated' | wc -l      # 12 @Validated
echo "$C" | xargs grep -l '@Hidden' | wc -l         # 4 hidden from OpenAPI
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
  header passes through and is left to the default-deny
  below — unauthenticated, unless it carries a session an OAuth sign-in
  authenticated (next bullet). The comparison is
  constant-time (`MessageDigest.isEqual` over SHA-256 digests of both sides — no
  length or timing leak). The key is per-JVM random, in-memory only, written to
  `session.json` (user-profile ACLs), never persisted to the DB.
- **Default-deny.** [SecurityConfig](../backend/src/main/java/org/voxrox/mailbackend/core/config/SecurityConfig.java)
  ends the chain with `anyRequest().hasAuthority(API_CLIENT)`, an authority
  only `ApiKeyFilter` grants, and only for the right key (`authenticated()`
  until #623, see below). The `PUBLIC_ENDPOINTS`
  allow-list is minimal and intentional: the OAuth flow (served by the system
  browser without a key, integrity-protected by state + PKCE), the two static
  `auth-*.html` pages, `/error`, and the springdoc paths, which serve nothing
  in the sidecar: `springdoc.api-docs.enabled` and `springdoc.swagger-ui.enabled`
  default to `false` in `application.properties`, and the `repackage` execution
  in `backend/pom.xml` leaves springdoc out of the jar altogether
  (`excludeGroupIds`). The `ASYNC`/`ERROR` dispatcher types
  are permitted — they are server-internal continuations of an
  already-authorized request and cannot be triggered from outside the container.
  **Corrected in 1.12:** `authenticated()` accepts any authenticated token, and
  the OAuth flow's own session holds one — the `OAuth2AuthenticationToken`
  Spring keeps there after a successful sign-in, for the session's lifetime.
  A request carrying that session's cookie therefore passes the default-deny
  without the key. Recorded once, as **B2-1** (High), in
  [OAUTH_AUDIT.md](OAUTH_AUDIT.md) §3. **Fixed at 1.13:** the rule is now
  `anyRequest().hasAuthority(API_CLIENT)`, an authority only `ApiKeyFilter`
  grants, so the session's token authorizes the keyless paths above and
  nothing else (403), and `/success` ends the session when the flow ends.
  "Behind the key" below holds for every request again. **Confirmed at 1.14**
  by the independent pass, measured: after a sign-in, the session without the
  key was answered 403 on every path outside the allow-list that was tried —
  the account, contact, readiness, client-config and SSE endpoints, the
  account `PUT` and `DELETE`, all four `/api/internal` endpoints including
  `health`, and an unmapped path — over HTTP as through MockMvc; with the right
  key 200, with a wrong one 401. The keyless pages below still answered it. Over
  HTTP, the cookie the callback set redirected to `/login` once `/success` had
  run, the session gone. A request without the key to any other path, and with
  no session, is redirected to `/login` (302), not answered 401; one carrying
  only the sign-in's session is answered 403.
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
  client-errors, actuator `health`) is not in the allow-list, so it inherits
  the key's authority rule. None of them is in the OpenAPI document:
  `springdoc.paths-to-exclude=/api/internal/**` leaves the whole prefix out,
  as the committed snapshot
  (`backend/src/test/resources/openapi/api-docs.json`) shows. The four
  support/QA hooks — client-boot, client-errors, threading recompute and
  correspondent rebuild — also carry `@Hidden`, which is what the fourth
  enumeration command counts; the diagnostic dump does not, and is left out
  all the same. That client-errors (`POST /api/internal/client-errors`, since
  #700) needs the key rests on this trace alone: its controller test runs
  without the security filters, and the 1.14 probes predate it.
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
  (`PUBLIC_ENDPOINTS` plus `anyRequest().hasAuthority(API_CLIENT)` behind the key), so
  moving a path changes no posture.

## 2. Input validation (confirmed)

Twelve of the 18 controllers are `@Validated` — every one that declares
constrained parameters. The six without it give bean validation nothing to
enforce: four expose parameterless endpoints (system readiness, client-config,
diagnostic-dump, the SSE notification stream) and the other two take a DTO
without constraints and bound it field by field in the service instead:
client-boot and client-errors (§5, §7). Path/query params
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
- **Requests Spring MVC rejects before a controller runs** (since #581): a
  parameter of the wrong type is a 400 naming the parameter, not its value; an
  unmapped method, an unsupported body type, an unacceptable `Accept` and any
  other binding failure get 405, 415, 406 or 400 with a fixed bundle text,
  whose only argument is the request's own method on a 405, and with the
  headers the exception carries (`Allow`, `Accept`). The exception's text goes
  to the log, at WARN. A binding failure that is the app's own,
  `MissingPathVariableException`, stays with the catch-all.
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
  and `messageArgs` in English, inside the Czech sentence. An exception thrown
  with no cause carries a message key of its own: in the mail layer since 1.10
  — the OAuth sign-in and token messages, the OAuth-without-TLS guards, a
  missing folder for a role, named as the folder list names it — or, for a
  guard on the app's own state the user cannot act on, the unexpected cause;
  and since 1.11 every `ResourceNotFoundException` and `ValidationException`
  as well. Until then each passed the English sentence the app wrote for its
  log, with ids in it, as the `{0}` of a generic key. The constructors that
  took a message alone are gone, so a throw site that names no key does not
  compile.

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
  path. It must match `AttachmentService.PART_PATH_PATTERN`, positive part
  numbers joined by dots, twice: by `@Pattern` on the endpoint, a 400 before
  the facade is called, and again in the service before the folder executor
  (since #582). `findPartByPath` then `Integer.parseInt`s each segment (one too
  large for an `int` finds no part) and only ever indexes into the children of
  a `jakarta.mail` `Multipart` or descends into an encapsulated message, and a
  message whose body is not multipart has that body as its part 1 — it never
  touches the filesystem. A path that leads nowhere is a
  `ResourceNotFoundException` naming the attachment
  (`error.attachment.partNotFound`), answered 404 through the async dispatch the
  streamed response takes (measured, 1.14). Until #582 a non-numeric segment
  ended in a 500 `MAIL_CONNECTION_ERROR` and a missing part in a 404 naming the
  folder.
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

## 5. Diagnostic dump — no credential, token, key or content; addresses masked (confirmed; policy changed 2026-10-12)

[DiagnosticDumpService](../backend/src/main/java/org/voxrox/mailbackend/core/diagnostic/DiagnosticDumpService.java)
(`GET /api/internal/diagnostic-dump`, behind the key) builds the ZIP the user
downloads and may send to the developers. Since #700 it is a support bundle
rather than a list of counters, and what it may carry was settled by the
owner on 2026-10-12: **what the user's own log holds may go to the developers
the user sends the bundle to**, folder names, account names and paths
included. What may not go is what no log may hold: a password, an OAuth
token, the session API key, a message's body or subject, an unmasked address.
The pseudonyms that fixed A2 and A3 are withdrawn under that decision, as
policy and not as a regression (§6).

What the bundle carries, entry by entry:

- `summary.json`, `accounts.json`, `folder-sync-states.json` and
  `message-counts.json`, read in one read-only transaction: app, API and
  schema versions, counts, IMAP pool stats and the cached-token **count**; per
  account its id, `LogMasker.maskEmail` of its address, provider name, server
  host/port/SSL, auth-type name, last sync time, the booleans `active`,
  `requiresReauth` and `lastErrorPresent`, and since #700 its last error **by
  code and the arguments that code names** (a `MailFailureCause` name, a
  folder, a provider). An argument the code does not name is left out, and so
  is the stored text, which is an exception's. Folders are listed under their
  own names next to their `role`, UIDs and message counts. A database that
  cannot be read costs these four entries only, and the manifest names the
  failure by its exception class.
- `runtime.json`: Java and OS versions, memory, the database and WAL file
  sizes, free disk space, active profiles, the loopback address and port, and
  the data, database and log directories with the user's home directory
  replaced by `~`.
- `client-boot.json` (below) and `startup-timings.json`.
- `threads.txt`: every thread, virtual ones included (`DiagnosticThreadDump`).
  The JDK writes that dump only to a file, so it goes through a temp directory
  of its own, deleted in a `finally`; a crash in between leaves stack frames in
  the system temp folder, which nothing sweeps.
- `logs/`: the logs folder as it is on disk (`DiagnosticLogExport`) —
  `mail.log`, `audit.log` and `mail-frontend*.log` with their rotated files,
  newest first, within a 24 MB budget of text, a rotated `.gz` decompressed.
  They hold what the logs hold: folder and account names, request paths,
  absolute paths with the Windows account name, and the text a server or a
  library wrote into an exception.
- `manifest.json`: whether the database could be read, for each log file its
  size, what was written and whether it was cut or left out, and how many
  replacements of each kind the redactor made.

**The redactor is a safety net, not the control.** The control is log hygiene:
the app's own log sites mask an address and log no token or key (the
log-hygiene records in
[backend/SECURITY_RELEASE_CHECK.md](../backend/SECURITY_RELEASE_CHECK.md)).
`DiagnosticLogRedactor` catches what those sites do not control, chiefly an
address a server or a library wrote into an exception's text — its own
documentation names `550 <someone@example.org> unknown` from an SMTP server.
Every line of `threads.txt` and `logs/` passes through it on the way in, and
it replaces the session's API key (`[api-key]`), the token shapes
`frontend/scripts/lib/log-scan.mjs` scans for (`[token]`), and an address,
masked as `LogMasker` masks one. The JSON entries do not pass through it; they
are built from fields, and the one address field is masked (§7). It matches
shapes, so what it does not recognize passes: an address URL-encoded in a
logged request path (`%40`), a token of another shape, or the key of an
earlier session, which authorizes nothing.

**Tested on real data.** `DiagnosticDumpPrivacyIT` fetches the dump over HTTP
with the key after a real sync of `INBOX` and of a folder the user named, with
a cached OAuth token and a client-boot report sent from a route inside that
folder. It finds none of its canaries — the account's address, login and
password, a sender's address, a subject, a body, the token and the session's
API key — in any entry, `logs/` included, and asserts that the folder's name
and the account's name are there and that the boot route kept only `/mail`.
Whether that run writes a canary into the log on disk is not asserted, so the
IT alone does not show the redactor at work; `DiagnosticDumpServiceTest` (a log
holding a server's address, the key and a token) and
`DiagnosticLogRedactorTest` do. The IT sends no client error report. The
release gate `npm run tauri:smoke:sidecar` scans every entry of a packaged
sidecar's dump for the key, its account's address and password, and the log
scan's shapes.

[ClientBootDiagnosticsService](../backend/src/main/java/org/voxrox/mailbackend/core/diagnostic/ClientBootDiagnosticsService.java)
self-defends against its unauthenticated-shaped input: timing keys are
allow-listed, values range-bounded, text (the client version included, since
#700) capped at 512 chars, the route loses its query and fragment and is cut
at its first segment that is not shaped like a route id's, and only the latest
snapshot is retained (no unbounded growth). Under the owner's decision the
route cut is no longer a privacy control, since the logs carry full request
paths; it stays so that one page reads the same in every report.

**Client error reports** (`POST /api/internal/client-errors`, since #700,
behind the key, §1).
[ClientErrorReportService](../backend/src/main/java/org/voxrox/mailbackend/core/diagnostic/ClientErrorReportService.java)
writes one `[CLIENT]` WARN line into `mail.log`, which is how an error in the
webview reaches the bundle; it writes nothing to `audit.log` and keeps nothing
but its rate window. Every field is cut before it is logged — the message to
1000 characters, the stack to 4000 with its frames joined on one line, the
source to 300, the error's name to 100, at most five context entries of 100
characters under keys of letters and digits — the `kind` is allow-listed, an
event id is kept only in its own shape, and the route and a page source are
cut by the same route sanitizer as client-boot's (a script under `/_app/` keeps
its path and loses its query). At most 20 reports a minute are written, and
the dropped ones are counted in one line once the minute ends. That bounds what
reaches the log, not what is accepted: a report past the limit is still read
and answered 202. Like every JSON body here, the request is deserialized whole
before anything is cut — a `context` map of any size and strings up to the
limit A1's residual names — which is that residual (§6). The CR/LF guard of
`logback-spring.xml` keeps a field from forging a log line. What the client
writes into an error's message is logged as sent, cut; on its way into the
bundle the redactor masks an address in it.

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
(both assert 400 + no service call); since #583 also
`DraftControllerTest.saveDraftBodyTooLong` and `saveDraftTooManyAttachments`.

**Residual (accepted).** The per-field `@Size` caps run post-deserialization, so
the true pre-deserialization aggregate bound is not closed in code. It is not
even 50 × 70 MiB: the address fields (`to`, `cc`, `bcc`) and the threading
headers (`inReplyTo`, `references`) carry no `@Size` on either DTO, and
Jackson's own limits stop only a single string over 100 million characters,
with no limit on the whole document. The two internal diagnostic POSTs have
the same shape (since 1.15): client-boot and client-errors take a DTO without
constraints, which Jackson reads whole before the service cuts each field, and
client-errors' `context` map has no entry limit until that cut (§5). Accepted
for V0.1.0 for the same reason the finding is Low
(loopback + authenticated + same-user out of scope + client-enforced 25 MB
total). **Upgrade path:** a container-level `Content-Length` request-size filter
on the JSON endpoints (rejecting oversize bodies with 413 before Jackson
reads them) if the boundary is ever hardened toward a lower-trust caller.

### A2 (Low) — a user-named folder in the dump's client-boot route — FIXED 2026-09-27, SUPERSEDED 2026-10-12

**What.** The pseudonyms §5 described at the time kept the name of a folder
the user created out of the dump's folder lists, but `client-boot.json`, in
the same dump, carried the route the client reported at its last boot:
`window.location.pathname`, with only the query and fragment cut on the
backend. A boot re-runs from the open page when the user restarts a failed
backend, so a route like
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

**Superseded by the owner's decision of 2026-10-12.** What the user's own log
holds may go to the developers the user sends the bundle to, and since #700 the
bundle carries the logs, which name folders in full, in request paths and sync
lines alike (§5). The value A2 kept out is no longer one the bundle withholds,
so A2 is closed by that decision rather than by its fix: a withdrawal of
policy, not a regression, since nothing the decision forbids leaves this way.
The fix stays in the code for another reason — the client reports the route
id, and the backend cuts a route to route-id shape, a client error's route and
page source as well, so one page reads the same in every report. Its tests
changed with it: `DiagnosticDumpPrivacyIT` still posts a report from the folder
route and asserts the route `/mail`, but asserts that the folder's name **is**
in the dump; `ClientBootDiagnosticsServiceTest` and
`clientBootDiagnostics.test.ts` still cover each half of the cut.

### A3 (Low) — a user-named folder kept by name in the dump's folder lists — FIXED 2026-09-27, WITHDRAWN 2026-10-12

**What.** Since #518 the dump kept the name of a folder with a role, on the
premise that the provider chose it. When a server does not mark a folder with
a special-use attribute, the role comes from the folder's own name instead
(`FolderRole.fromNameFallback`, a substring match until #580 and a whole-name
match since, which a folder the user names exactly as a system folder still
meets): a folder the user called
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
the user named under the role `ImapFolderService` detects for it and finds the
name in no entry — at #578 a name with a role keyword in it, detected as
`NEWSLETTERS`; since #580, which made such a name a user folder, one named
exactly `Newsletters` (`NEWSLETTERS`, asserted);
`DiagnosticDumpServiceTest` checks the pseudonyms and the roles next to them.
Both fail against the unfixed service.

**Residual.** None in the dump. Support loses the provider's own spelling of a
system folder's name, which the role replaces.

**Withdrawn by the owner's decision of 2026-10-12** (A2, §5). Since #700
`DiagnosticDumpService` writes every folder under its own name in
`folder-sync-states.json` and `message-counts.json`, next to its role, and an
account's last error names its folder in full. A withdrawal of policy, not a
regression of the fix. The tests were turned around with it:
`DiagnosticDumpPrivacyIT` no longer syncs a folder named `Newsletters` and
asserts that the user's folder name is in the dump, and
`DiagnosticDumpServiceTest` checks that folders go in under their own names,
the same in both files, next to their role. The residual above went with the
pseudonyms.

### A4 (Low) — the shell's Save logs ZIP carries the logs without the dump's redaction — OPEN 2026-10-12

**What.** #701 gave the boot error screen **Save logs** for a backend that does
not start. The Tauri command `export_logs`
([logs.rs](../frontend/src-tauri/src/logs.rs)) copies every regular file of
the logs folder byte for byte, newest first up to 64 MB, into a ZIP in the
user's Downloads folder. It stands in for the dump in the one case the dump
cannot serve and carries the same logs, but none of §5's safety net: no line
passes a redactor. The logs on disk can hold what §5 says no bundle may carry,
chiefly an address a server or a library wrote into an exception's text — the
case `DiagnosticLogRedactor` exists for (a failed send is logged with its
stack, and a server's rejection of a recipient commonly quotes the address).
No test covers the ZIP's content beyond the copy itself. The privacy policy
describes this ZIP accurately ([PRIVACY.en.md](../PRIVACY.en.md): "exactly as
they are on disk", and its Czech twin the same); the owner's rule
of 2026-10-12 that an unmasked address does not leave holds for the bundle and
not for it. Found by the 1.15 pass. The code is the shell's, outside this
audit's subject; it is recorded here because §5 is where the repo states what a
diagnostic artifact may carry, and `logs.rs` joins `Code paths` with it.

**Severity: Low**, for the reasons A2 gives: no boundary is crossed without
the user, who makes the file and decides who gets it, and every value in it is
on the user's own disk already. The harm is that a support artifact can carry
a third party's address unmasked, which the rule for the bundle says it does
not. No token or key is known to reach the logs — the app's own log sites log
none (the log-hygiene records), and the key of an ended session authorizes
nothing — so in practice the missing safety net matters for addresses.

**Fix.** None yet: this pass changes no code, and the choice is the owner's.
Either closes it — apply the dump's replacements in `logs.rs` before a line
enters the ZIP (the address and token shapes, a rotated `.gz` decompressed
first as `DiagnosticLogExport` does, and the current key from `session.json`
when there is one), or accept it explicitly and say in the privacy policy's
Save logs paragraph, in both languages and on voxrox.org, that an error a
server returned can carry another person's address unmasked.

**Residual.** Until then a Save logs ZIP can carry the unmasked address of a
recipient or sender that a server's or library's error text named.

**Upgrade path.** As under Fix. If both exports redact, one set of shapes
tested against both implementations keeps them from drifting apart.

## 7. Informational notes (no change required)

- **CORS `file://*` origin.** `corsConfigurationSource` allows five origin
  patterns — `http://localhost:[*]`, `http://127.0.0.1:[*]`,
  `http://tauri.localhost`, `tauri://localhost` and `file://*` — with
  `allowCredentials(true)`. `http://tauri.localhost` is the packaged Windows
  webview's origin and is why the installed app is not answered with 403 before
  the key filter runs; `tauri://localhost` is the same origin on macOS and
  Linux, kept for parity; there is no `https://` pattern. **Corrected in
  1.12:** this note called the list unexploitable because API auth is a custom
  `X-API-KEY` **header**, not a cookie, which a cross-origin page cannot obtain
  (random port + key in `session.json`), and "credentials" in the CORS sense
  only gate the OAuth session. That session is the gap: once a sign-in
  completes, its cookie authenticates the API as well (§1, B2-1 in
  [OAUTH_AUDIT.md](OAUTH_AUDIT.md)), and for that cookie the
  `http://localhost:[*]` pattern with credentials is what lets a page on another
  `localhost` port read the answers. Since B2-1's fix (1.13, confirmed at 1.14)
  the API refuses that cookie, so the list is back to gating the OAuth flow
  only; narrowing it and dropping `file://*` remain tightenings of their own.
  The 403 such a request now gets still carries
  `Access-Control-Allow-Origin` for the `localhost` origin (measured at 1.14),
  which shows the page that its request was refused and nothing else.
- **Runtime paths in the dump — closed by #518, allowed again by the owner's
  decision of 2026-10-12.** `runtime.json` used to carry the absolute data,
  database and log paths, which contain the OS username, and this note accepted
  that as the price of a support artifact the user chooses to share. #518
  wrote them relative to `~`, and `runtime.json` still does (§5). Since #700
  the bundle's `logs/` carries absolute paths regardless — the backend logs
  where it wrote `session.json`, the shell its data root — which that decision
  allows. The note stays so the reasoning that accepted it does not get
  re-applied to something the decision does not cover.
- **`ClientBootDiagnosticsController` and `ClientErrorReportController` have no
  `@Valid`.** Cosmetic — neither DTO carries a bean-validation constraint, and
  each service bounds every field (§5), so nothing is unenforced.
- **The JSON entries of the dump are not redacted** (1.15). The redactor of §5
  runs over `threads.txt` and `logs/` only; the JSON entries are built from
  fields, and their one address field is masked. A folder name is a field the
  user chose and goes in as it is under the owner's decision, so a folder named
  after an address carries that address unmasked in `folder-sync-states.json`,
  `message-counts.json` and a last error's `folder`, while the same name comes
  out masked in a log line that names it plainly. Not a finding: the value is the user's own label, and
  the decision lets folder names go. Passing folder names through the redactor
  would make the two agree.
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
- **Framework-level request errors reached the catch-all, until #581.** A
  request Spring MVC rejects before the controller runs — a path variable that
  is not a number, an HTTP method the path does not map — had no handler of its
  own, so the catch-all answered it: 500 with the fixed message, and a
  `CRITICAL` log line with the stack trace. Hygiene held, since nothing but the
  fixed text went out; the status was wrong and the log noisy. Since #581 each
  gets its own status (§3), which `FrameworkRequestRejectionTest` covers; the
  attachment path of §4, the same kind of untidiness, was closed by #582. The
  note stays because it was true at every anchor up to `55fdd1b`.

## 8. References

- [SECURITY_THREAT_MODEL.md](../SECURITY_THREAT_MODEL.md) — Boundary 3 STRIDE matrix.
- [CONTENT_RENDERING_AUDIT.md](CONTENT_RENDERING_AUDIT.md) — Boundary 4 companion audit.
- [backend/SECURITY_RELEASE_CHECK.md](../backend/SECURITY_RELEASE_CHECK.md) — per-release security gate.

## 9. Change log

- **1.15** (2026-10-12) — **re-verified against `5def679`**, every claim, by an
  agent that is not the author of #700 or #701, clearing the eight
  acknowledgements recorded since 2026-10-05 at the ledger's cap. The delta
  since `55f4afa` was read as one change: the attachment lookup of B1-12
  (#630, #634), a `SecurityConfig` javadoc (#636), the draft save keeping its
  typed recipients before the 202 (#641), two keys in `application.properties`
  (#650, #695), the PBKDF2 iteration count as a constant (#698), and #700,
  which made the dump a support bundle carrying the logs and added
  `POST /api/internal/client-errors`. The four enumeration commands give 18, 5,
  12 and 4 — client-errors is the one more controller, internal and `@Hidden`,
  than at 1.14 — and §3's three commands the same as before (0 throw sites, 5
  sites in 3 services, 1 file). Static only: nothing was built, run or probed,
  and the tests cited passed in CI on `main` at `5def679`. Rewritten against
  #700: §1 names client-errors behind the key and among the `@Hidden` hooks,
  and says that rests on the static trace; §2 counts it with client-boot among
  the controllers that bound their DTO in the service; §5 describes the bundle
  entry by entry, the owner's decision of 2026-10-12 on what it may carry, the
  redactor as a safety net and what it does not catch, what the privacy IT does
  and does not show, and the bounds on a client error report; A1's residual
  names the two diagnostic POSTs; A2 and A3 are marked superseded by that
  decision, their history kept; §7's runtime-paths note says the logs carry
  absolute paths, and a new note says the JSON entries are not redacted. §5
  also described the privacy IT syncing three user folders, one named
  `Newsletters`; since #700 it syncs `INBOX` and one. **A4 (Low, open)**
  found: the shell's Save logs ZIP of #701 carries the logs without the
  redaction (§6), and `frontend/src-tauri/src/logs.rs` joins `Code paths`.
  Verdict: **PASS, with A4 (Low) open**.
- **1.14** (2026-10-05) — **re-verified against `55f4afa`**, every claim, by
  an agent other than the author of 1.9–1.13, clearing the eight
  acknowledgements recorded since 2026-09-27 at the ledger's cap (#628). The
  delta since `55fdd1b` was read as one change: whole-name folder roles (#580),
  the statuses of requests Spring MVC rejects (#581), validated attachment part
  paths (#582) and the content lookup of B1-12 (#592), the cause codes and
  message keys of 1.9–1.11 (#599, #607, #608), and B2-1's fix (#623). The four
  enumeration commands give the same numbers (17, 4, 12, 3), and §3's three
  commands the same (0 throw sites, 5 sites in 3 services, 1 file). **B2-1's
  fix confirmed**, measured with a throwaway sign-in through the real chain,
  over HTTP as well as MockMvc (§1): its session is refused with 403 on every
  path outside the allow-list tried, `/success` ends it, and the key, the SSE
  stream, the internal endpoints and the CORS preflight work as before.
  Corrected, each true at the old anchor and superseded since: §4 still
  described the part path as ending in a 500 or a folder's 404, where since
  #582 a malformed path is a 400 before the facade and a missing part a 404
  naming the attachment (measured through the async dispatch); §7's note on
  framework errors reaching the catch-all, closed by #581, which §3 now
  describes; §5 and A3's regression test named a role-keyword folder detected
  as `NEWSLETTERS`, which #580 made a user folder, so the IT now uses a folder
  named exactly `Newsletters`; and the method statement said nothing tests the
  wrong-key 401 or the `DraftRequest` caps, which #583 covers (A1 gains its
  tests). Added: the 1.14 method, the 403 in §1, and the CORS header on that
  403 (§7). Verdict unchanged: **PASS**.
- **1.13** (2026-10-05) — **B2-1 fixed** (recorded in
  [OAUTH_AUDIT.md](OAUTH_AUDIT.md) 1.4): the default-deny requires the
  authority `ApiKeyFilter` grants instead of any authentication, so the
  session an OAuth sign-in leaves no longer passes it; §1 and §7 follow, and
  the verdict no longer points to an open finding. Under this audit's paths
  `core/security/ApiKeyFilter.java` and `core/config/SecurityConfig.java`
  change; anchor unchanged at `55fdd1b`, the drift acknowledged in
  `docs/audit-freshness.json`.
- **1.12** (2026-10-04) — **§1 and §7 corrected** after the OAuth audit's
  re-verification found B2-1 (High, open) (#621): the session an OAuth
  sign-in leaves in the system browser satisfies the default-deny without
  the key. §1 said a request without the header is unauthenticated, and §7
  called the CORS list unexploitable because API auth is a header, not a
  cookie; neither holds for a request carrying that session. The finding is
  recorded once, in [OAUTH_AUDIT.md](OAUTH_AUDIT.md) §3, and the verdict
  points to it. No code under this audit's paths changed; anchor unchanged
  at `55fdd1b`.

- **1.11** (2026-10-03) — **every not-found and validation error names a key
  of its own** (#608). The message-only constructors of
  `ResourceNotFoundException` and `ValidationException` are removed, and the
  sites that used them name a key: a missing message, draft, conversation,
  folder or contact address, a message gone from the server, the OAuth
  callback outside a login flow, and the drafts page size (§3). The listing
  command 1.10 added is gone with them; it also counted keyed multi-line
  calls. Verdict unchanged; drift acknowledged, anchor unchanged at
  `55fdd1b`.

- **1.10** (2026-10-03) — **the mail layer's own English sentences kept out
  of responses** (#607). A mail exception without a cause carries a message
  key of its own or the unexpected cause, and the constructors that passed the
  app's English sentence as `{0}` are removed; `ResourceNotFoundException` and
  `ValidationException` still pass one, with the command that lists them (§3).
  Verdict unchanged; drift acknowledged, anchor unchanged at `55fdd1b`.

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
