# Contributing to VoxRox Mail

Thanks for your interest in VoxRox Mail. The project is in active development
and the public surface is still small; this guide is for collaborators or
future maintainers reading the codebase cold.

For security issues, see [SECURITY.md](SECURITY.md). Do **not** open public
GitHub issues for vulnerabilities.

## Repository Layout

```
backend/      Spring Boot 4.x sidecar (Java 25, Maven)
frontend/     SvelteKit 2 + Svelte 5 desktop UI (static adapter)
frontend/src-tauri/  Tauri 2 wrapper that bundles the JAR sidecar
```

Each module is a self-contained build with its own README, dependency
inventory (`THIRD_PARTY_LICENSES.md`) and changelog where applicable.

## Local Setup

Requirements:

- **Java 25 (Temurin)** with Maven. Use `mvn` directly — that is what every
  hand-written invocation runs, in CI and in `backend/scripts/`. Do not reach
  for `./mvnw`: `mvnw.cmd` shells out to `powershell.exe` (Windows PowerShell
  5.1), which is absent on machines that only ship PowerShell 7, so it fails
  there before Maven ever starts.

  The wrapper is committed anyway and **must stay**. CodeQL analyses Java with
  `build-mode: autobuild` ([`.github/workflows/codeql.yml`](.github/workflows/codeql.yml)),
  and the autobuilder picks `./mvnw` over the system `mvn` on its own — so
  `backend/.mvn/wrapper/maven-wrapper.properties` decides which Maven builds
  the CodeQL database, and `Analyze (java-kotlin)` is a required check.
  Dependabot's `apache-maven` bumps are therefore real changes, not noise.

- **Node 26** with `npm`.
- **Rust toolchain** (stable + clippy) — only needed for the Tauri Rust
  crate (`frontend/src-tauri/`).
- **PowerShell** on Windows for the sidecar packaging scripts under
  `backend/scripts/` and `frontend/scripts/sync-backend-sidecar-windows.mjs`.

Quick start:

```powershell
# 1. Backend tests
cd backend
mvn clean verify

# 2. Frontend gates
cd ../frontend
npm ci
npm run lint
npm run check
npm run test:unit
npm run test:functional:stable
npm run test:a11y:stable

# 3. Dev run (Tauri desktop)
npm run tauri:dev
```

### Git hooks (one-time activation)

Hooks live in the tracked `.githooks/` directory. Activate them once per clone
(`core.hooksPath` is local config and is **not** carried over by `git clone`):

```sh
git config core.hooksPath .githooks
```

- **pre-commit** (fast): when the commit touches `frontend/`, runs
  `prettier --check`, i18n key parity (`check:i18n`), and the strict translation
  whitelist (`check:translations:strict`); when it touches the backend
  `messages*.properties` bundles, runs the backend i18n parity guard
  (`check:i18n:backend`); when it touches any `*.md`, runs the repo-wide
  Markdown format check (`check:md`). These are the cheap, deterministic checks
  that otherwise drift between sessions because nothing enforced them at commit
  time.
- **pre-push** (fuller): `npm run lint`, `npm run check:i18n:backend`, strict
  translations, `npm run check`, `knip`, `knip:production`, and `npm run test:unit`.

Bypass once with `git commit --no-verify` / `git push --no-verify`. Backend
`mvn verify`, Playwright e2e, and Tauri `cargo` checks are intentionally left to
the manual gate / CI — too slow to force on every commit or push.

## Pre-Push Quality Gate

Every change must pass the following locally before opening a PR. CI runs the
same set and will reject the PR if anything red.

This list deliberately carries **no test counts**. Earlier revisions did, and
every one of them was wrong within a month — a count changes whenever anyone
adds a test, which is never the moment someone remembers to edit
CONTRIBUTING. The invariant worth stating is the one the tooling enforces:
suites are green and coverage floors do not move. If a change would drop
coverage, add a per-file threshold rather than lowering a global floor.

**Backend (`cd backend && mvn clean verify`)**

- `spotless:check` — Eclipse 4.37 formatter via `spotless-maven-plugin`.
- `spotbugs:check` — Bundled in `mvn verify`. Exclusions in
  `spotbugs-exclude.xml`. Run it with `clean`: a `target/` left over from an
  earlier `-Paot package` still holds the generated `__BeanDefinitions`
  classes, and SpotBugs analyses them and fails on code nobody wrote.
- Unit + integration tests (Surefire + Failsafe).
- Jacoco merged unit + IT coverage report in `target/site/jacoco-merged/`.
  Threshold gate enforces ≥ 70% instructions / ≥ 50% branches / ≥ 70% lines.
- Translation whitelist lint (`node ../frontend/scripts/check-translation-whitelist.mjs
--target=backend --mode=strict`) — keeps the Java codebase in English.
- Backend i18n key parity (`node ../frontend/scripts/check-backend-i18n-keys.mjs`) —
  cs/en key + MessageFormat placeholder parity, and `messages.properties` stays
  identical to the Czech base bundle.

**Frontend (`cd frontend && npm run lint && npm run check && npm test ...`)**

- `npm run lint` — Prettier check (`frontend/`) + `check:md` (every tracked
  `*.md` in the repo, since prettier's config lives under `frontend/` and would
  otherwise never see the root, `docs/` or `backend/docs/` files) + ESLint +
  i18n key parity (cs.json vs en.json). Fix formatting with `npm run format`
  and `npm run format:md`.
- `npm run check` — CSP parity (`app.security.csp` vs `devCsp`), typography
  lint (no arbitrary font-size utilities), design lint (see below), version
  sync, doc-claims lint (stack versions and stale phrases in root + module
  docs vs `pom.xml`/`package.json`/`.nvmrc`), audit freshness (see below),
  OpenAPI snapshot drift, `svelte-check` (must report 0 errors).
- `npm run check:design` (part of `check`) — the visual system, which a type
  checker cannot see: colours must come from the tokens in `app.css` and never
  from Tailwind's own palette (a palette colour has to be re-picked by hand for
  dark mode, and nothing checks the pair was ever contrast-tested); corner radii
  must come from the radius scale, so Tailwind's bare `rounded` — its own 4px
  default, on no step of that scale — is refused; the focus indicator may only
  be spelled out in `src/lib/components/ui/focus-ring/`; and a token tint must
  step in multiples of 10. The tint rule is not cosmetic: `bg-muted` had drifted
  to ten alphas between /15 and /70, three of them stacked as adjacent bars in
  one view, and steps that fine are invisible on screen while reading as
  deliberate in a diff. Adding a colour, a radius or a tint step therefore means
  editing `app.css`, which is the point — one file to read before picking.
  There is one exception and it is a path, not a list of hues:
  `ui/avatar/` holds the categorical avatar palette, eight colours whose only
  job is to differ from each other, so no semantic token could supply them.
- `npm run check:audits` (part of `check`) — fails when the content under the
  `Code paths` of a security audit in `docs/` no longer matches what that
  audit's `Audited commit` saw. If your change trips it, either re-verify the
  audit (bump the SHA + change-log entry) or record in
  [`docs/audit-freshness.json`](docs/audit-freshness.json) why the change
  cannot move a verdict. Recording an object id without reading the diff
  defeats the point.

  The comparison is by git object id, not by commit range, and the
  acknowledgement records those ids — the check prints the current ones when
  it fails, ready to paste. This is what lets an acknowledgement written
  during a PR stay valid after the squash merge: squashing rewrites history,
  not content. The SHA-based version cost a follow-up commit per
  acknowledgement, six of them in two days.

- `npm run check:refs` (part of `check`) — every repo path and `npm run`
  script named in a doc or a source comment must exist. Historical documents
  (changelogs, `todo-archive.md`, snapshot docs) are skipped: their dead
  references are the record, not a defect.
- `npm run check:nul` (part of `check`, and on staged files in the pre-commit
  hook) — fails when a file carries a NUL byte without being declared binary
  in `.gitattributes`. git then treats it as binary: the PR shows
  `Bin N -> N bytes` instead of a diff, and blame and conflict resolution stop
  working. Nothing else catches it, because a NUL is a legal string character
  that formatters, linters and tests are happy to carry. If you need one in a
  string, write it as an escape sequence rather than the raw byte; if the
  file really is binary, declare it next to the other asset types.
- `npm run check:eol` (part of `check`) — fails when an index blob's line
  endings contradict `.gitattributes`. git normalizes a text blob to LF on the
  way in and applies `eol=` on checkout, so a CRLF blob is not one `git add`
  produced: it arrives from a commit made through the GitHub API, which does
  not apply `.gitattributes`. Dependabot commits that way and runs weekly.
  The file then shows as modified in every clone, and neither
  `git checkout --` nor `git reset --hard` clears it — the smudge filter
  rebuilds the working copy from the same bad blob. The fix is a commit:
  `git add --renormalize <path>`, which the gate prints ready to paste.

  The detection is `git ls-files --eol`, which reads the blob. `git status`
  is not a substitute: it compares round-tripped content under a stat cache
  and calls a mixed-ending blob clean even after a fresh checkout.

- `npm run check:docs-impact -- --base <ref>` — CI-only, needs a diff range.
  Fails when a change touches an egress- or storage-relevant path without
  updating `PRIVACY*.md`. No script can decide whether the policy is still
  true, so this only refuses to let the question go unasked. Waive it with a
  commit trailer when it genuinely does not apply:

  ```
  Docs-impact: none — internal refactor, no change to what leaves the device
  ```

Note what these gates deliberately do **not** do: they never rewrite a doc for
you. A number a machine can recompute either gets checked or gets deleted —
prose that silently self-heals is prose nobody reads.

**The gates have their own tests** (`frontend/scripts/*.test.mjs`, run by
`npm run test:unit`). Each one runs the real script as a process against a
throwaway git repository built by
[`scripts/test-support/gate-repo.mjs`](frontend/scripts/test-support/gate-repo.mjs),
because a gate's contract is its exit code and most of them ask git questions
only a real repository can answer. The script under test is copied into the
fixture: the gates are split between finding the repo from `process.cwd()` and
finding it from their own location, and only a copy puts both inside the
fixture rather than letting half of them report on the real checkout. When you
change a gate, change its suite in the same commit — these are the checks that
decide whether everything else is allowed to land.

- `npm run knip` — dead-code analysis. Config in `knip.json`. Output must
  be empty.
- `npm run knip:production` — the same config with the test files out of the
  graph, so an export that only its own test still imports shows up. Deliberate
  exceptions (a reset hook, a teardown seam) carry an `@testseam` JSDoc tag next
  to the reason they exist; everything else on that list is dead.
- `npm run check:java-callers` — part of `npm run check`. A declaration in
  `backend/src/main` needs a caller; the report separates dead code from code
  only tests reach and from a method that is merely too visible. A deliberate
  exception carries `@callerless <reason>` on the declaration.
- `npm run check:rename-residue -- --base <ref>` — CI only, like the docs
  impact check: it needs something to diff against. Fails when a symbol the
  range stopped declaring is still named in a comment, a `@DisplayName` or a
  test name. Deliberate mentions take a `Rename-residue:` commit trailer.
- `npm run check:translations:strict` — Czech-diacritics whitelist over
  `frontend/src`.
- `npm run check:translations:backend:strict` — the same whitelist over the
  Java tree. A separate script because the shared implementation takes one
  target per run; CI invokes it directly from the backend job, which has no
  npm dependencies installed.
- `npm audit --audit-level=high` — must report 0 high+ vulnerabilities.
- `npm run test:unit:coverage` — vitest with thresholds (≥ 65% global,
  per-file 90/85/90 for `content-sanitizer.ts`, 85/80/80 for
  `client.ts`).
- `npm run test:functional:stable` — Playwright functional.
- `npm run test:a11y:stable` — Playwright a11y.
- `npm run test:performance:stable` — initial-load budget.

**Tauri Rust (`cd frontend/src-tauri && cargo check && cargo clippy`)**

- `cargo check` — clean compile.
- `cargo clippy --no-deps -- -D warnings` — clean, no warnings.

If any gate fails locally, fix it before pushing. The CI is not where to
discover regressions.

## Commit Conventions

We use [Conventional Commits](https://www.conventionalcommits.org/) in
**English**. Scopes are the top-level module the change touches:

- `backend` — anything under `backend/`.
- `frontend` — anything under `frontend/src/`, `frontend/scripts/`,
  `frontend/static/` (not src-tauri).
- `tauri` — `frontend/src-tauri/`.
- `ci` — `.github/`, CI scripts.
- `docs` — root + module README/OPERATIONS/CHANGELOG/PRIVACY/SECURITY.
- `repo` — `package.json`, `pom.xml` (build infra), `.gitignore`, etc.
- `release` — version bumps + changelog updates around a release tag.

Examples:

```
feat(backend): add full QRESYNC SELECT with VANISHED
fix(frontend): strip $_ second arg from toErrorMessage callers
chore(tauri): regen Cargo dependency licenses
ci: add cargo clippy gate
```

`BREAKING CHANGE:` in the footer for backend API shape changes (any
field renamed, dropped, or whose type changed in a way that breaks the
generated `frontend/src/lib/api/schema.d.ts`).

## Reproducing Generated Files

Many files in the repo are auto-generated — do not hand-edit them.

| File                                               | Regenerate command                                                                  |
| -------------------------------------------------- | ----------------------------------------------------------------------------------- |
| `frontend/src/lib/api/schema.d.ts`                 | `npm run generate:api:snapshot` (from `frontend/`)                                  |
| `backend/src/test/resources/openapi/api-docs.json` | `mvn -Dopenapi.snapshot.update=true test -Dtest=OpenApiSnapshotTest` (from backend) |
| `frontend/THIRD_PARTY_LICENSES.md`                 | `npm run regen:licenses` (from `frontend/`)                                         |
| `backend/THIRD_PARTY_LICENSES.md`                  | `node backend/scripts/regen-third-party-licenses.mjs`                               |
| `frontend/src-tauri/THIRD_PARTY_LICENSES.md`       | `node frontend/src-tauri/scripts/regen-third-party-licenses.mjs`                    |
| `frontend/src-tauri/resources/NOTICE.txt`          | `npm run regen:sbom:all && npm run regen:notice` (from `frontend/`)                 |

One-shot release regen of everything above — the three license
inventories, the three CycloneDX SBOMs, and the bundled `NOTICE.txt`
(built from those SBOMs) — is `npm run regen:licenses:all` (from
`frontend/`). The Tauri SBOM needs `cargo-cyclonedx`
(`cargo install cargo-cyclonedx`); the script fails fast with that hint
if it is missing.

## Localisation

All user-visible strings live in `frontend/src/lib/i18n/messages/cs.json`
and `en.json`. The keys must match exactly (checked by
`npm run check:i18n`).

Backend error messages have their own bundle in
`backend/src/main/resources/messages_{cs,en}.properties` — the frontend
surfaces them via RFC 9457 `problem.detail` localised through the
`Accept-Language` header.

Code (comments, log messages, exception messages) is **English only**.
The `frontend/scripts/check-translation-whitelist.mjs` script enforces
that for both modules (`--target=frontend|backend`); non-English files
must be added to the module's whitelist with a one-line justification.

## Filing Issues and PRs

- Raw feature ideas start in
  [Discussions → Ideas](https://github.com/TheVoxRox/mail/discussions/categories/ideas);
  once accepted they are converted to issues. Concrete, well-scoped feature
  requests may use the [feature template](.github/ISSUE_TEMPLATE/feature.yml)
  directly.
- Bug reports use the [bug template](.github/ISSUE_TEMPLATE/bug.yml).
- Security issues go to **info@voxrox.org**, never the public tracker —
  see [SECURITY.md](SECURITY.md).
- PRs should include a one-line summary + a checklist of which gates
  were run locally.

The maintainers' active task list and decision log live in `todo.md`.
