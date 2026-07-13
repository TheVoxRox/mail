# Compose draft lifecycle — redesign (send review, batch 3)

Status: **DECIDED 2026-07-13** — discard deletes the draft; no local mirror (server-side recovery instead); two PRs (backend contract first). Ready for PR-A.
Scope: the eight interacting findings from the send-path review ([PR #145](https://github.com/TheVoxRox/mail/pull/145) "Remaining → Batch 3"), plus two small items deferred from the batch-2 tail review.

## 1. Findings being addressed

| #   | Finding                                                        | Mechanism (verified against current code)                                                                                                                                                                                                                                                                                                         |
| --- | -------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 1   | Autosave has no dirty-check → zombie drafts                    | `createComposeAutosaveScheduler` debounces the _start_ of a save only; `ComposeDraftSaveCoordinator.saveDraft` never compares against the last saved fingerprint. Signature swaps / prefill artifacts can persist drafts the user never meant to keep.                                                                                            |
| 2   | Draft identity resolution is not serialized → duplicate drafts | `saveDraft` = list baseline → `POST` (202, no id) → poll `listDrafts` up to 5×500 ms and match by subject+hasAttachments (`drafts.ts#matchesDraft`). Two overlapping saves (autosave + manual, or slow save + next autosave) both see `replacesDraftId == null` → two drafts. The match heuristic also mis-binds when two drafts share a subject. |
| 3   | Discard doesn't delete an autosaved draft                      | `handleDiscardConfirmed` only resets `lastSavedSnapshot` and navigates; a draft autosaved 3 s earlier stays in Drafts forever.                                                                                                                                                                                                                    |
| 4   | Edited draft deleted before the async send outcome             | `handleSend` (edited-draft path) calls `deleteMessage(currentDraftId)` right after the **202** from `sendMail`. Delivery is async; a later `send_failed` leaves the user with no draft and no content (compose already unmounted).                                                                                                                |
| 5   | New-compose text held only in memory                           | Until the first autosave (3 s debounce + save latency) the text exists nowhere but component state; crash/close loses it. Same exposure after send: a `send_failed` for a brand-new message has no recoverable copy.                                                                                                                              |
| 6   | Wrong From account on mount-before-ready                       | `fromAccountId` initializes from `get(activeAccountId)`; if `accountsState` isn't `ready` yet the subscribe callback later defaults to `accounts[0]`, ignoring the active account the user was viewing.                                                                                                                                           |
| 7   | Bcc dropped on draft reopen                                    | `MessageEntity` has no bcc column, `MessageFetcher` never reads `RecipientType.BCC`, `MailDetailResponse` has no field, and `prefill.ts` hardcodes `bcc: ''`. The draft MIME on the server _does_ carry the header (`MimeMessageBuilder` writes it).                                                                                              |
| 8   | Pending-send toast race + silent 60 s fallback                 | `registerPendingSend` runs after the `sendMail` await; a fast SSE outcome arriving first finds no entry (`resolvePendingSend` → `''`), the outcome toast renders without a recipient and the pending toast lingers until the 60 s fallback silently dismisses it — the user never learns the result.                                              |

Deferred items from the batch-2 tail review absorbed here: a _failed_ attachment read never re-arms the skipped silent autosave (the save queue's dirty flag fixes this class); the stale "attachment still loading" form error (cleared naturally once errors render from queue state).

Explicitly **out of scope** (stays on the PR #145 list): SSE outcome buffering/replay on reconnect (architectural), and the `role="alert"` re-announcement quirk for repeated identical errors (app-wide form-error pattern).

## 2. Root causes

All eight findings trace to three structural decisions:

1. **The draft-save contract has no identity.** `POST /drafts` returns an empty 202 and the stableId "appears after the next sync" (DraftController javadoc). The client compensates with baseline-diff polling and a subject-match heuristic — fragile by construction, and impossible to serialize cheaply because each save takes seconds to resolve.
2. **Saves are free-running.** Nothing serializes autosave vs. manual save vs. the replaces chain; `busy` only covers manual paths.
3. **The lifecycle lives in component state.** Draft identity, unsent content and the pending-send handshake all die with the component unmount, so every flow that outlives the composer (async send outcome, crash, navigation) loses data.

## 3. Design

### B1 — Mint the draft identity at save time (backend)

`stableId` is already deterministic: `MessageStableId.compute` = SHA-256 of `accountId + " mid " + folder + " " + Message-ID` when a Message-ID exists. Drafts appended today _lack_ a Message-ID (only the send path calls `saveChanges()`; `Folder.appendMessages` does not generate one), which is the only reason the identity cannot be known up front.

Changes:

- `MimeMessageBuilder.build` (draft path) or `saveDraftAsync` assigns the Message-ID explicitly before the append (`message.saveChanges()`). Bonus: draft stableIds become re-download-stable, same as synced mail (the ghost-404 rationale in `MessageStableId`).
- `DraftController.saveDraft` resolves the Drafts folder name, computes the stableId **synchronously**, dispatches the async append, and returns `202 { "stableId": ... }`. (OpenAPI snapshot golden regenerates; the old empty-202 client contract remains compatible.)
- `ImapAppendService` uses `IMAPFolder#appendUIDMessages` (APPENDUID) for the Drafts append, and `saveDraftAsync` **upserts the local `MessageEntity` row immediately** after a successful append (identity fields + envelope; body/attachments reconcile on the next sync under the same stableId). This makes `?replaces=` chains and instant `?draft=` reopen work without waiting for a sync.
- Failure path unchanged: append failure keeps the previous revision, records `DRAFT_SAVE_FAILED`; a later `replaces=` pointing at a never-persisted id already degrades safely (logged, nothing expunged — batch-1 guard).

Client effect: `ComposeDraftSaveCoordinator` loses `resolveSavedDraftStableId`, `listDraftStableIds`, `matchesDraft`, the identity-pending state and both polling loops (~120 lines of heuristics). The MSW draft double becomes stateful and returns real ids (also fixes the "MSW always 202" quality note from PR #145).

### B2 — Send supersedes the draft server-side (backend)

`POST /messages/account/{id}/send` gains an optional `supersedesDraftId` parameter. The client stops calling `deleteMessage` after the 202. In `sendEmailAsync`, **after** `transport.sendMessage` succeeds (the existing "delivery is the point of no return" section), the backend hard-deletes the superseded draft, guarded by the batch-1 `isReplaceableDraft` ownership/folder check (never expunges non-drafts). A failed send therefore leaves the draft intact — finding 4 closed at the same altitude as the batch-1 fixes.

**Send-failure recovery draft (replaces the rejected local mirror):** when `sendEmailAsync` fails for a message with no superseding draft (a brand-new compose), the failure tail additionally best-effort appends the built message to Drafts (`MimeMessageBuilder` output is already in hand) before broadcasting `send_failed`. The failure toast then says the content is recoverable in Rozepsané. No duplicate risk in the success path — the append only runs on failure.

### B3 — Bcc round-trip (backend + frontend)

- `MessageEntity.recipientsBcc` + edit `V1__init.sql` in place (pre-release migration policy) + dev DB reset.
- `MessageFetcher` reads `RecipientType.BCC` alongside TO/CC.
- `MailDetailResponse.recipientsBcc` (OpenAPI golden + generated client types regenerate).
- `prefill.ts` consumes `detail.recipientsBcc ?? ''`.

Bcc only ever exists on the user's own drafts/sent copies, so no privacy change for received mail.

### F1 — Serialized save queue with dirty-check (frontend)

Replace the coordinator's free-running saves with a single-flight queue:

- At most one save in flight; requests during a save set a `dirty` flag; on completion the queue coalesces to **one** trailing save of the _latest_ draft.
- A save is skipped when the draft fingerprint equals the last successfully saved fingerprint (kills zombie-draft creation and no-op churn; `hasComposeContent` stays as the "never save an empty draft" floor).
- Debounce tuning: the _first_ save of a not-yet-persisted compose fires after ~1 s instead of 3 s, shrinking the only-in-memory window (finding 5) without a local mirror; steady-state stays at 3 s.
- The `replaces` chain is maintained inside the queue: the next save always uses the stableId returned by the previous one (B1 makes this synchronous and exact).
- `attachmentReading` integrates as a queue precondition instead of an ad-hoc guard: a blocked save leaves `dirty = true`, and the queue retries when reading settles — including the failed-read case that today drops the pending autosave.

### F2 — Compose session survives the component (frontend)

Move the lifecycle state (draft identity, save queue, last saved fingerprint) from `ComposeForm` into a `composeSession` store, so flows that outlive the component (async send outcome, replaces chain of a still-running save) keep working after unmount.

_(Decided: no localStorage draft mirror. Crash recovery is server-side only — the faster first autosave in F1 plus the send-failure recovery draft in B2 cover the practical cases; a crash inside the first second of typing is accepted residual risk.)_

**Discard (decided):** "Zahodit" deletes the current draft identity entirely — the new-compose zombie (finding 3) _and_ an opened `?draft=`; the confirm-dialog copy states it explicitly for the opened-draft case ("Koncept bude smazán.").

### F4 — From-account resolution (frontend)

In the accounts subscription: when `fromAccountId == null` and accounts become ready, prefer `get(activeAccountId)` if it exists in the list; only then fall back to `accounts[0]`. One-line ordering fix plus a unit test.

### F5 — Pending-send outcome parking (frontend)

- `notifications.ts` parks outcomes for unknown `sendId`s (small map with a short TTL). `registerPendingSend` first checks the parked map and resolves immediately — closes the race regardless of SSE timing.
- The 60 s fallback stops being silent: instead of dismissing the pending toast, it flips it to a warning ("Výsledek odeslání se nepodařilo ověřit — zkontrolujte složku Odeslané."), tone `error`-adjacent, announced. True reconnect/replay stays deferred as noted.

## 4. Decisions (owner, 2026-07-13)

1. **Discard semantics:** delete the draft entirely (option a) — dialog copy updated for the opened-draft case.
2. **Local crash-recovery mirror:** rejected — server-side recovery only (F1 debounce tuning + B2 send-failure recovery draft).
3. **Phasing:** two PRs. PR-A backend contract (B1+B2+B3, GreenMail IT, old client keeps working). PR-B frontend (F1+F2+F4+F5 + MSW/e2e rework) consuming the new contract.

## 5. Test plan

- **Backend IT (GreenMail):** append returns APPENDUID → local row upserted with computed stableId; `?replaces=` chain across three revisions leaves exactly one draft; send with `supersedesDraftId` deletes the draft only on delivered, keeps it on SMTP failure; failed send of a new message lands a recovery draft in Drafts; Bcc survives save → detail → reopen.
- **Frontend unit:** save queue (coalescing, dirty-skip, replaces chaining, attachmentReading precondition, failure retry, first-save debounce); outcome parking (outcome-before-register, register-before-outcome, TTL expiry); From-account resolution ordering.
- **e2e (functional):** autosave produces exactly one draft while typing continuously; discard removes the autosaved draft (both new-compose and opened-draft cases); send-failed toast points at the recovery draft; reopened draft round-trips Bcc.
- **MSW:** stateful draft store double (ids, replaces, verify-400/404 simulation — clears the PR #145 quality note).

## 6. Migration/compat notes

- `V1__init.sql` edited in place (no V2) + dev DB reset — pre-release policy.
- OpenAPI snapshot golden + generated frontend API types regenerate twice (B1 response body, B3 detail field).
- The 202-with-body change is backward compatible; the old identity-resolution client code is deleted in PR-B, not before.
