# Threading / Conversations — design proposal

> **HISTORICAL SNAPSHOT.** Design proposal whose Phase 1 shipped 2026-06-01;
> kept as the design-rationale record and not updated since. The shipping
> code, not this document, is authoritative for current behavior.

_Status: **Phase 1 implemented 2026-06-01.** Decisions on the six open
questions at the bottom of this document have been resolved as the
recommended defaults (1a backend-only, skip subject clustering, per-account,
defer search threading, minimal SSE payload, silent backfill). See the
"Phase 1 implementation summary" section just below for what landed._

## Phase 1 implementation summary (2026-06-01)

Backend-only ship per decision 1a — frontend types are generated but the
v0.1.0 UI does not yet consume them. Landed:

- **Schema** — `thread_id` / `thread_root_message_id` / `thread_position`
  columns on `messages` plus composite threading indexes. Originally shipped
  as `V2__add_threading.sql` (+ `V3` for the `in_reply_to` index); folded
  into `V1__init.sql` on 2026-06-12, pre-release with no production data —
  same consolidation as the earlier modseq migration (see CHANGELOG).
- **`MessageEntity`** carries the three columns with Javadoc explaining the
  rollout window where `thread_id` may be null.
- **`MailSummaryResponse` + `MailDetailResponse`** advertise `threadId` as
  nullable on the wire. JPQL projection in `MessageRepository` selects it.
- **`ThreadingService`** implements JWZ-light: direct In-Reply-To match,
  References walk oldest-to-newest (capped at 50), new thread fallback,
  late-arriving-parent reconciliation that merges orphan chains and
  broadcasts `thread_updated`. Subject clustering deliberately skipped.
- **`MessageDownloader.saveMessagesBatchAtomic`** invokes
  `ThreadingService.assignThread` after each persisted entity, inside the
  same transaction.
- **`ThreadingBackfillService`** runs once on `ApplicationReadyEvent`
  asynchronously, walks every active non-reauth account, and assigns
  `thread_id` to every row where it is null. Audit events
  `threading_backfill_started` / `threading_backfill_completed` /
  `threading_backfill_skipped_account` document the operation.
- **`ThreadResponse`** DTO + **`MailFacade.getThread(accountId, threadId)`**
  with ownership scoping via `account_id` in the query.
- **`GET /api/v1/messages/account/{accountId}/threads/{threadId}`** in
  `MailReadController` with `ProblemDetail` 401/404/500/503 responses.
- **`ThreadUpdated`** sealed permits added to `SseEvent`; SSE stream now
  advertises `oneOf: [SyncNotification, SendNotification, ThreadUpdated]`
  in the OpenAPI snapshot.
- **`POST /api/internal/threading/recompute?accountId=N`** (`@Hidden`)
  re-runs the backfill on demand for QA / support.
- **Frontend types** — `ThreadResponse` and `ThreadUpdated` exported via
  `lib/api/generated.ts`; hand-crafted `Local.ThreadUpdated` in
  `lib/types.ts` with `type-contract.ts` guard. SSE client whitelists
  `thread_updated` so the event survives parsing even though the v0.1.0
  UI does not yet listen.
- **Tests:** 12 golden ThreadingServiceTest cases (JWZ corpus), 3
  MailFacadeTest cases for `getThread`, 3 MailReadControllerTest cases for
  the endpoint, plus pre-existing tests updated for the new DTO signature.

What is **not** in Phase 1 (deferred per decisions 1a / 4):

- No UI for conversation grouping in V0.1.0. `MailSummaryResponse.threadId`
  is sent to the client but ignored.
- No thread-aware search (defer per decision 4).
- No Settings toggle, no thread row aria-tree rendering — those are V0.2.



## Problem

Today the mail list is flat. Reply chains, forwards and group conversations
appear as independent rows even though they share threading metadata
(`Message-ID`, `In-Reply-To`, `References` per RFC 5322 / RFC 5256). Most
modern clients (Gmail, Outlook, Thunderbird) group these into a single
"conversation" or "thread" entity.

User-visible features that depend on threading:

- One row per conversation in the inbox, expandable to show replies in tree
  order.
- Unread counter and bulk actions ("mark all read", "delete thread") scoped
  to the entire conversation.
- Conversation-aware search results.
- Reply-to-thread keeps the new message in the same conversation.

This document picks one approach, freezes the data shape and API contract,
and lists open questions that need to be decided before implementation
starts.

## Approaches considered

### Option A — Client-side JWZ algorithm

The backend keeps returning flat `MailSummaryResponse` rows. The frontend
loads enough messages, runs Jamie Zawinski's threading algorithm
([jwz.org/doc/threading.html](https://www.jwz.org/doc/threading.html))
in memory, and renders threads in the UI.

- **Pros:** zero backend changes; reference algorithm is well-understood
  (used by Thunderbird, Notmuch, mu); no DB migration.
- **Cons:** requires the entire account's headers in memory for stable
  threading; pagination is hostile (a thread might span pages); a new
  message that arrives later can rewrite the structure of already-loaded
  threads; bulk operations across a thread require a second round-trip to
  resolve members.

### Option B — Server-side JWZ with materialized `thread_id`

The backend computes thread membership at sync time, stores `thread_id` on
each message, and exposes new endpoints that operate at thread granularity.
The client never re-runs the algorithm.

- **Pros:** consistent state across clients (future-proof if there is ever a
  second device); pagination by thread works naturally
  (`ORDER BY MAX(thread_position) DESC`); bulk operations resolve in one
  query; thread membership survives restarts.
- **Cons:** schema migration (V2); needs backfill for any pre-existing rows;
  must handle late-arriving parents (a message arrives whose
  `Message-ID` matches a previously-orphan `In-Reply-To`) by merging two
  thread IDs.

### Option C — IMAP THREAD extension (RFC 5256) with fallback

For servers that advertise `THREAD=REFERENCES` capability (Gmail and
Outlook 365 do), issue `THREAD REFERENCES UTF-8 ALL` and cache the response.
For servers without the capability (Seznam, custom IMAP), fall back to
server-side JWZ.

- **Pros:** matches the threading users see in the provider's own web UI
  for the major providers.
- **Cons:** behavior varies by provider — same account on a different mail
  client may show different threads; threading is per-folder by default
  (Inbox vs Sent are split); `THREAD ORDEREDSUBJECT` and `THREAD REFERENCES`
  produce different shapes; cache invalidation on every mailbox change is
  expensive.

### Option D — Hybrid (server-side JWZ, no IMAP THREAD) ★ recommended

Compute `thread_id` server-side at sync time using a JWZ-light algorithm
that consumes only the three RFC 5322 threading headers. Do **not** use the
IMAP THREAD extension. Threading scope is per-account, not per-folder, so a
sent reply links back to the inbox thread.

- **Pros:** consistent behavior across providers (Seznam threads look the
  same as Gmail threads); no per-provider branch in the threading code;
  thread membership stable across syncs; pagination by thread works.
- **Cons:** for Gmail users our threads can diverge from what
  [mail.google.com](https://mail.google.com) shows in edge cases (Gmail
  uses additional signals like subject normalization and a custom
  `X-GM-THRID` header). This is acceptable — the desktop client is a
  separate UX.

**Recommendation: Option D.** Predictable, source-of-truth in the local DB,
no schema-level dependence on a provider extension that may not be there.

## Data shape

Three new columns on `messages`:

| Column | Type | Meaning |
|---|---|---|
| `thread_id` | TEXT (UUID) | Stable identifier of the conversation this message belongs to. Generated when a new thread root is detected. |
| `thread_root_message_id` | TEXT | RFC 5322 `Message-ID` of the root (oldest) message in the thread. Useful for late-arriving parent reconciliation. |
| `thread_position` | INTEGER | Ordinal position within the thread, ascending by `receivedAt`. Used for default sort within a thread without an extra `ORDER BY` join. |

`thread_id` is **per account**, not global — cross-account threading would
leak information across accounts (and is a privacy-sensitive choice the
user has not asked for).

## API surface

### Existing endpoints — additive change only

`GET /api/v1/mail/messages` (`MailReadController.list`) keeps returning the
flat shape. `MailSummaryResponse` gains one nullable field:

```json
{
  "id": 42,
  "stableId": "...",
  ...
  "threadId": "8b4...uuid"
}
```

When `threadId` is `null` the row is treated as a standalone thread of one
(missing `Message-ID`, or threading headers were unreadable — see the
follow-up of `MessageFetcher` for the unreadable case).

### New endpoint — thread detail

```
GET /api/v1/mail/threads/{threadId}
```

Returns ordered messages in the thread:

```json
{
  "threadId": "8b4...uuid",
  "rootMessageId": "<abc@gmail.com>",
  "messages": [
    { ...MailSummaryResponse, threadPosition: 1 },
    { ...MailSummaryResponse, threadPosition: 2 },
    ...
  ],
  "participantsTotal": 3,
  "unreadCount": 1
}
```

Authorization: same as `MailFacade.getMessageDetail` — caller's account
must own the thread (enforce by `account_id` on every row).

### Optional later — list grouped by thread

```
GET /api/v1/mail/messages?groupBy=thread
```

Returns one row per thread, with the **latest** message as a representative
plus `messageCount` and `unreadCount`. Phase 2 — not required for V1
shipping.

### Notifications

`SseEvent` gains a new variant `ThreadUpdated`:

```json
{ "type": "thread_updated", "threadId": "8b4...", "accountId": 7 }
```

Emitted when:

- A new message lands and is attached to an existing thread.
- An existing message changes thread (late-arriving parent reconciliation).
- A message in a thread is deleted (so the UI can update its unread count).

## DB schema delta

Three columns + threading indexes on `messages` (today part of
`V1__init.sql`; originally a separate `V2__add_threading.sql`, folded
pre-release):

```sql
thread_id              TEXT,
thread_root_message_id TEXT,
thread_position        INTEGER

CREATE INDEX idx_messages_account_thread
  ON messages (account_id, thread_id);

CREATE INDEX idx_messages_account_thread_root
  ON messages (account_id, thread_root_message_id);

CREATE INDEX idx_messages_account_message_id
  ON messages (account_id, message_id);

CREATE INDEX idx_messages_account_in_reply_to
  ON messages (account_id, in_reply_to);
```

No backfill in the migration itself — see backfill plan below.

Forward compatibility: `thread_id IS NULL` is a valid state (unthreaded /
not yet computed). Existing code that paginates by `receivedAt DESC` keeps
working with no changes.

## Algorithm sketch

`ThreadingService.assignThread(MessageEntity msg, AccountEntity account)`
runs at the end of `MessageDownloader.saveMessagesBatchAtomic`, before
commit:

1. **Resolve direct parent.** If `msg.inReplyTo` is set, query for a sibling
   message in the same account whose `messageId` matches. If found:
   - `msg.threadId = parent.threadId`
   - `msg.threadRootMessageId = parent.threadRootMessageId`
   - `msg.threadPosition = max(threadPosition) over thread + 1`
2. **Walk References** (oldest to newest). If no direct parent, try each
   ID in `msg.references` — the first that matches a known message
   becomes the parent (Gmail behaviour). Same assignment as step 1.
3. **No parent found — new thread.**
   - `msg.threadId = UUID.randomUUID()`
   - `msg.threadRootMessageId = msg.messageId` (or, if `messageId` is
     null, the thread is a singleton — we still create a UUID so the
     summary endpoint can reference it).
   - `msg.threadPosition = 1`
4. **Late-arriving parent reconciliation.** After step 3, check whether any
   existing message has an `inReplyTo` that points at this message's
   `messageId` (or is a cross-folder duplicate already rooted at it). If yes,
   those messages (and their entire current thread) are re-pointed to this
   message's thread and re-rooted to the thread's true root, then the merged
   thread is renumbered. Emit a `thread_updated` SSE event per affected
   `thread_id`.
   - This step is bounded: at most one orphan chain can collapse per
     incoming message.
   - **Implementation note.** The lookup
     (`MessageRepository.findMergeableOrphanThreadIds`) matches
     `in_reply_to = :messageId OR thread_root_message_id = :messageId`, both
     indexed, so it stays cheap on every arrival during a bulk sync. A child
     that links to the parent *only* through `references` (no `in_reply_to`)
     is not back-reconciled — a token match inside the free-text `references`
     column is unindexable and would make bulk sync O(n²). The forward
     References walk in steps 1–2 still threads those children once their
     ancestor is present, so the residual gap is a message that arrives
     strictly before any ancestor and carries `references` but no
     `in_reply_to` — rare in practice.

The two-phase shape (direct + walk references + late reconciliation)
matches the published JWZ algorithm minus the subject-based clustering
step. Subject clustering is intentionally skipped — it produces false
positives on common subjects ("Re: Hi"), and the modern Message-ID /
References chain is well-implemented across all providers we target.

## Edge cases

- **Missing `Message-ID`.** Treat as singleton: new `thread_id`,
  `thread_root_message_id = null`. Document the trade-off in the response
  schema. (Late reconciliation can never find this message as a parent.)
- **Self-loop / circular References.** Walk References with a `visited`
  set. Bound at 50 ancestors per message (`MimePartExtractor` already uses
  `MAX_DEPTH = 20` as a precedent).
- **Cross-folder threading.** Inbox + Sent + Drafts are all in the same
  account, so the lookup in step 1 is naturally cross-folder. Trash
  participates while it exists; Spam does not (different conversation
  semantically).
- **Deletion.** When the last message of a thread is deleted, the thread
  itself ceases to exist. No housekeeping needed — the `thread_id` is
  orphaned but `idx_messages_account_thread` returns empty.
- **Account deletion.** ON DELETE CASCADE removes all thread rows with the
  account (no separate `threads` table to clean up).
- **`requires_reauth` accounts.** Threading stays on existing data; new
  messages stop arriving so the thread state is frozen until re-auth.
- **Future cross-account threading.** Out of scope — would leak account
  isolation. Re-evaluate only if the product explicitly grows a unified
  inbox feature.

## UI implications

- **Mail list view.** Default behind a Settings toggle ("Group by
  conversation"). Off by default in V1 ship — keeps the migration
  user-visible-zero-risk. Flip to on by default in a later release.
- **Thread row collapsed.** Shows latest message's subject + sender (or
  "N replies"), unread count badge, latest `receivedAt`.
- **Thread row expanded.** Tree of messages in `thread_position` order.
- **Bulk actions.** Selecting a thread selects all messages in it.
  Delete-thread is a single backend call:
  `DELETE /api/v1/mail/threads/{threadId}`.
- **Reply.** Composing a reply sets the new message's `inReplyTo` to the
  selected message's `messageId` (already implemented). The new sent
  message will land in the same thread after sync.
- **Accessibility.** Per the project memory, the screen-reader contract
  matters. Each thread row needs an `aria-label` ("Conversation, 5
  messages, 2 unread, last from Alice"); expand/collapse needs
  `aria-expanded`; tree of children inside expanded thread needs
  `role="tree"` / `role="treeitem"`. Will require an a11y test pass
  alongside the feature.

## Backfill

The Flyway migration only adds columns. Backfill runs separately:

```
POST /api/internal/threading/recompute?accountId=<id>
```

Internal endpoint (gated by `X-API-KEY` like `/api/internal/health`),
idempotent. Walks every message in the account in `receivedAt` ascending
order, runs `ThreadingService.assignThread` against it.

For a dev DB the user can also wipe and re-sync — but the backfill endpoint
exists so production users (when v0.x is released to users) do not have to
lose data on the threading rollout.

Backfill cost on a representative account (Inbox ≈ 5000 messages): two SQL
lookups per message via `idx_messages_account_thread_root` ≈ a few seconds.

## Test plan

- **Unit:** `ThreadingServiceTest` with golden fixtures — borrow Mu/Notmuch
  threading test cases:
  - simple parent → child
  - References chain over 5 levels
  - missing parent, late reconciliation
  - circular References
  - missing Message-ID singleton
- **Integration:** `MailSyncServiceIT` — sync 50 IMAP messages including
  replies (use the existing test resource `application-it.properties` flow),
  assert thread structure matches.
- **E2E (frontend):** new `routes/mail/threading.functional.e2e.ts` —
  toggle "Group by conversation", assert collapsed/expanded rendering,
  bulk action scope.
- **A11y:** `routes/a11y.e2e.ts` extension — `getByRole('tree')` for
  expanded thread, `aria-expanded` state, `aria-label` of thread row.

## Open questions for the user

1. **Ship in which phase?** Three options:
   - (a) V1 includes the schema + ThreadingService + flat API with
     `threadId` field, frontend ignores it (just stores). Ship the UI in
     V2. Lowest-risk rollout.
   - (b) V1 includes everything end-to-end with the Settings toggle off
     by default. Power users opt in.
   - (c) V1 ships nothing here; threading is a v0.2 feature.
2. **Subject normalization fallback.** The proposal skips it for false-
   positive risk. Confirm? Or do we want a `THREAD ORDEREDSUBJECT` style
   fallback when threading headers are entirely missing?
3. **Thread scope.** Per-account is recommended. Confirm we never want per-
   folder threading (the alternative would surface Inbox and Sent as two
   threads for the same conversation, which most users find confusing).
4. **Thread-aware search.** Returns threads or messages? Recommendation:
   message-level (thread context appears as the thread row in results).
   Confirm or defer.
5. **`thread_updated` SSE event payload.** Should it include the latest
   message's `MailSummaryResponse` for immediate UI patching, or just the
   `threadId` requiring the frontend to refetch? Frontend YAGNI sweep
   precedent suggests the minimal payload (`threadId` only) — confirm.
6. **Backfill UX.** Internal `/api/internal/threading/recompute` is the
   technical hook. Should the frontend expose a Settings button
   ("Reorganize conversations") that calls it, with a progress indicator?
   Or run it transparently in the background on first start after an
   upgrade?

## Phasing summary

- **Phase 1 (V0.2 backend prep):** threading schema (now part of
  `V1__init.sql`), ThreadingService, API
  additions (`threadId` on summary, `/threads/{id}` endpoint,
  `thread_updated` SSE event). Tests at all three layers. Frontend ignores
  threading data.
- **Phase 2 (V0.3 UI):** Settings toggle, mail list grouping, thread
  detail view, bulk action wiring, a11y pass.
- **Phase 3 (V0.4 polish):** thread-aware search, conversation count in
  folder badge, backfill UX.

---

_Decisions on the open questions above unblock Phase 1 implementation.
This document is a proposal — none of the code or schema changes have been
committed._
