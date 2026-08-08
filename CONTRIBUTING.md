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

- **Java 25 (Temurin)** with Maven. Use `mvn` directly — that is what CI runs.
  The `mvnw` wrapper is committed, but `mvnw.cmd` shells out to
  `powershell.exe` (Windows PowerShell 5.1), which is absent on machines that
  only ship PowerShell 7, so it fails there before Maven ever starts.
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
  translations, `npm run check`, `knip`, and `npm run test:unit`.

Bypass once with `git commit --no-verify` / `git push --no-verify`. Backend
`mvn verify`, Playwright e2e, and Tauri `cargo` checks are intentionally left to
the manual gate / CI — too slow to force on every commit or push.

## Pre-Push Quality Gate

Every change must pass the following locally before opening a PR. CI runs the
same set and will reject the PR if anything red. The numbers in parentheses are
the 2026-08-08 baseline — if you regress them, set per-file thresholds rather
than lowering global floors.

**Backend (`cd backend && mvn clean verify`)**

- `spotless:check` — Eclipse 4.37 formatter via `spotless-maven-plugin`.
- `spotbugs:check` — Bundled in `mvn verify`. Exclusions in
  `spotbugs-exclude.xml`. Run it with `clean`: a `target/` left over from an
  earlier `-Paot package` still holds the generated `__BeanDefinitions`
  classes, and SpotBugs analyses them and fails on code nobody wrote.
- Unit + integration tests (Surefire + Failsafe). Baseline: **1140 + 78 green**.
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
  lint (no arbitrary font-size utilities), version sync, doc-claims lint
  (stack versions and stale phrases in root + module docs vs
  `pom.xml`/`package.json`/`.nvmrc`), audit freshness (see below), OpenAPI
  snapshot drift, `svelte-check` (1408 files / 0 errors).
- `npm run check:audits` (part of `check`) — fails when a commit touches the
  `Code paths` of a security audit in `docs/` after that audit's
  `Audited commit`. If your change trips it, either re-verify the audit
  (bump the SHA + change-log entry) or record in
  [`docs/audit-freshness.json`](docs/audit-freshness.json) why the change
  cannot move a verdict. Bumping `reviewedAt` without reading the diff
  defeats the point.
- `npm run knip` — dead-code analysis. Config in `knip.json`. Output must
  be empty.
- `npm run check:translations:strict` — Czech-diacritics whitelist.
- `npm audit --audit-level=high` — must report 0 high+ vulnerabilities.
- `npm run test:unit:coverage` — vitest with thresholds (≥ 65% global,
  per-file 90/85/90 for `content-sanitizer.ts`, 85/80/80 for
  `client.ts`). Baseline: **475 green**.
- `npm run test:functional:stable` — Playwright functional (**199 green**).
- `npm run test:a11y:stable` — Playwright a11y (**62 green**).
- `npm run test:performance:stable` — initial-load budget (**1 green**).

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
