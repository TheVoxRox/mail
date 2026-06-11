# Static analysis — backend

What runs at compile/verify time, at which severity, and why. Source of truth
for the tools' configuration is [pom.xml](../pom.xml); this file records the
*policy* behind it.

## Tooling

| Tool | When | Gate |
| --- | --- | --- |
| Error Prone 2.50 | every `javac` run (main + test) | ERROR-level bug patterns fail the build |
| NullAway 0.13 | main sources only | ERROR (burn-down completed, see below) |
| SpotBugs (effort Max, threshold Medium) | `mvn verify` | fails the build |
| Spotless (Eclipse formatter) | `mvn verify` / pre-push | fails the build |
| JaCoCo merged check | `mvn verify` | instruction 70 % / branch 50 % / line 70 % floors |

Error Prone needs `jdk.compiler` add-exports flags; they live in
[.mvn/jvm.config](../.mvn/jvm.config) because the plugin runs in-process
(forked javac swallows its own diagnostics on Windows).

## Policy decisions

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

## NullAway

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
