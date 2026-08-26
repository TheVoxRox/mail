# Static analysis

What runs at compile/verify/lint time, at which severity, and why. Source of
truth for the tools' configuration is [backend/pom.xml](../backend/pom.xml),
the frontend npm scripts in [frontend/package.json](../frontend/package.json)
and the workflows in [.github/workflows/](../.github/workflows/); this file
records the _policy_ behind all three.

## Backend

### Tooling

| Tool                                    | When                            | Gate                                                                 |
| --------------------------------------- | ------------------------------- | -------------------------------------------------------------------- |
| Error Prone 2.50                        | every `javac` run (main + test) | ERROR-level bug patterns fail the build                              |
| NullAway 0.13                           | main sources only               | ERROR (burn-down completed, see below)                               |
| javac doclint, `reference` group        | every `javac` run (main + test) | a `{@link}`/`@see`/`@throws` that does not resolve fails the build   |
| `check:java-callers`                    | `npm run check` / pre-push      | a main-source declaration with no caller (three verdicts, see below) |
| SpotBugs (effort Max, threshold Medium) | `mvn verify`                    | fails the build                                                      |
| Spotless (Eclipse formatter)            | `mvn verify` / pre-push         | fails the build                                                      |
| JaCoCo merged check                     | `mvn verify`                    | instruction 70 % / branch 50 % / line 70 % floors                    |

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
- **Javadoc references are compiled, prose is not.** `-Xdoclint:reference/private`
  turns a `{@link}`, `@see`, `@throws` or `@param` naming a member that does
  not exist into a compile error. It closes the rot that a rename leaves behind
  and that nothing else here sees: `check:refs` validates repo paths and npm
  scripts, not Java identifiers, and `check:java-imports` reads a `{@link}` as
  a _use_ of the import. Only the `reference` group is on — `missing` and
  `syntax` would demand a javadoc burn-down this repo has not decided to do.
  A method named in plain prose (`… is called by MailFacade.deleteMessages`)
  is still outside the gate; writing it as a `{@link}` is what puts it in.
- **Three verdicts on a callerless declaration, never one.** `check:java-callers`
  reports **dead** (nothing names it, tests included), **test-only** (only tests
  do) and **internal** (only its own file does) separately, because the fix
  differs: delete it, delete both the code and the test that hid it, or narrow
  it to `private`. The first prototype lumped the third in with the first and
  argued for deleting `MessageDownloader.getLatestUidFromServer`, whose caller
  sits 260 lines above it. Framework entry points are skipped by annotation —
  anything Spring, JPA, Jackson or Jakarta Validation invokes reflectively —
  and an `@Entity` accessor a test calls is exempt from the test-only verdict:
  production writes through a mapper and a test asserts persisted state through
  the getter, which is the healthy shape rather than a leftover. A private
  method with no caller stays out entirely, since SpotBugs already fails the
  build on `UPM_UNCALLED_PRIVATE_METHOD` and two gates on one finding means two
  places to silence it. Deliberate exceptions carry `@callerless <reason>` on
  the declaration, in a **line comment** — not javadoc and not a block
  comment: Error Prone rejects a javadoc block tag it does not know
  (`InvalidBlockTag`) and flags a block comment carrying javadoc tags
  (`AlmostJavadoc`), so either spelling buys a warning on every build for a
  note addressed to this gate rather than to a caller. The reason is required
  and must sit on the tag's own line, or whatever opens the next line reads
  as the reason.
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
  field, the getter _and_ the setter parameter — annotating only one of them
  trips the assignment/return checks.
- The owning side of a bidirectional association is `@Nullable` even when the
  column is `nullable = false`: it is unset between construction and the
  `addX` helper that links the two sides (`AttachmentEntity.message`). The
  column constraint is unaffected — removal goes through orphanRemoval, which
  deletes the row rather than flushing a null. The unlink helper this bullet
  used to cite was removed in the 2026-08-24 dead-code sweep; nothing called
  it, and the nullability it justified is really the construction gap.
- `account_credentials.password` is NOT NULL by schema; "no secret" is stored
  as an empty string (all readers treat blank as absent), never as null.

## Frontend

### Tooling

| Tool                              | When                                 | Gate                                                           |
| --------------------------------- | ------------------------------------ | -------------------------------------------------------------- |
| TypeScript strict + svelte-check  | `npm run check` / pre-push           | fails on any error                                             |
| ESLint (js/ts/svelte recommended) | `npm run lint` / pre-push            | fails the run                                                  |
| ESLint type-aware promise rules   | `src/**/*.ts`                        | `no-floating-promises`, `await-thenable` as errors             |
| knip                              | `npm run knip` / pre-push            | unused files, exports, types, dependencies                     |
| knip, production graph            | `npm run knip:production` / pre-push | the same, with test files out of the graph                     |
| rename residue                    | `check:rename-residue` (CI only)     | a removed symbol still named in a comment or a test name       |
| test claims                       | `check:test-claims` / pre-push       | duplicate test bodies in one file, tests switched off silently |
| i18n key checks                   | `check:i18n` (also pre-commit)       | locale parity **and** unused base-locale keys fail             |
| backend i18n key parity           | `check:i18n:backend`                 | cs/en key + placeholder parity; `messages.properties` == cs    |
| translations whitelist            | `check:translations:strict`          | Czech diacritics outside i18n need a justified whitelist entry |

Policy notes:

- **Fire-and-forget promises must be explicit.** A dropped rejection in the
  boot/sidecar path surfaces as a silent hang or a spurious unhandled-rejection
  boot error; intentional cases are written as `void promise` with a comment
  (see `lib/i18n/index.ts` for the pattern).
- **The second knip run is where refactoring leftovers surface.** The default
  run treats every `*.test.ts` and `*.e2e.ts` as an entry point, so production
  code whose last real caller disappeared stays green as long as its own test
  still imports it — that is how `formatMediumDate` survived two months after
  #34 deleted the contacts column it was written for. `knip:production` runs
  the same config with only the patterns marked `!` (the SvelteKit routes,
  `app.html`, the API type contract), so the test graph is gone. **Dropping a
  `!` does not narrow the run, it empties it:** with no production pattern left,
  knip traverses nothing, reports nothing and exits 0 — a silently disabled
  gate. Three
  deliberate consequences: `--exclude dependencies` (with the test graph out,
  every runtime dependency of a `.svelte` component reads as unused), the
  `!src/test-fixtures/**!` / `!src/routes/e2e-helpers.ts!` project negations
  (test support that happens to live under `src/`), and `--tags=-testseam`
  for the handful of exports production genuinely never calls — a reset hook
  or a teardown seam, each carrying `@testseam` next to the reason it exists.
- **A test that repeats its neighbour claims something nothing backs.**
  `check:test-claims` compares test bodies **within one file** and reports a
  pair that normalizes to the same text, plus any test switched off without a
  reason. The scope is what makes it usable: the Google and Microsoft token
  suites share sixteen bodies deliberately — one contract, two providers — so
  comparing across files would report them all and the gate would be switched
  off within a week. Inside one file the same body under two names is a copy
  someone stopped editing, which is exactly what #307 found by hand.
  **Comments come out, strings stay in**, and that pairing is not incidental:
  `'http://example.com'` contains `//`, so a regex comment-stripper deletes the
  rest of the line and two tests rejecting different URLs read as identical —
  this gate's first run reported precisely that false pair. The skip rule needs
  the opposite view (strings blanked), or the sentence "it.skip is not allowed"
  inside an assertion reports as a switched-off test.
  A skip stays if it says why: `@Disabled("…")`, a `test-skip:` comment above
  the call, or `skipIf`, whose condition is the reason.
- **The rename gate reads the change, not the tree.** `check-rename-residue.mjs`
  takes the names a diff stopped declaring and reports the ones that no longer
  appear in code anywhere — code being the file with comments and string
  literals blanked out. That direction is the robust one: matching declarations
  across the tree misses Spring Data methods (no access modifier), annotated
  signatures and wrapped ones, and every miss would call a live symbol dead.
  Two known limits, both deliberate. A name that still exists **somewhere** in
  the codebase cannot be judged by name alone, so the seven `@DisplayName`
  strings naming `findByAccountId` in #307 would not have fired — another
  repository still declares that method. And Markdown is out of scope in both
  directions: it is where removals get recorded, so a changelog entry must
  neither be reported nor let a real leftover hide behind it.
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

## Tauri (Rust)

| Tool                                    | When                             | Gate                        |
| --------------------------------------- | -------------------------------- | --------------------------- |
| `cargo check`                           | CI `tauri` job / pre-release     | clean compile               |
| `cargo clippy --no-deps -- -D warnings` | CI `tauri` job / pre-release     | any warning fails the build |
| `cargo audit` (RustSec)                 | CI `tauri` job + `vuln-scan.yml` | advisory hit fails the job  |

`--no-deps` keeps clippy on our own crate: the Tauri dependency tree is large
and we do not own its lints. cargo-audit is pinned (`0.22.2`) and cached, so a
new advisory changes the result but a new tool version never does on its own.

## Cross-language (CodeQL)

[.github/workflows/codeql.yml](../.github/workflows/codeql.yml) runs the
`security-and-quality` suite over a Java + JS/TS matrix on push, on pull
requests, and on a weekly cron. SARIF lands in the GitHub Security tab.

**Advisory only, deliberately.** CodeQL is not wired into branch protection:
its quality queries carry a false-positive rate this codebase has not yet
characterised, and a gate nobody trusts gets bypassed rather than fixed. The
weekly cron exists because the query packs improve independently of our code —
a finding can appear in a file nobody has touched. Promote it to a required
check once the noise level is known.

Dismissing an alert needs a written reason. The log-injection class (72 alerts,
closed 2026-06-18) is the standing example: `CrlfSafeMessageConverter`
neutralises the sink, but CodeQL does not model the converter, so structurally
identical alerts keep arriving and get dismissed the same way.
