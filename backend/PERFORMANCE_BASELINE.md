# Performance baseline

The record of what this app's startup and runtime actually cost, and of what
each number was measured on. Every section below is a dated measurement; the
scripts that produce them are [scripts/measure-cold-start-windows.ps1](scripts/measure-cold-start-windows.ps1)
and `npm run tauri:smoke:release-startup`, and both say results belong here.

**Rule: every measured section starts with a `Machine:` line.** The repository
alternates between two machines with a ~2× difference in throughput (laptop
i7-1255U, 15W U-series, 1.7 GHz base, 16 GB × desktop PC AMD Ryzen 9 9900X,
12 cores / 24 threads, 4.4 GHz, 62 GB), so a startup number without a machine
name is unusable — comparing across sections turns it into an apparent
contradiction that is not one. Sections written before this rule do not name a
machine and it can no longer be recovered; they therefore carry
`Machine: not recorded` and their absolute numbers cannot be compared. Relative
deltas within one section still hold — those measured both variants on the same
hardware.

## What has never been measured

Everything below is startup. The **runtime** side of the original worksheet was
never filled in, in any release candidate, so these are open gaps rather than
numbers waiting to be copied in:

- full-sync throughput against a real 10k+ mailbox (elapsed time, messages/s),
- FTS5 search latency on that mailbox, hit and miss,
- peak RSS / working set and observed Java heap over a long run,
- SQLite DB and WAL growth over that run,
- time to send a message with a large attachment.

The first three matter most and none of them can be measured on the dev profile
(524 messages). The functional side of the same smoke — that these operations
_work_ — is owned by [RELEASE_CHECKLIST.md](RELEASE_CHECKLIST.md) §5 and §8;
what is missing here is only how long they take. A new measurement goes in as a
dated section with a `Machine:` line, in the shape of the ones below.

## Startup audit 2026-05-17 - before/after

After implementing the set of changes from the startup audit (todo.md section
"Startup A Komunikace FE-BE"). The goal is to measure the real gain on cold start
and perceived speed, and to decide whether it is worth going on with the JEP 483
AOT cache (#10 in the audit).

Measure on a **clean profile** (a fresh `%LOCALAPPDATA%\VoxRox\Mail`, no previous
WAL, an empty DB). Run each variant **3×** and take the median, not a single
sample.

This section deliberately names no machine: the numbers in both tables below are
**targets and typical ranges**, not measured values. The `Machine:` rule from the
introduction applies to sections that measured something.

### Measuring on the backend side

`StartupTimingService` logs every phase at INFO level (`[BOOT] Startup
timing: phase=<key> durationMs=<ms>`). The key phases:

| Phase                       | Key                                 | Typical before the changes |                 Target after |
| --------------------------- | ----------------------------------- | -------------------------: | ---------------------------: |
| Spring AppContext refresh   | (Spring `Started ... in X seconds`) |                     ~3-5 s |               -20-40 % (AOT) |
| Flyway migration            | `db.flyway-migrate`                 |                  50-300 ms | unchanged (no-op when ready) |
| Pre-migration backup        | `db.pre-migration-backup`           |                  50-200 ms | **0 ms** (when none pending) |
| SQLite PRAGMA verify        | `db.verify-pragmas`                 |                  30-100 ms |                    unchanged |
| Crypto subsystem init       | `crypto.service-init`               |                     <10 ms |     unchanged (already lazy) |
| Storage permissions         | `storage.permissions`               |                   10-50 ms |                    unchanged |
| Handshake session write     | `handshake.session-write`           |                     <10 ms |                    unchanged |
| ApplicationReadyEvent total | `spring.application-ready`          |                 ~50-500 ms |      -10-20 % (smaller heap) |

How to measure:

```powershell
# 1. Fresh data dir
Remove-Item -Recurse -Force "$env:LOCALAPPDATA\VoxRox\Mail" -ErrorAction SilentlyContinue
# 2. Start the Tauri release build
& "$env:ProgramFiles\VoxRox Mail\voxrox-mail.exe"
# 3. After the application starts, copy the log and read it
Get-Content "$env:LOCALAPPDATA\VoxRox\Mail\logs\mail.log" |
  Select-String "Startup timing" | Select-Object -First 20
```

### Measuring on the frontend side

`bootstrap.ts` writes timings into `bootState.timings` (for the keys see
[frontend/src/lib/stores/boot.ts](../frontend/src/lib/stores/boot.ts)). Dev console:

```js
console.info("[mail] boot timings", $bootState.timings);
```

The key deltas (relative to `uiStart=0`):

| Interval                     | Key              | Typical before |                        Target after |
| ---------------------------- | ---------------- | -------------: | ----------------------------------: |
| Sidecar process running      | `sidecarRunning` |      50-300 ms |                           unchanged |
| `.ready` + session.json seen | `sessionFound`   |  +1000-5000 ms | -300-500 ms (smaller JVM heap, AOT) |
| Handshake OK                 | `handshakeOk`    |      +0-200 ms |       **0 ms** (removed, mark only) |
| Readiness OK                 | `readinessOk`    |      +0-200 ms |     unchanged (1 polling roundtrip) |
| Client config loaded         | `clientConfigOk` |    +100-300 ms |  -50 ms (in parallel with accounts) |
| Accounts loaded              | `accountsLoaded` |    +100-500 ms |    -50 ms (in parallel with config) |
| App ready (full shell)       | `appReady`       |  ~2000-6000 ms |                        -500-1500 ms |

### What to verify after the smoke

- [x] Cold start `appReady` median over 3 runs — **4621 ms from spawn**, verified
      2026-09-08 in the desktop bundle (section "`appReady` in the desktop bundle"
      below). Of that, 4178 ms is waiting for the sidecar; the UI adds 45 ms.
- [ ] Heap at startup (`Get-Process java | Select-Object PrivateMemorySize64`)
- [ ] The Tauri window appears within <500 ms
- [ ] The shell-first placeholder (AppRail + sidebar gray boxes) is visible
      immediately after the window appears, and does not stay a blank screen
- [ ] A pre-migration backup file (`db/mail.db.backup-pre-v*`) is NOT created
      on a start against the unified schema
- [x] The springdoc classes are absent from the fat jar (`jar tf mail-backend-*.jar |
Select-String springdoc` = empty) — verified 2026-06-11 after fixing the
      exclusion (see the measurement below; the original `<excludes>` with a
      groupId alone silently did nothing and springdoc stayed in the jar)
- [x] The AOT artifacts are present in the jar (`jar tf mail-backend-*.jar |
Select-String "__BeanDefinitions"` non-empty) — verified 2026-06-11,
      310 classes
- [ ] Global bootstrap timeout: on an artificial sidecar failure (mock
      `mail.e2e.sidecarFailure=once`) the frontend shows the error **within 60 s**,
      not after the polling loops (previously ~90 s)

### Decision gate for AppCDS / JEP 483 (#10)

If the median cold start `appReady` after this set of changes is **> 3.5 s**, it
is worth going on with the JEP 483 AOT class cache (training run, build cache,
deploy with the fat jar). If the median is **<= 3.5 s**, the gain from the AOT
cache (typically a further +200-500 ms off) does not justify the complexity
(a training run during the build, cache invalidation when the jar changes, the
distribution size).

### JEP 483 AOT cache — measurement 2026-05-19

**Machine: not recorded** (added 2026-09-08, see the rule in the introduction).

Performed after the self-injection refactor (`MessageContentPersister`,
`ContactBulkService`), which removed runtime CGLib proxy generation from
`MailContentService`/`ContactService`. The previous `ObjectProvider<Self>`
prevented the AOT cache restore with a `ClassCastException`.

**Setup:**

- Plain `java -jar mail-backend-0.1.0.jar` (without the Tauri jpackage launcher)
- Jar built with `-Paot` (Spring AOT processing active)
- Dummy crypto keys from env
- `APP_DATA_DIR` = a fresh tmp directory per run
- Median of 3 independent runs per variant

**Results:**

| Variant                      | Median cold start |                  Delta |
| ---------------------------- | ----------------: | ---------------------: |
| Without the AOT cache        |       **8553 ms** |               baseline |
| With `-XX:AOTCache=mail.aot` |       **5415 ms** | **−3138 ms (−36.7 %)** |

**Cache size:** 130.62 MB (`mail.aot`). The cache is bound to the exact jar hash

- and the Java version — it has to be regenerated when the jar is rebuilt (~70 s
  for training + create through `scripts/generate-aot-cache-windows.ps1`).

**Closing the gap:** the Tauri jpackage launcher adds further overhead on top of
plain `java -jar` (process spawn, sidecar handshake, .ready signal). The real
`appReady` in the desktop bundle will be higher than 5.4 s, but roughly the same
relative saving (~37 %) should carry over.

**Decision:** the AOT cache is `OPT-IN` in `scripts/package-sidecar-windows.ps1`
through the `-EnableAotCache` switch. The build pipeline without AOT is the
default because of CI/dev iteration speed (the training run adds ~70 s), and the
130 MB cache is not stored in git. For a release build use `-EnableAotCache`.

### Startup audit — measurement 2026-06-03 (re-validating AOT after the startup sweep)

**Machine: not recorded** (added 2026-09-08, see the rule in the introduction).
The section itself says "a loaded machine", but not which one — that is not
enough to compare it with another section.

Performed after a set of startup optimisations (AOT cache default ON for the
release, lazy import of FE components, fs watch instead of session polling,
readiness single-shot 75→5 attempts, `AttachmentService` cleanup moved from
`@PostConstruct` to `@Async @EventListener(ApplicationReadyEvent)`). Goal: verify
that the AOT saving holds and that moving the cleanup off the boot thread did not
make the cold start worse.

**Setup (identical to 2026-05-19 for comparability):**

- Jar with `-Paot`, JDK 25.0.3 (Temurin)
- Production JVM flags: `-XX:TieredStopAtLevel=1 -Xms64m -Xmx384m -XX:+UseSerialGC --enable-native-access=ALL-UNNAMED -Dspring.aot.enabled=true`
- Dummy crypto keys from env, `APP_DATA_DIR` = a fresh tmp directory per run
- **Metric:** wall-clock from the JVM process start to the creation of the
  `.ready` file (exactly the signal the frontend `loadSession` waits for), median
  of 3 runs
- Note: measured on a loaded machine (parallel builds were running), so the
  absolute numbers are higher than on 2026-05-19; **the relative delta is the
  robust signal**.

**Results:**

| Variant                      | Median cold start (→ `.ready`) |                  Delta |
| ---------------------------- | -----------------------------: | ---------------------: |
| Without the AOT cache        |                   **10902 ms** |               baseline |
| With `-XX:AOTCache=mail.aot` |                    **7055 ms** | **−3847 ms (−35.3 %)** |

**Cache size:** 128.5 MB (`mail.aot`).

**Conclusions:**

- The AOT saving of **−35.3 %** confirms the historical −36.7 % → the decision
  "AOT cache default ON for the release" (CI `windows-signed-release.yml`) is
  validated by real numbers.
- Both variants reached `.ready` cleanly → the `AttachmentService` cleanup is now
  off the boot critical path (it runs `@Async` after `ApplicationReadyEvent`),
  **with no regression** in boot time.
- **Decision gate (`appReady` > 3.5 s → AOT pays off):** backend-to-`.ready` with
  AOT is 7.0 s, and the full `appReady` in the desktop bundle will be higher
  still → the gate is met with margin, and AOT is clearly justified.
- **Open for the future:** an absolute 7 s even with AOT is still heavy. Further
  levers (outside the scope of this audit): a jlink-trimmed runtime (only the
  modules used), fewer Spring auto-configurations, lazy bean init. They require a
  larger change and their own measurement.

**Still requires a GUI measurement (cannot be done headless):** the full
`appReady` (window paint + handshake + accounts load) in the Tauri bundle —
`frontend/scripts/tauri-release-startup-smoke.mjs` against a real build, and
perceived speed (shell-first placeholder, lazy sidebar/dialog timing).

### Startup audit — measurement 2026-06-11 (real springdoc exclusion + NullAway sweep)

**Machine: not recorded** (added 2026-09-08, see the rule in the introduction).
This also applies to the phrase "an unloaded machine" below — load is only half
the conditions, the other half is which hardware.

Context: while verifying the checklist above it turned out that springdoc was
**never excluded** from the fat jar — `spring-boot-maven-plugin` `<excludes>`
requires both groupId and artifactId, and the original entry with a groupId alone
silently matched nothing. Fixed through
`<excludeGroupIds>org.springdoc</excludeGroupIds>` on the `repackage` and
`process-aot` executions (the second is necessary, otherwise AOT generates
springdoc `__BeanDefinitions` classes referring to classes missing from the jar).
Details in [OPERATIONS.md](OPERATIONS.md); the `openapi` profile resets it for a
debug build.

**Setup (identical to 2026-06-03):** jar `-Paot`, JDK 25.0.3, prod JVM flags
(`-XX:TieredStopAtLevel=1 -Xms64m -Xmx384m -XX:+UseSerialGC
--enable-native-access=ALL-UNNAMED -Dspring.aot.enabled=true`), dummy crypto keys
from env, a fresh tmp `APP_DATA_DIR` per run, metric JVM start → `.ready`,
3 runs. Without `-XX:AOTCache`. The machine was unloaded this time and the jar was
freshly in the FS cache — the absolute numbers are therefore not 1:1 comparable
with 2026-06-03 (a loaded machine there).

**Results:** 3603 / 3658 / 3683 ms → **median 3658 ms** to `.ready`,
private memory at `.ready` ~235 MB. Every run reached `.ready` cleanly —
i.e. the AOT-enabled context **boots without springdoc on the classpath without
errors** (no NoClassDefFoundError from the AOT bean definitions; the @Schema
annotations on DTOs are soft references at runtime, so their absence harms
nothing).

**Conclusion:** the springdoc exclusion is now real and verified by a run.
Backend-to-`.ready` is 3.7 s on an unloaded machine without the JEP 483 cache;
the full `appReady` in the desktop bundle remains to be measured by a GUI smoke
(see above).

### Startup audit — measurement 2026-09-08 (does `-XX:TieredStopAtLevel=1` still hold with the AOT cache?)

**Machine: laptop i7-1255U** (15W U-series, 1.7 GHz base, 16 GB) — added
2026-09-08 from the following section, which says of itself "the same day and
machine as the measurement above" and names the machine.

Context: item 5 in [#392](https://github.com/TheVoxRox/mail/issues/392) asks
whether the C1-only flag still pays off now that the AOT cache is permanently on —
both optimisations aim at the same second of startup, and only one of them costs
CPU on computationally heavy work. The premise that the app does such work during
the first sync of a large mailbox was refuted in the same issue (the first sync
downloads 100 messages, then tops up in batches of 30), so this question was what
remained.

**Setup:** jar `-Paot`, JDK 25.0.4.1, a 133.9 MB AOT cache from
`scripts/generate-aot-cache-windows.ps1`, prod JVM flags, dummy crypto keys from
env, a fresh tmp `APP_DATA_DIR` per run, metric JVM start → `.ready`.
**15 rounds, variants alternating and with the order reversed every other round** —
a block design (all A first, then all B) would hand the machine's load drift to
whichever variant ran second. Script:
[scripts/measure-cold-start-windows.ps1](scripts/measure-cold-start-windows.ps1).

**Results (n=15 per variant):**

| Variant                          | Median | Range     |                   vs. the release configuration |
| -------------------------------- | -----: | --------- | ----------------------------------------------: |
| AOT + `-XX:TieredStopAtLevel=1`  |   5479 | 5009–5675 |                                        baseline |
| AOT, full tiered (flag removed)  |   5226 | 5058–5646 |   −253 ms (−4.6 %), z = −1.22 → not significant |
| Without the AOT cache + the flag |   7819 | 6600–8025 | +2340 ms (+42.7 %), z = −4.67 → **significant** |

(Mann-Whitney U; `|z| >= 1.96` = significant at 5 %.)

**Conclusions:**

- **The AOT cache is confirmed again: −2.3 s / −42.7 %.** That matches the
  historical −36.7 % (2026-05-19) and −35.3 % (2026-06-03), so "AOT default ON for
  the release" rests on three independent measurements.
- **`-XX:TieredStopAtLevel=1` no longer has a measurable benefit on startup.** Two
  measurements from the same day disagreed on the **sign**: the earlier two-variant
  run gave +78 ms in the flag's favour (z = −2.97, significant), this three-variant
  one −253 ms against it (not significant). That is not "sometimes one way,
  sometimes the other" — it is evidence that the effect lies below the resolving
  power of this method on this machine. It is certainly not the 10–15 % the flag is
  justified with.
- **The decision about the flag does not change, but its justification does.** It
  stays (no measurement shows harm, and there is no point moving a shipped JVM
  configuration before a release over nothing), but the argument "it saves 10–15 %
  of startup" no longer holds. What stays open is the other half, which nobody
  measured: the **cost** of C1-only on computational work (MIME parsing, threading,
  FTS5 indexing) during the first sync. Once that exists, the flag can be decided
  from both sides instead of one.
- **Absolute numbers drift by ~15 % within an hour on the same machine** (a median
  with AOT of 4739 ms in the morning vs 5479 ms in the afternoon). Comparison is
  only valid within one run, never across sections of this document.

**Three traps that cost this measurement one run each** (and which are therefore
commented in the script): a redirected but unread stdout/stderr fills the pipe and
the JVM **hangs halfway through boot** — it looks like a slow start, not a
deadlock; `CryptoProperties` rejects a key shorter than 32 characters and the
failure only surfaces as a bean binding error deep in the context refresh; and a
difference of medians on its own claims nothing at this effect size — the rank
test is what decides.

### Startup audit — measurement 2026-09-08 (desktop bundle with the AOT cache)

**Machine: laptop i7-1255U** (15W U-series, 1.7 GHz base, 16 GB — the same one
the pre-push gate was measured on; added by the maintainer the same day, when the
numbers diverged from the evening measurement on the desktop PC — see the section
"`appReady` in the desktop bundle"). The absolute values of this section are
therefore not transferable to other hardware; the ratios within it are.

The same day and machine as the measurement above, so that the numbers can be
compared. A release bundle built with `npm run tauri:build:with-sidecar` over a
sidecar with the AOT cache, measured with
`npm run tauri:smoke:release-startup -- --runs=3 --isolate-app-data` (isolate
renames an existing `%LOCALAPPDATA%\VoxRox\Mail` aside, measures on a clean
profile and then puts it back).

| Run    | `.ready` + session.json | readiness `READY` |
| ------ | ----------------------: | ----------------: |
| cold   |                 8182 ms |           8329 ms |
| warm   |                 8260 ms |           8438 ms |
| warm   |                 9784 ms |           9937 ms |
| median |             **8260 ms** |       **8438 ms** |

**What it says:** a headless start of the same jar with the same AOT cache had a
median of 5479 ms to `.ready` the same day. The desktop bundle is at 8260 ms, so
**~2.8 s extra goes to the jpackage launcher, the spawn from Tauri and the path
to session.json** — exactly the overhead the 2026-05-19 section estimated with the
words "the real `appReady` will be higher", but which nobody measured. The
readiness endpoint adds another ~180 ms.

**What it does NOT say:** this **is not `appReady`**. The smoke script ended at
backend readiness at that point; the full `appReady` (window paint, handshake,
accounts load) lives in `bootState.timings` inside the webview. Measured the same
day — see the following section, which also corrects the estimated route: CDP was
not needed.

## Startup audit — `appReady` in the desktop bundle, measurement 2026-09-08 (evening)

Fills in the open item of the previous section. `npm run tauri:smoke:release-startup -- --runs=3 --isolate-app-data`,
release `app.exe` from `tauri:build --no-bundle`.

**The route to the number is different from what the previous section estimated,
and that matters more than the numbers themselves.** Neither WebView2 remote
debugging nor CDP is needed: the client **already sends** those timings to
`POST /api/internal/client-boot` in the statement right after `completeBoot()`,
the backend keeps the last snapshot, and the diagnostic dump serves them as
`client-boot.json`. The smoke script therefore downloads the dump after readiness
and reads them — on the shipped binary, with no debugger and no build flag (the
console log of boot timings is behind `import.meta.env.DEV`, so it is silent in a
release build). The bridge between the clocks is `reportedAt`: the client stamps
it with `new Date()` on the same wall clock, so `reportedAt − spawn` gives the
time from the process start.

| Run        | spawn → `uiStart` | `sidecarRunning` | `sessionFound` | `readinessOk` | `appReady` (UI clock) | spawn → `appReady` |
| ---------- | ----------------: | ---------------: | -------------: | ------------: | --------------------: | -----------------: |
| cold       |            585 ms |            71 ms |        4403 ms |       4436 ms |               4460 ms |            5045 ms |
| warm       |            423 ms |            32 ms |        4138 ms |       4160 ms |               4181 ms |            4604 ms |
| warm       |            398 ms |            34 ms |        4178 ms |       4201 ms |               4223 ms |            4621 ms |
| **median** |        **423 ms** |        **34 ms** |    **4178 ms** |   **4201 ms** |           **4223 ms** |        **4621 ms** |

**What it says: `appReady` is not above backend readiness by an unknown, it is
above it by tens of milliseconds.** From `sessionFound` to `appReady` there are
57 / 43 / 45 ms — handshake, readiness, client config and accounts together. The
whole startup budget is waiting for the sidecar: `sessionFound` is 4.1–4.4 s and
everything else in the UI is noise against it. Optimising the UI start therefore
has nothing to gain; the only lever is the JVM.

**A second thing nobody had measured: 400–585 ms before `uiStart`.** That is the
process spawn, the WebView2 start and the first script finishing — it does not
show in `bootState`, because its clock only starts in `beginBoot()`. On a cold run
it is 585 ms, on a warm one ~400 ms.

**The absolute numbers are not comparable with the previous section, because each
was produced on a different machine.** This section is measured on the **desktop
PC** (AMD Ryzen 9 9900X, 12 cores / 24 threads, 4.4 GHz, 62 GB RAM), the previous
morning one on the **laptop** (i7-1255U, 15W U-series, 1.7 GHz base, 16 GB —
confirmed by the maintainer, it is the same machine the pre-push gate was measured
on, see `todo.md`). The sidecar differs too: the one in `src-tauri/binaries/` was
repackaged the same day at 16:03 **without the JEP 483 AOT class cache** —
`mail-x86_64-pc-windows-msvc.cfg` carries `-Dspring.aot.enabled=true`
and `-XX:TieredStopAtLevel=1`, but no `-XX:AOTCache`, and no cache file sits next
to the jar.

Two variables at once therefore mean that **nothing about the AOT cache follows
from the difference of 4.2 s vs 8.3 s to `.ready`** — a 15W mobile CPU against a
twelve-core desktop explains a factor of two on its own. The documented gain of
the cache (−42.7 %, the 2026-09-08 section above) rests on a controlled
alternating run **on a single machine** and is not called into question by this
measurement.

**The rule that follows, and that applies to every further entry:** a startup
number without a machine name is unusable. This repository hits that for the
second time — "the gate runs ~50 minutes" from `todo.md` is the same class of
mystery and it also dissolved as soon as someone measured what it ran on. The
startup sections above that name no machine carry `Machine: not recorded` as of
2026-09-08 — they are numbers without a scale, and they say so themselves.

None of this touches the `appReady` breakdown above: it is a ratio within a single
run, so it does not depend on the speed of the machine.

## Correspondent typeahead — measurement 2026-09-08 (synthetic)

**Machine: desktop PC** (AMD Ryzen 9 9900X, 12 cores / 24 threads, 4.4 GHz, 62 GB,
NVMe), Windows 11, sqlite-jdbc 3.53.2.1 — the same version the backend resolves.

The question this closes comes from `todo.md` and is a design one, not an
operational one: the comment next to `correspondent` in V1\_\_init.sql rests on the
table holding **low thousands of rows per account**, and therefore needing only a
scan bounded by `ux_correspondent_account_email` without a second index. Were
there an order of magnitude more, that would stop holding and matching by name
would want a normalised column. A real mailbox could not be measured (see below),
so the answer comes from the other side: **how many rows that path can carry**.

**Method:** [scripts/measure-correspondent-typeahead.java](scripts/measure-correspondent-typeahead.java),
two accounts (so that the leading index column has something to bound), the schema
and pragmas copied from V1\_\_init.sql and `application.properties`, the query
copied from `CorrespondentRepository#search` with a limit of 20 (the API ceiling).
Per size: 50 warm-up and 200 measured iterations. `hit` = the query "jan" (matches
both addresses and names), `miss` = "zxq" (matches nothing, so it does not sort —
the pair separates the cost of the scan from the cost of the ordering).

| Rows / account | hit p50 | hit p95 | miss p50 | upsert p50 |
| -------------: | ------: | ------: | -------: | ---------: |
|          1,000 | 0.31 ms | 0.34 ms |  0.21 ms |   0.019 ms |
|          5,000 | 1.21 ms | 1.26 ms |  1.04 ms |   0.017 ms |
|         20,000 | 5.35 ms | 5.51 ms |  4.90 ms |   0.015 ms |
|         50,000 | 14.3 ms | 15.0 ms |  13.2 ms |   0.015 ms |
|        100,000 | 29.9 ms | 31.3 ms |  27.3 ms |   0.015 ms |
|        200,000 | 72.3 ms | 75.9 ms |  67.5 ms |   0.014 ms |
|        300,000 |  224 ms |  241 ms |   211 ms |   0.014 ms |
|        500,000 |  732 ms |  752 ms |   712 ms |   0.015 ms |

Rows 1,000 through 300,000 are one continuous run; the 500,000 row comes from
another run the same evening on the same machine.

`EXPLAIN QUERY PLAN` is the same at every size:
`SEARCH c USING INDEX ux_correspondent_account_email (account_id=?)`

- `USE TEMP B-TREE FOR ORDER BY`.

**Conclusions:**

- **You pay for the scan, not for the ordering.** A query that matches nothing —
  and so has nothing to sort — costs only 5–9 % less from 20k rows upward than one
  that fills the limit. The cost is walking the index range for the account and
  reaching into the table for each row (`SELECT *`), not the temp B-tree.
- **Linear up to ~200k rows, then not.** Up to 200k it holds roughly 0.36 ms per
  thousand rows; 300k is 2× above the linear extrapolation and 500k is 4×. The
  break fits the working set outgrowing `cache_size=-20000` (20 MB) and then the
  filesystem cache too — the DB is 96 MB at 200k rows.
- **The design assumption holds with two orders of magnitude to spare.** "Low
  thousands per account" = single-digit ms; even tens of thousands stay under
  15 ms. Neither a second index nor a normalised column for name matching is
  therefore needed. Reaching for them makes sense only if an account approached
  ~100k distinct addresses — there the query is 30 ms **per keystroke**, because
  [AddressTokenField](../frontend/src/lib/components/compose/AddressTokenField.svelte)
  **does not debounce** the typeahead: every `input` sends a request and a run
  token discards stale responses. On the 15W laptop (the repository's second
  machine) expect roughly double; not measured.
- **The sync path is independent of table size.** The `upsert` through
  `ON CONFLICT (account_id, email)` holds at 0.015 ms from a thousand to half a
  million rows — the harvest during a sync need not worry about the table growing.

**What it does not say:** how many rows `correspondent` has on a **real, full
mailbox**. The dev profile is not enough for that, and not even the ratio can be
taken from it: 524 messages and 65 rows, but 338 of those messages sit in one
mailing-list folder (few senders, many messages) and Trash and Spam are not
harvested at all. Measuring it needs a full sync, and that is blocked on a fresh
login to the Gmail account (see `todo.md`). The threshold above is high enough
that the design question need not wait for it.

**Further limitations:** the data is synthetic (ASCII, an even distribution of
names, every tenth address a robot), measured on a single connection without a
concurrent sync writing into the same DB.

## Windows commands

```powershell
$data = "$env:USERPROFILE\.voxrox\mail-backend"
Get-ChildItem "$data\db" | Select-Object Name, Length, LastWriteTime
Get-Process java | Select-Object Id, ProcessName, CPU, WorkingSet64, PrivateMemorySize64
$session = Get-Content "$data\session.json" | ConvertFrom-Json
Invoke-RestMethod "$($session.baseUrl)/api/internal/health" -Headers @{ "X-API-KEY" = $session.apiKey }
Invoke-WebRequest "$($session.baseUrl)/api/internal/diagnostic-dump" -Headers @{ "X-API-KEY" = $session.apiKey } -OutFile "$env:TEMP\mail-backend-diagnostic.zip"
Get-Item "$env:TEMP\mail-backend-diagnostic.zip" | Select-Object Name, Length
```

## Linux/macOS commands

```bash
DATA="$HOME/.voxrox/mail-backend"
ls -lh "$DATA/db"
ps -o pid,pcpu,rss,vsz,comm -p "$(pgrep -f mail-backend | head -n 1)"
```
