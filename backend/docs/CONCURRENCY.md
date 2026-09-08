# Concurrency inventory — backend

Every piece of shared mutable state, who mutates it, and under which guard.
Half of the bugs found in the 2026-06 reviews were threads disagreeing about
one of the rows below — when adding shared state, add it here; when touching
one of these, re-read its row first.

## Locking rules (the short version)

1. **All IMAP `Store`/`Folder` work goes through
   `ImapConnectionManager.executeWithLock`, and every caller names its lane.**
   The pooled `Store` is not thread-safe; the fair `ReentrantLock` per
   **(account, lane)** is the only thing serializing protocol access. (Enforced
   by `ArchitectureTest` — no class outside `feature.mail.service` may even
   reference `jakarta.mail.Store`.)
   - **Two lanes per account, two connections, two locks.**
     `Lane.INTERACTIVE` carries work a user is blocked on — message body and
     attachment fetches, a cold folder listing, the server count behind "page X
     of Y", reading a draft to send it. `Lane.BACKGROUND` carries sync cycles,
     backfill, maintenance, and the server-side half of actions whose local
     write already happened. A wrong lane is not a compile error and shows up
     only as latency, so the parameter is mandatory rather than defaulted.
   - **What a lane must not carry.** Anything long-running belongs on
     BACKGROUND, or the interactive lane rebuilds the queue it exists to
     remove. And a read-modify-write over the local mirror stays on BACKGROUND
     even when a user waits for it: the connection lock is what serializes it
     against the sync cycle, and moving it would bring back the concurrent
     `(account, folder, uid)` insert from the v0.1.0 smoke (bug F). That is why
     `MailSyncService.fetchServerCountAndEnsurePageLocally` is split — count
     interactive, download background.
   - **A second connection can be refused** (providers cap simultaneous
     sessions). `ImapConnectionManager` then re-runs the action on BACKGROUND —
     after releasing the interactive lock, never while holding it — and skips
     the interactive lane for `mail.client.imap.interactive-lane-retry-after`
     (default 5 m), because a failing connect costs a full timeout plus retries
     and paying that per message opened would be worse than having no second
     lane. `mail.imap.lane.fallback` counts it.
   - **`executeWithLockOrSkip` is still there**, for a narrower reason: waiting
     on a lock raises nothing and logs nothing, so a caller that can answer from
     the DB should do that rather than queue behind another request on its own
     lane. Callers pass a short timeout (`mail.client.imap.role-lookup-timeout`,
     default 1 s). Today's only caller is the conversation listing's
     trash/junk/drafts/sent role lookup (`MailFacade.conversationExcludedFolders`),
     which falls back to a folder-scoped listing. `mail.imap.lock.skipped` counts
     it; since the split it measures contention **within** one lane, not a read
     waiting on a sync.
2. **Lock order: `SyncLockManager` (non-blocking tryLock) → account connection
   lock → OAuth refresh lock.** The IMAP path takes the refresh lock _inside_
   the connection lock (`createNewConnectedStore → getAccessToken`); the SMTP
   path takes the refresh lock alone. Never acquire the connection lock while
   holding a refresh lock — that inverts the order and can deadlock against a
   concurrent IMAP connect.
   - **Never hold both lanes' connection locks at once.** The lanes are
     siblings, not levels: with a thread able to hold one and wait for the
     other, the order stops being a line and becomes a graph. The lane fallback
     is written the way it is for exactly this reason — it releases the
     interactive lock and only then calls back in on BACKGROUND. Clearing both
     lanes (`removeConnection`) locks them one after another, never together.
3. **Never hold a SQLite write transaction across a network wait or a lock
   wait.** SQLite is single-writer: one blocked transaction stalls every write
   in the app. Precedent: `AccountService.deleteAccount` defers
   `purgeAccount` (which waits on the connection lock, potentially minutes)
   until `afterCommit`.
4. **Connection lock map entries are never removed** (per lane, so up to two
   per account). Removing a lock while another thread waits on it lets a third
   thread mint a fresh lock via `computeIfAbsent` and enter the critical section
   concurrently. A `ReentrantLock` per deleted account is a few dozen bytes;
   both `ImapConnectionManager.accountLocks` and
   `OAuth2TokenService.refreshLocks` follow this policy. The interactive-lane
   cooldown map is the exception and may shrink — it holds no lock, only an
   expiry instant.
5. **Status flags written from async error paths use targeted
   `@Modifying` UPDATEs** (`updateRequiresReauth`, `updateLastError`,
   `clearLastError`) — no entity load, no optimistic-lock conflicts with a
   concurrently running sync.
   - **Raising `requires_reauth` publishes `AccountRequiresReauthEvent`**, and
     `AccountReauthEventListener` closes the account's pooled connections off
     `mailEventExecutor`. All three writers do it, each for its own reason:
     `OAuth2TokenService` cannot call `ImapConnectionManager` (it is on the
     other side of the dependency, via `OAuth2TokenServiceRegistry`);
     `ImapConnectionManager`'s own write happens under one lane's lock, where
     rule 2 forbids reaching for the other; and
     `ExternalProviderLoginService.markRequiresReauthIfExists` is
     `@Transactional`, so it publishes from `runAfterCommit` under rule 3. The
     purge is deliberate rather than
     cosmetic: after the flag, `MailSyncScheduler` no longer selects the
     account and `requireUsableAccount` rejects every other entry point, so an
     untouched `Store` would sit in the pool per lane until the process ends —
     the deactivation leak #430 closed, on the other half of the same
     predicate. Published only after the UPDATE succeeds; a failed write means
     the account is still reachable and its connections are still in use.

## Inventory

| State                                              | Type / guard                                                                                  | Mutated by                                                                                                                                                         | Lifecycle                                                                                                                                                                                                       |
| -------------------------------------------------- | --------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------ | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `ImapConnectionManager.connectionPool`             | `ConcurrentHashMap<(accountId, Lane), Store>`; entries only touched under that lane lock      | `getConnectedStore` (create/evict), `removeConnection[Locked]`, `purgeAccount`, `AccountReauthEventListener`                                                       | closed + cleared in `@PreDestroy`                                                                                                                                                                               |
| `ImapConnectionManager.accountLocks`               | `ConcurrentHashMap<(accountId, Lane), ReentrantLock(fair)>`                                   | `computeIfAbsent` on first use                                                                                                                                     | never removed (rule 4); cleared only in `@PreDestroy`                                                                                                                                                           |
| `ImapConnectionManager.interactiveLaneCooldown`    | `ConcurrentHashMap<Long, Instant>`; no lock — a lost race costs one connect attempt           | set when an interactive connect fails, removed on expiry and by `purgeAccount`                                                                                     | empty in the normal case; cleared in `@PreDestroy`                                                                                                                                                              |
| `SyncLockManager.activeSyncs`                      | `ConcurrentHashMap.newKeySet()`                                                               | `tryLock`/`unlock` around a whole account sync                                                                                                                     | skip-if-running semantics — a second sync of the same account is dropped, not queued                                                                                                                            |
| `SyncLockManager.activeFolderSyncs`                | `ConcurrentHashMap.newKeySet()` of (account, folder)                                          | `tryLockFolder`/`unlockFolder` around one folder cycle (`syncAndBackfill` and each folder of `syncAllFolders`)                                                     | skip-if-running — every `GET /emails` dispatches a cycle, the guard drops duplicates instead of queuing them behind the IMAP lock; a skipped folder in the scheduled pass blocks `clearLastError` for that pass |
| `FolderListCache.snapshots`                        | `ConcurrentHashMap`, 30 s TTL                                                                 | `getFolders` (put), invalidated by `MailSyncEventListener` (before SSE broadcast), `ImapActionService` (after server move / seen-flag write), account delete purge | collapses sidebar-refresh and bulk-move-validation bursts into one IMAP LIST+STATUS per TTL window                                                                                                              |
| `OAuth2TokenService.refreshLocks`                  | `ConcurrentMap<Long, ReentrantLock>` per provider bean                                        | `getAccessToken` (serializes `doRefresh` + double-checks `TokenCache`)                                                                                             | never removed (rule 4)                                                                                                                                                                                          |
| `TokenCache.tokens`                                | `Collections.synchronizedMap` LRU                                                             | `put` after refresh (under refresh lock), `invalidate` from XOAUTH2-reject paths, revoke, account delete                                                           | size-bounded LRU                                                                                                                                                                                                |
| `CryptoService.keyCache`                           | `synchronizedMap` LRU + explicit `synchronized` for iteration; `AtomicBoolean selfTestPassed` | key derivation, `evictCache` on credential change/delete                                                                                                           | zeroed in `@PreDestroy`                                                                                                                                                                                         |
| `FolderCountCache.snapshots`                       | `ConcurrentHashMap`, 60 s TTL                                                                 | sync cycle + read-path IMAP fetches                                                                                                                                | stale entries overwritten/ignored, lost on restart by design                                                                                                                                                    |
| `SseNotificationService.emitters`                  | `CopyOnWriteArrayList`                                                                        | client connect/disconnect, broadcast                                                                                                                               | emitter death handled per send                                                                                                                                                                                  |
| `StartupTimingService.TIMINGS`                     | static `ConcurrentHashMap`                                                                    | boot phases via `recordPhase`                                                                                                                                      | append-only diagnostics                                                                                                                                                                                         |
| `HandshakeService` (`apiKey`, `initialized`)       | `volatile` + `AtomicBoolean` + `synchronized getOrCreateApiKey`                               | `ApplicationReadyEvent` listener                                                                                                                                   | regenerated every process start                                                                                                                                                                                 |
| `accounts.requires_reauth`, `accounts.last_error*` | DB columns, atomic UPDATE queries (rule 5)                                                    | token-refresh failure handler, sync error paths, `clearLastError` after a fully clean pass                                                                         | cleared by successful re-login / clean sync                                                                                                                                                                     |
| Frontend `__MAIL_BACKEND_SIDECAR__`                | module-global runtime in [sidecar.ts](../../frontend/src/lib/backend/sidecar.ts)              | spawn/stop/restart + close/error handlers                                                                                                                          | `generation` counter invalidates events of replaced processes                                                                                                                                                   |

## Executors

- `mailSyncExecutor` — `@Async` sync cycles (`syncAllFolders`,
  `syncAndBackfillAsync`) and IMAP move/flag actions.
- `userMailExecutor` — user-facing SMTP sends.

Both pools can trigger an OAuth refresh for the same account concurrently —
that is exactly why `refreshLocks` exists (see
`GoogleTokenServiceTest.concurrentCallsWithStaleCacheShouldRefreshOnlyOnce`).

Concurrency limits are enforced by `AsyncConfig.GatedTaskExecutor` (a fair
semaphore acquired as the task's first action), NOT by
`SimpleAsyncTaskExecutor.setConcurrencyLimit` — the built-in throttle blocks
the _submitter_ at the limit, which would hang an HTTP request thread
(`GET /emails` dispatching a sync) or the shared scheduler thread. Submission
always returns immediately; excess tasks park as permit-waiting virtual
threads holding no locks. Corollary: a task must never wait for the _result_
of another task on the same gated executor (the child may be parked behind
the parent's permit) — fire-and-forget is fine.

## Verifying changes

- Concurrency-sensitive changes need a latch-style test proving the invariant
  (single HTTP refresh, single spawn, …) — see the GreenMail IT and the
  concurrent token test for the pattern.
- Before a release, run the 24h soak with JFR
  (`-XX:StartFlightRecording=duration=24h,filename=soak.jfr`) and check lock
  contention + exception counts in JDK Mission Control — see
  [RELEASE_CHECKLIST.md](../RELEASE_CHECKLIST.md).
