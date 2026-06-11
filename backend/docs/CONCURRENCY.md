# Concurrency inventory — backend

Every piece of shared mutable state, who mutates it, and under which guard.
Half of the bugs found in the 2026-06 reviews were threads disagreeing about
one of the rows below — when adding shared state, add it here; when touching
one of these, re-read its row first.

## Locking rules (the short version)

1. **All IMAP `Store`/`Folder` work goes through
   `ImapConnectionManager.executeWithLock`.** The pooled `Store` is not
   thread-safe; the per-account fair `ReentrantLock` is the only thing
   serializing protocol access. (Enforced by `ArchitectureTest` — no class
   outside `feature.mail.service` may even reference `jakarta.mail.Store`.)
2. **Lock order: `SyncLockManager` (non-blocking tryLock) → account connection
   lock → OAuth refresh lock.** The IMAP path takes the refresh lock *inside*
   the connection lock (`createNewConnectedStore → getAccessToken`); the SMTP
   path takes the refresh lock alone. Never acquire the connection lock while
   holding a refresh lock — that inverts the order and can deadlock against a
   concurrent IMAP connect.
3. **Never hold a SQLite write transaction across a network wait or a lock
   wait.** SQLite is single-writer: one blocked transaction stalls every write
   in the app. Precedent: `AccountService.deleteAccount` defers
   `purgeAccount` (which waits on the connection lock, potentially minutes)
   until `afterCommit`.
4. **Per-account lock map entries are never removed.** Removing a lock while
   another thread waits on it lets a third thread mint a fresh lock via
   `computeIfAbsent` and enter the critical section concurrently. A
   `ReentrantLock` per deleted account is a few dozen bytes; both
   `ImapConnectionManager.accountLocks` and `OAuth2TokenService.refreshLocks`
   follow this policy.
5. **Status flags written from async error paths use targeted
   `@Modifying` UPDATEs** (`updateRequiresReauth`, `updateLastError`,
   `clearLastError`) — no entity load, no optimistic-lock conflicts with a
   concurrently running sync.

## Inventory

| State | Type / guard | Mutated by | Lifecycle |
| --- | --- | --- | --- |
| `ImapConnectionManager.connectionPool` | `ConcurrentHashMap<Long, Store>`; entries only touched under the account lock | `getConnectedStore` (create/evict), `removeConnection[Locked]`, `purgeAccount` | closed + cleared in `@PreDestroy` |
| `ImapConnectionManager.accountLocks` | `ConcurrentHashMap<Long, ReentrantLock(fair)>` | `computeIfAbsent` on first use | never removed (rule 4); cleared only in `@PreDestroy` |
| `SyncLockManager.activeSyncs` | `ConcurrentHashMap.newKeySet()` | `tryLock`/`unlock` around a whole account sync | skip-if-running semantics — a second sync of the same account is dropped, not queued |
| `OAuth2TokenService.refreshLocks` | `ConcurrentMap<Long, ReentrantLock>` per provider bean | `getAccessToken` (serializes `doRefresh` + double-checks `TokenCache`) | never removed (rule 4) |
| `TokenCache.tokens` | `Collections.synchronizedMap` LRU | `put` after refresh (under refresh lock), `invalidate` from XOAUTH2-reject paths, revoke, account delete | size-bounded LRU |
| `CryptoService.keyCache` | `synchronizedMap` LRU + explicit `synchronized` for iteration; `AtomicBoolean selfTestPassed` | key derivation, `evictCache` on credential change/delete | zeroed in `@PreDestroy` |
| `FolderCountCache.snapshots` | `ConcurrentHashMap`, 60 s TTL | sync cycle + read-path IMAP fetches | stale entries overwritten/ignored, lost on restart by design |
| `SseNotificationService.emitters` | `CopyOnWriteArrayList` | client connect/disconnect, broadcast | emitter death handled per send |
| `StartupTimingService.TIMINGS` | static `ConcurrentHashMap` | boot phases via `recordPhase` | append-only diagnostics |
| `HandshakeService` (`apiKey`, `initialized`) | `volatile` + `AtomicBoolean` + `synchronized getOrCreateApiKey` | `ApplicationReadyEvent` listener | regenerated every process start |
| `accounts.requires_reauth`, `accounts.last_error*` | DB columns, atomic UPDATE queries (rule 5) | token-refresh failure handler, sync error paths, `clearLastError` after a fully clean pass | cleared by successful re-login / clean sync |
| Frontend `__MAIL_BACKEND_SIDECAR__` | module-global runtime in [sidecar.ts](../../frontend/src/lib/backend/sidecar.ts) | spawn/stop/restart + close/error handlers | `generation` counter invalidates events of replaced processes |

## Executors

- `mailSyncExecutor` — `@Async` sync cycles (`syncAllFolders`,
  `syncAndBackfillAsync`) and IMAP move/flag actions.
- `userMailExecutor` — user-facing SMTP sends.

Both pools can trigger an OAuth refresh for the same account concurrently —
that is exactly why `refreshLocks` exists (see
`GoogleTokenServiceTest.concurrentCallsWithStaleCacheShouldRefreshOnlyOnce`).

## Verifying changes

- Concurrency-sensitive changes need a latch-style test proving the invariant
  (single HTTP refresh, single spawn, …) — see the GreenMail IT and the
  concurrent token test for the pattern.
- Before a release, run the 24h soak with JFR
  (`-XX:StartFlightRecording=duration=24h,filename=soak.jfr`) and check lock
  contention + exception counts in JDK Mission Control — see
  [RELEASE_CHECKLIST.md](../RELEASE_CHECKLIST.md).
