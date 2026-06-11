# OpenAPI surface audit — 2026-06-01

_Snapshot used: `backend/src/test/resources/openapi/api-docs.json` (35 paths,
46 operations, 39 schemas). All findings below are **before** any fix._

## Resolution log

| ID | Finding | Status | Reference |
|---|---|---|---|
| A1 | SSE stream advertises `SseEmitter` | **Fixed** 2026-06-01 | `NotificationController` rewritten with explicit `@ApiResponse(oneOf={SyncNotification, SendNotification})`; both DTOs land in `components.schemas`. FE `generated.ts` + `type-contract.ts` extended. |
| A2 | SSE description mentions only `sync_completed` | **Fixed** 2026-06-01 | `@Operation(description=...)` now enumerates all three events. |
| A3 | "Electron mail client" in OpenAPI info | **Fixed** 2026-06-01 | `OpenApiConfig.java:57,64` → "Tauri desktop client" / "desktop client". |
| A4 | 8 endpoints with drift between actual status and snapshot | **Fixed** 2026-06-01 | Explicit `@ApiResponse(202)` / `@ApiResponse(204)` added on every `ResponseEntity`-returning controller method that drifts. `void` + `@ResponseStatus` cases were already correct. |
| B1 | Duplicate folder-list endpoint | **Fixed** 2026-06-01 (B1-a) | Path-variant `getMessagesByFolderPath` removed; e2e helper migrated to query-variant; controller test URLs updated; snapshot path count 35 → 34. |
| B2 | `PATCH /accounts/{id}` has no FE caller | **Fixed** 2026-06-01 (B2-Vol3) | Public API surface trimmed: `partialUpdateAccount` controller method, `AccountPatchRequest` DTO, `AccountService.patchAccount` + `applyServerConfigPatch` helper, 15 test scenarios, the `validation.account.patchProviderXorCustom` i18n key and all frontend echoes (`generated.ts`, `type-contract.ts`, `lib/types.ts`, msw fixtures + handlers) removed. Snapshot schemas 39 → 38. If a future partial-edit UX is needed, `git show` on the removal commit restores the proven logic + tests verbatim. |
| B3 | `StreamingResponseBody` leak on attachment download | **Fixed** 2026-06-01 | `MailReadController.downloadAttachment` gained explicit `@ApiResponse(200, content = @Content(mediaType = "application/octet-stream", schema = @Schema(type = "string", format = "binary")))`. `StreamingResponseBody` no longer appears in `components.schemas` (count 40 → 39). |
| B4 | `operationId = "stream"` too generic | **Fixed** 2026-06-01 (as part of A1) | Renamed to `streamNotifications` while rewriting `NotificationController.stream()`. |
| C1 | "Mail Providers" tag not nested under "Accounts" | **Open / preference** | Consistent across the project (`Contacts`, `Drafts`, `Folders` also nest in URL but are top-level tags). Leave unless Swagger UI grouping is redesigned. |
| C2 | `POST /test-connection` returns 200 | **Not a bug** | Probe, not a create. False-positive of the audit heuristic. |

Open follow-up items: C1 (preference-only — Swagger UI grouping convention).
All A-tier and B-tier findings are resolved; the audit is closed for v0.1.0.

## Post-audit additions — Threading Phase 1 (2026-06-01)

After the audit closed, Threading Phase 1 added one public endpoint and two
schemas:

- `GET /api/v1/messages/account/{accountId}/threads/{threadId}` — conversation
  detail. ProblemDetail 401/404/500/503 wired via the global customizer plus
  an explicit `@ApiResponse(200)` and `@ApiResponse(404)`. Tagged under the
  existing `Messages — Read` group.
- New schema `ThreadResponse` (operation response body).
- New schema `ThreadUpdated` (SSE event variant).
- `NotificationController.stream` `oneOf` extended from
  `[SyncNotification, SendNotification]` to
  `[SyncNotification, SendNotification, ThreadUpdated]`.

Snapshot delta: paths 34 → 35, schemas 38 → 40. Internal endpoint
`POST /api/internal/threading/recompute` is `@Hidden` and correctly stays
out of the public spec.



The audit cross-references three sources:

1. The OpenAPI snapshot (which `OpenApiSnapshotTest` locks down).
2. The live Spring `@RestController` annotations (`/api/v1/...` endpoints
   discovered by `grep` on `*Controller.java`).
3. The frontend caller wrappers in `frontend/src/lib/api/*.ts` (every
   `api.get/post/put/patch/delete`, plus `apiRaw` and `buildApiUrl`).

## Findings

### A — Real drift that ships wrong contract (must fix before first release)

#### A1. SSE stream returns the framework class `SseEmitter` instead of the event payloads

**Endpoint:** `GET /api/v1/notifications/stream`
**Snapshot says:** `text/event-stream` payload is `#/components/schemas/SseEmitter`.
**Reality:** the stream emits one of three event types — `sync_completed`,
`send_completed`, `send_failed` — modeled in Java as the sealed `SseEvent`
interface (records `SyncNotification` and `SendNotification`). Neither
appears in `components.schemas`.

**Impact:** the frontend cannot generate types for SSE events and ships its
own hand-written `StreamNotification` in `lib/types.ts` that can silently
diverge from the backend. The audit found that `KNOWN_EVENT_TYPES` in
`lib/api/notifications.ts` already lists all three event names, but the
matching TS shapes are not validated against any contract.

**Recommended fix:**
- Mark `SseEmitter` as `@Schema(hidden = true)` (or filter it out via the
  springdoc operation customizer).
- Document the stream as `oneOf: [SyncNotification, SendNotification]` via
  an explicit `@ApiResponse` on `NotificationController.stream()`.

#### A2. SSE description is outdated

**Where:** `NotificationController.stream()` `@Operation(description = ...)` ⇒ snapshot:

> "Opens a Server-Sent Events stream. The client receives 'sync_completed'
> events after every folder sync that produced new messages. Heartbeat
> every 30 s."

This description survives from before the send-notification refactor
(captured in `CHANGELOG.md`). It does not mention `send_completed` /
`send_failed`. New users reading the spec will think send results never
arrive on this channel.

**Recommended fix:** update the `@Operation(description = ...)` to enumerate
the three event types and link them to their schemas.

#### A3. "Electron" leaks into the OpenAPI info and security scheme

**Where:** `core/config/OpenApiConfig.java:57` and `:64`.

```java
.description("REST API for the Electron mail client — accounts, messages, drafts, folders.")
...
.description("Internal API key that the Electron client loads from session.json and sends in the X-API-KEY header.")
```

The project moved to Tauri; the OpenAPI spec still advertises Electron. The
snapshot inherits the same wording (lines 5 and 4251).

**Recommended fix:** rename "Electron" → "Tauri" (or the locale-neutral
"desktop client") in both places, regenerate snapshot.

#### A4. HTTP status codes drift between controller and snapshot

springdoc auto-detection defaults the success status to `200` even when the
controller actually returns `ResponseEntity.accepted()` (202),
`ResponseEntity.noContent()` (204), or has `@ResponseStatus(NO_CONTENT)`
on a `ResponseEntity`-returning method.

| Endpoint | Actual | Snapshot |
|---|---|---|
| `POST /api/v1/messages/account/{accountId}/send` | 202 (`ResponseEntity.accepted()`) | 200 |
| `POST /api/v1/messages/account/{accountId}/sync` | 202 | 200 |
| `POST /api/v1/accounts/{accountId}/drafts` | 202 | 200 |
| `POST /api/v1/accounts/{accountId}/drafts/{stableId}/send` | 202 | 200 |
| `DELETE /api/v1/messages/{stableId}` | 204 (`ResponseEntity.noContent()`) | 200 |
| `DELETE /api/v1/accounts/{id}` | 204 (`@ResponseStatus(NO_CONTENT)` on `void`-returning) | 200 |
| `DELETE /api/v1/accounts/{accountId}/contacts/{contactId}` | 204 | 200 |
| `DELETE /api/v1/accounts/{accountId}/contacts/{contactId}/emails/{emailId}` | 204 | 200 |
| `DELETE /api/v1/accounts/{accountId}/contacts/bulk` | not verified — likely 200 (returns body) | 200 ✓ |

**Impact:** the frontend's generated types include the wrong status code in
the response map. Right now the call sites use the result as `void` and
discard the status, so nothing visibly breaks. But typed clients written
against this spec (in any language) will be wrong, and the snapshot is
unreliable as a contract artifact.

**Recommended fix:** add explicit `@ApiResponse(responseCode = "202", ...)`
on async POSTs and `@ApiResponse(responseCode = "204")` on DELETEs that
return `ResponseEntity`.

### B — Probably wrong / cleanup candidates

#### B1. Duplicate folder-list endpoint

`MailReadController` exposes both:

- `GET /api/v1/messages/account/{accountId}/folder?folderRef=...` (query param)
- `GET /api/v1/messages/account/{accountId}/folder/{folderRef}` (path param)

Their `@Operation(summary, description)` strings are byte-identical, the
two methods delegate to the same `private getMessages(accountId, folderRef, page, size)`.
The production frontend uses only the query-param version
(`mailRead.ts:32`); the path-param version is exercised only by the E2E
helper `routes/e2e-helpers.ts:136`.

**Recommended fix:** keep the path-param variant (URL-cleaner, no need to
URL-encode unusual `folderRef` values into query params), redirect the
frontend caller to it, then remove the query-param variant. Or invert if
you prefer the query-param shape. Either way, do not ship two synonymous
endpoints in the first public spec.

#### B2. `PATCH /api/v1/accounts/{id}` is dead public surface

Backend has all of:

- `AccountController.partialUpdateAccount` (PATCH)
- `AccountPatchRequest` DTO
- `AccountService.patchAccount(id, request)`
- OpenAPI tag, schema, error responses

Frontend has zero callers. `grep "api.patch" frontend/src/lib/api/` returns
only `contacts.ts` (×2) and `mailAction.ts` (flags). Account edits in the
UI go through PUT, sending all fields.

**Recommended fix:** either wire the frontend up to use PATCH for partial
edits (smaller payloads, cleaner audit log entries — see `AccountService`
which already logs differently for PUT vs PATCH), or delete the backend
PATCH + DTO + service method. Picking one removes the dead surface.

#### B3. Attachment download advertises `StreamingResponseBody`

**Endpoint:** `GET /api/v1/messages/{stableId}/attachments/{partPath}`
**Snapshot says:** response schema is `#/components/schemas/StreamingResponseBody`
(another Spring framework class).

**Impact:** less severe than A1 — the frontend already calls this via
`apiRaw` and converts the result to a `Blob` (`mailRead.ts:downloadAttachment`).
But the snapshot misleads anyone reading the spec.

**Recommended fix:** annotate the controller with an explicit
`@ApiResponse(responseCode = "200", content = @Content(mediaType = "application/octet-stream", schema = @Schema(type = "string", format = "binary")))`
or add a springdoc customizer that rewrites `StreamingResponseBody`
references to binary payload.

#### B4. `operationId = "stream"` is too generic

Every other operationId in the spec is descriptive (`listAccounts`,
`patchContact`, `searchMessages`, ...). `NotificationController.stream()`
is the only one with a single common verb. Client generators that pivot
on operationId can collide if a second `stream` endpoint shows up later.

**Recommended fix:** rename to `streamNotifications` (matching tag) or
`subscribeNotifications`.

### C — Preference, low priority

#### C1. "Mail Providers" tag sits under `/accounts/providers/*` path but isn't grouped under "Accounts"

Swagger UI presents `Mail Providers` as a sibling of `Accounts`, even though
the URL hierarchy nests under `accounts/`. The other route nested under
`accounts/` (`Contacts`, `Drafts`, `Folders`) all have their own top-level
tags too, so this is **consistent within the project** — but if you ever
want Swagger UI grouped by URL prefix, it does not.

**Recommended fix:** leave as-is unless you redesign the Swagger UI
grouping. The pattern is consistent across all `Accounts`-nested routes
right now.

#### C2. `POST /accounts/test-connection` returns 200 OK

This is correct — it's a probe, not a resource creation. Listed only because
my heuristic flagged "POST returning 200" but this one is intentional.

## Operations that the snapshot does NOT include (and shouldn't)

The internal endpoints are correctly hidden from the public spec:

- `POST /api/internal/client-boot` — `ClientBootDiagnosticsController` has `@Hidden`
- `GET /api/internal/diagnostic-dump` — `DiagnosticDumpController` is in the
  `core/diagnostic` package and is gated by the `/api/internal/...` path
  prefix together with springdoc default path filtering

**Verify before release:** if the production sidecar is ever built with
`spring.application.openapi.expose-internal=true` (no such flag exists
today — flagging for future), confirm the internal endpoints stay hidden.

## What the audit confirmed is clean

- **39 schemas, 0 orphans** — every `components.schemas` entry is reachable
  from at least one operation. No leftover schemas from the
  Faze 6.11/6.13/6.16 dead-code cleanup.
- **`ProblemDetail` is wired on every operation** via the global
  `OperationCustomizer`. 401/500 are baseline; 400 on body-validating
  endpoints; 503 on `feature.mail.*` endpoints.
- **All 12 tags have root-level descriptions** in `OpenAPI.tags` —
  Swagger UI sidebar is fully documented.
- **No duplicate operationIds.**
- **Frontend api/*.ts caller surface matches the snapshot** — every
  endpoint in the spec has a frontend caller (with the exception of B2
  above). No callers reference paths that the snapshot does not declare.

## Recommended phasing

1. **Pre-first-push:** fix A1, A2, A3, A4 (drift from reality — would
   require a snapshot regeneration anyway). These are the items that
   advertise the wrong contract today.
2. **Pre-first-release:** decide B1 (drop duplicate folder endpoint) and
   B2 (drop dead PATCH or wire up frontend). These change the public API
   surface, so they are easier to do before any user installs.
3. **Optional polish:** B3, B4, C1 — quality-of-life for spec readers,
   no behavior change.

After any fix, regenerate the snapshot with
`mvn -Dopenapi.snapshot.update=true test -Dtest=OpenApiSnapshotTest` and
re-run the frontend `npm run check:api` to refresh `schema.d.ts` and
`generated.ts`.
