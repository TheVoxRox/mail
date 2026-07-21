# Static analysis

What runs at compile/verify/lint time, at which severity, and why. Source of
truth for the tools' configuration is [backend/pom.xml](../backend/pom.xml) and
the frontend npm scripts in [frontend/package.json](../frontend/package.json);
this file records the *policy* behind both.

## Backend

### Tooling

| Tool | When | Gate |
| --- | --- | --- |
| Error Prone 2.50 | every `javac` run (main + test) | ERROR-level bug patterns fail the build |
| NullAway 0.13 | main sources only | ERROR (burn-down completed, see below) |
| SpotBugs (effort Max, threshold Medium) | `mvn verify` | fails the build |
| Spotless (Eclipse formatter) | `mvn verify` / pre-push | fails the build |
| JaCoCo merged check | `mvn verify` | instruction 70 % / branch 50 % / line 70 % floors |

Error Prone needs `jdk.compiler` add-exports flags; they live in
[backend/.mvn/jvm.config](../backend/.mvn/jvm.config) because the plugin runs
in-process (forked javac swallows its own diagnostics on Windows).

### Policy decisions

- **`JavaTimeDefaultTimeZone` is OFF.** Single-user desktop app: `last_sync_at`,
  `received_at` and friends are stored and displayed in the machine's local
  wall-clock time by design (SQLite `LocalDateTime`, no server, no cross-TZ
  readers). Migrating to `Instant`/UTC would be a schema + product decision;
  if that ever happens, re-enable the check first and let it drive the
  migration.
- **String case conversions use `Locale.ROOT`.** Everything we fold is a
  protocol token, email address, OS name or i18n-independent key — never
  locale-sensitive user text. Enforced by `StringCaseLocaleUsage`.
- **No empty catch blocks.** Best-effort cleanup paths (`store.close()` in
  pool eviction, key zeroing at shutdown) log at DEBUG instead of swallowing —
  the 2026-06 reviews showed that silent error paths are exactly where this
  project's bugs live.

### NullAway

NullAway runs with `AnnotatedPackages=org.voxrox` at **ERROR** on main sources
(OFF for tests — Mockito `@Mock`/`@BeforeEach` initialization would be pure
noise). The burn-down from ~100 warnings finished 2026-06-11; new findings now
fail the build.

Approach for new findings, in order of preference:

1. If the nullability is real and intended (DTO fields, JPA associations,
   provider-dependent values), annotate with `org.jspecify.annotations.Nullable`
   (the same annotations Spring Framework 7 uses) and null-check at use sites.
2. If the value cannot actually be null, restructure so NullAway can prove it
   (inline null checks or `Objects.requireNonNull` with a one-line comment —
   NullAway does not look through helper predicates like the old `hasText`).
3. `@SuppressWarnings("NullAway")` only with a one-line justification, and only
   for framework-managed lifecycle gaps (e.g. JPA no-arg construction).

Conventions established during the burn-down:

- JPA entity columns that are nullable in the schema carry `@Nullable` on the
  field, the getter *and* the setter parameter — annotating only one of them
  trips the assignment/return checks.
- A bidirectional unlink helper (`MessageEntity.removeAttachment`) may null a
  `nullable = false` association transiently; the field is `@Nullable` with a
  comment, the column constraint is unaffected because orphanRemoval deletes
  the row.
- `account_credentials.password` is NOT NULL by schema; "no secret" is stored
  as an empty string (all readers treat blank as absent), never as null.

## Frontend

### Tooling

| Tool                              | When                           | Gate                                                           |
| --------------------------------- | ------------------------------ | -------------------------------------------------------------- |
| TypeScript strict + svelte-check  | `npm run check` / pre-push     | fails on any error                                             |
| ESLint (js/ts/svelte recommended) | `npm run lint` / pre-push      | fails the run                                                  |
| ESLint type-aware promise rules   | `src/**/*.ts`                  | `no-floating-promises`, `await-thenable` as errors             |
| knip                              | `npm run knip` / pre-push      | unused files, exports, types, dependencies                     |
| i18n key checks                   | `check:i18n` (also pre-commit) | locale parity **and** unused base-locale keys fail             |
| backend i18n key parity           | `check:i18n:backend`           | cs/en key + placeholder parity; `messages.properties` == cs    |
| translations whitelist            | `check:translations:strict`    | Czech diacritics outside i18n need a justified whitelist entry |

Policy notes:

- **Fire-and-forget promises must be explicit.** A dropped rejection in the
  boot/sidecar path surfaces as a silent hang or a spurious unhandled-rejection
  boot error; intentional cases are written as `void promise` with a comment
  (see `lib/i18n/index.ts` for the pattern).
- **Unused i18n keys fail the gate.** `scripts/check-i18n-keys.mjs` recognizes
  literal lookups and dynamic template prefixes (`` `folder.${role}` ``,
  `` `palette.group_${id}` ``); keys consumed via property access need a
  justified entry in its `USED_INDIRECTLY` list.

### Complexity audit (2026-06-12)

One-off cyclomatic-complexity sweep (`complexity`, `max-depth`, `max-params`
via a throwaway ESLint config) over `src/`. Verdict: **no refactoring
warranted** — the hot spots fall into three categories that do not benefit
from splitting:

1. **MSW test fixtures** (`test-fixtures/msw`, CC up to 85) — the fake
   backend's field-by-field merge/validation. Test-only code; complexity is
   enumerative, not branching logic.
2. **Essential enumerations** — keyboard dispatchers
   (`globalShortcuts.ts` CC 28: one branch per shortcut), field-by-field
   session/readiness parsers (deliberately explicit so each missing field has
   its own diagnostic), the HTML `content-sanitizer.ts` (explicitness is a
   security feature), optional-parameter URL builders.
3. **Option-flag pipelines** — `mailbox.executeBulkMessageAction` (CC 19)
   reads top-to-bottom; the flags are its API. `ComposeForm.saveDraftNow`
   (CC 20) carries the interactive-vs-autosave duality; splitting it would
   duplicate the shared persistence flow through the most accessibility
   sensitive component in the app.

Production-code baseline: max CC 28 (shortcut dispatch), everything else ≤ 22.
No permanent `complexity` lint rule — a threshold loose enough to pass the
legitimate enumerations (>28) would never fire in practice, and a tighter one
would only generate suppressions. Re-run the sweep when a function _feels_
unreadable, and judge by the three categories above, not by the number alone.
