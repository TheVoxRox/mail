# Contributing to VoxRox Mail

Thanks for your interest in VoxRox Mail. The project is in active development
and the public surface is still small; this guide is for collaborators or
future maintainers reading the codebase cold.

For security issues, see [SECURITY.md](SECURITY.md). Do **not** open public
GitHub issues for vulnerabilities.

## Repository Layout

```
backend/      Spring Boot 4.x sidecar (Java 25, Maven)
frontend/     SvelteKit 3 + Svelte 5 desktop UI (static adapter)
frontend/src-tauri/  Tauri 2 wrapper that bundles the JAR sidecar
```

Each module is a self-contained build with its own README and dependency
inventory (`THIRD_PARTY_LICENSES.md`). The changelog is the root
[CHANGELOG.md](CHANGELOG.md), for every module.

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
npm run test:functional
npm run test:a11y

# 3. Dev run (Tauri desktop)
npm run tauri:dev
```

The Playwright scripts (`test:functional`, `test:a11y`, `test:performance`
and `test:e2e`, which runs the first two) each build the app and manage their own
`vite preview` on 4173, built with `--mode=e2e` so that `VITE_E2E_MOCK=1` puts
the MSW mocks in front of the backend. `npm run preview:test` starts the same
preview **without** that mode — the ordinary build, talking to a real sidecar —
for driving the app by hand or attaching an external runner. `npm run preview`
is the step below that: bare `vite preview` over whatever `build/` already
holds, with no build of its own, for looking at a build you just produced.

Stop it before running any suite. `playwright.config.ts` sets
`reuseExistingServer: true`, so a preview already answering on 4173 is adopted
as-is: the suites then run against an unmocked build and fail wholesale, in a
way that reads as hundreds of real regressions.

`npm run tauri:smoke:startup` is the unattended version of step 3: it runs
`tauri:dev` for 20 s (`--duration-ms=` to change that), tees both streams to
`frontend/target/tauri-startup-smoke-<stamp>.{stdout,stderr}.log` and stops it again,
reporting whether the app stayed up for the full window. Use it to catch a boot
that dies or floods the log without sitting and watching the window; the
release-build equivalent is `tauri:smoke:release-startup`, in
[`backend/RELEASE_CHECKLIST.md`](backend/RELEASE_CHECKLIST.md) §3.

`npm run tauri:dev:fresh` is step 3 with the dev profile pre-cleaned: it deletes
the WebView2 local storage and the backend `session.json`/`.ready` under
`%LOCALAPPDATA%\VoxRox\Mail.dev` and then starts `tauri:dev`. Reach for it when
the UI behaves as if it remembers something — a stale preference, a session
pointing at a port nothing listens on. It never touches `db/`, `attachments/`,
`crypto.bin` or `logs/`, and it works on the dev root only.

`npm run tauri:smoke:csp-build` checks the packaged build's CSP, which
`check:csp` cannot reach: `tauri.conf.json` sets `script-src 'self'` with no
`'unsafe-inline'`, so the one inline bootstrap script in the SvelteKit fallback
`index.html` runs only if Tauri hashes it into the packaged policy — and dev and
build inject that hash by different paths, so a clean dev console proves nothing
about the installer. The smoke launches the release binary with WebView2 remote
debugging, listens for `securitypolicyviolation` from document start, and
asserts both that nothing was blocked and that the SPA actually mounted. Build
the binary first: `npm run sidecar:rebuild:windows`, then `npm run tauri:build`,
which stops on a sidecar not packaged from the checked-out backend. If the
injection ever breaks, the packaged app opens on a blank window and nothing else
catches it.

### Git hooks (one-time activation)

Hooks live in the tracked `.githooks/` directory. Activate them once per clone
(`core.hooksPath` is local config and is **not** carried over by `git clone`):

```sh
git config core.hooksPath .githooks
```

- **pre-commit** (fast): on every commit, the NUL byte check over the staged
  files (`check:nul`); when the commit touches `frontend/`, runs
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
  acknowledgement, six of them in two days. A change only to comments and
  layout does not trip it in Java, TypeScript, JavaScript or `.properties`,
  and neither does a JSON file whose text changed only in its top-level
  `version`, which is what `bump:version` writes; every other change is
  compared byte for byte.

  Acknowledging is **capped**: records are dated, and a run of them ends at 8
  records or 90 days from the oldest, whichever comes first. Past either, only
  re-verification clears the gate. You get a warning two records or a
  fortnight before the wall, so plan the re-verification then rather than
  meeting the cap on an unrelated PR. See
  [AUDIT_GUIDE.md §2](docs/AUDIT_GUIDE.md) for why the cap is on the union of
  acknowledgements rather than on any one of them.

- `npm run check:refs` (part of `check`) — every repo path and `npm run`
  script named in a doc or a source comment must exist. Historical documents
  (changelogs, snapshot docs) are skipped: their dead
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
- `npm run check:java-imports` — part of `npm run check`. Fails on an unused
  import in a tracked `.java` file. Nothing else reports one: Spotless has a
  `removeUnusedImports` step but it is deliberately **off** here because it
  does not understand `import module` (the reason sits next to the plugin in
  `backend/pom.xml`), and javac has no lint for it — so a leftover survives a
  green `mvn clean verify` indefinitely. A symbol named only in prose does not
  count as used; a `{@link}` or `@throws` does, since those need the import to
  resolve. Only an unqualified occurrence is a use: a fully qualified
  `a.b.Name`, a nested `Outer.Name` or a method reference `Type::name` is
  resolved through its qualifier and needs no import of `Name` or `name`.
  Wildcard and `import module` lines are skipped rather than guessed at.
  TypeScript is out of scope — eslint already covers it.
- `npm run check:java-callers` — part of `npm run check`. A declaration in
  `backend/src/main` needs a caller; the report separates dead code from code
  only tests reach and from a method that is merely too visible. A deliberate
  exception carries `@callerless <reason>` on the declaration, in a `//`
  comment — javadoc would trip Error Prone's `InvalidBlockTag`.
- `npm run check:npm-callers` — part of `npm run check`. An entry in
  `frontend/package.json` `scripts` needs something that reaches it: a
  workflow or hook, another entry, or documentation. The report names the
  route, and leads an unreachable entry with "document it" rather than
  "delete it" — of the six entries that had no caller when this was written,
  five were working tools nobody had written down. `prepare` and the two
  interactive watch loops are exempt in the script itself, with a reason;
  the exemption fails once the entry becomes reachable or disappears. Two
  entries that run the same command fail too: keep the name the callers use
  and move the others onto it.
- `npm run check:test-claims` — part of `npm run check`. Fails on two tests
  in one file with identical bodies, and on a test switched off without a
  reason (`@Disabled("…")`, or a `test-skip: …` comment above the call).
- `npm run check:e2e-focus` — part of `npm run check`. An e2e test that takes
  focus itself may not then send a key without saying where that key lands.
  The app moves focus in the gap on purpose — opening a message parks the
  reading cursor in the body frame a frame later — so the key arrives
  somewhere else and the test fails further down, intermittently, on somebody
  else's PR. Two ways out, both already in the suite: `waitForFocus(target)`
  between the two calls when the test is _about_ focus, or
  `target.press('Key')` instead of the pair when the key send is incidental,
  since Playwright focuses as part of the press. Only a `.focus()` the test
  performs itself arms the gate; focus arriving from a click or from the app
  is a question it cannot judge and does not try to. Exceptions take a
  `// focus-settled: <reason>` comment.
- `npm run check:repackage-excludes` — part of `npm run check`. The fat-jar
  exclude list in `backend/pom.xml` is written twice — on `repackage` (what
  the jar contains) and on `process-aot` (what AOT generates
  `__BeanDefinitions` for) — because Maven has nowhere to share it, and the
  gate holds the two copies equal. When `process-aot` sees an artifact
  `repackage` drops, AOT writes bean definitions for classes the jar does not
  carry: `mvn clean verify` stays green on the full compile classpath and the
  sidecar dies at startup under `spring.aot.enabled=true`. The `openapi`
  profile resets both filters and must reset **both** — resetting one is the
  same failure by another route.
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

  When either of those fails, `npm run check:translations` (no arguments) runs
  the same scan in **report** mode: it lists the offending files with per-file
  counts and exits 0, so you get the whole list in one pass instead of fixing
  one file per red run. `--target=backend` switches trees. It is a diagnostic,
  not a gate, which is why it is not in `npm run check`.

- `npm run audit:ci` — `npm audit`, failing on anything at high or above
  that `frontend/npm-audit-accepted.json` does not accept. Through a script
  rather than `npm audit --audit-level=high`, because npm exits the same way
  when it finds something and when it cannot reach the advisory registry; the
  script reads the verdict and retries only the second.
- `npm run test:unit:coverage` — vitest with thresholds (≥ 65% global,
  per-file 90/85/90 for `content-sanitizer.ts`, 85/80/80 for
  `client.ts`).
- `npm run test:functional` — Playwright functional.
- `npm run test:a11y` — Playwright a11y.
- `npm run test:performance` — initial-load budget.

**Tauri Rust (`cd frontend/src-tauri`)**

- `cargo fmt --check` — no rustfmt diff.
- `cargo check` — clean compile.
- `cargo clippy --no-deps -- -D warnings` — clean, no warnings.
- `cargo test --lib -- redact::` — the log redactor against the vectors it
  shares with the backend; the one Rust test CI runs.
- `cargo audit` — no RustSec vulnerability advisory; accepted warnings live in
  `frontend/src-tauri/.cargo/audit.toml`.

CI builds the crate on Linux, so it never compiles the `cfg(windows)` code.
A change to that code needs `cargo check` and `cargo clippy` on Windows too.

If any gate fails locally, fix it before pushing. The CI is not where to
discover regressions.

## Commit Conventions

We use [Conventional Commits](https://www.conventionalcommits.org/) in
**English**. The type is one of `feat`, `fix`, `perf`, `refactor`, `test`,
`docs`, `build`, `ci` and `chore`. The scope says where the change lives:

- `backend` — `backend/`.
- `frontend` — `frontend/` outside `src-tauri/`.
- `tauri` — `frontend/src-tauri/`.
- `mail` — a mail feature that spans the backend and the frontend.
- `ci` — `.github/` and what only CI runs.
- `repo` — the repository as a whole: build infrastructure, repo-wide tooling,
  docs that belong to no module.
- `release`, `audit`, `security` — the release process and its records, the
  security audits in `docs/`, and the threat model and security policy, whichever
  files they touch.
- `deps`, `deps-dev` — what dependabot writes on its own pull requests.

The repository squash-merges, so a pull request's title becomes the commit on
`main` once it has more than one commit: write the title in the same form.

Examples:

```
feat(mail): make remote-image trust revocable, named, and off in junk
fix(backend): refuse a damaged database before touching it, and say why
docs(audit): re-verify the updater audit against 721bc6e (1.26)
docs(release): cut v0.2.0-beta.4 on the 2026-11-10 candidate sheet
build(deps): bump the npm-production group in /frontend with 4 updates
```

`BREAKING CHANGE:` in the footer for backend API shape changes (any
field renamed, dropped, or whose type changed in a way that breaks the
generated `frontend/src/lib/api/schema.d.ts`).

## Reproducing Generated Files

Many files in the repo are auto-generated — do not hand-edit them.

| File                                               | Regenerate command                                                                  |
| -------------------------------------------------- | ----------------------------------------------------------------------------------- |
| `frontend/src/lib/api/schema.d.ts`                 | `npm run generate:api` (from `frontend/`)                                           |
| `backend/src/test/resources/openapi/api-docs.json` | `mvn -Dopenapi.snapshot.update=true test -Dtest=OpenApiSnapshotTest` (from backend) |
| `frontend/THIRD_PARTY_LICENSES.md`                 | `npm run regen:licenses` (from `frontend/`)                                         |
| `backend/THIRD_PARTY_LICENSES.md`                  | `node backend/scripts/regen-third-party-licenses.mjs`                               |
| `frontend/src-tauri/THIRD_PARTY_LICENSES.md`       | `node frontend/src-tauri/scripts/regen-third-party-licenses.mjs`                    |
| `frontend/src-tauri/resources/NOTICE.txt`          | `npm run regen:sbom:all && npm run regen:notice` (from `frontend/`)                 |
| `frontend/src-tauri/notice-inputs.json`            | written by `npm run regen:notice`, with `NOTICE.txt`                                |

One-shot release regen of everything above — the three license
inventories, the three CycloneDX SBOMs, and the bundled `NOTICE.txt`
(built from those SBOMs) — is `npm run regen:licenses:all` (from
`frontend/`). The Tauri SBOM needs `cargo-cyclonedx`
(`cargo install cargo-cyclonedx`); the script fails fast with that hint
if it is missing.

Regenerating an inventory means rendering the notice too, in the same
PR. `npm run check:notice`, part of `npm run check`, compares the
committed inventories with the digests `regen:notice` recorded in
`notice-inputs.json` and fails while the notice is behind them.

## Maintaining Dependencies

Dependabot opens grouped update pull requests every week (the grouping and the
cooldown are explained at the top of
[`.github/dependabot.yml`](.github/dependabot.yml)), and
[`.github/workflows/vuln-scan.yml`](.github/workflows/vuln-scan.yml) scans the
three dependency trees every weekday. Neither lands or clears anything by
itself.

### Landing a Dependabot pull request

Dependabot commits through the GitHub API, so it cannot do what every
dependency change here owes: a `CHANGELOG.md` entry in the same change, and a
license inventory and installer notice that follow the tree. The headers of
the three `regen-third-party-licenses.mjs` scripts forbid committing a
dependency change without them. Each of its pull requests therefore gets one
commit of yours on its own branch before it merges, the _record commit_:

1. **Bring the branch up to date.** `gh pr checkout <number>`, then
   `git merge origin/main`. Dependabot's base can be a week old, and CI should
   test the bump against the code it will land on.
2. **Install the bumped tree.** For an npm bump, `npm ci` in `frontend/`: the
   license script reads the installed `node_modules`, not the lockfile, so an
   install left over from another branch renders the wrong list. An install
   that fails with `ESTRICTALLOWSCRIPTS` means a new dependency has an install
   script; [DECISIONS.md](docs/DECISIONS.md) "Dependency install scripts are
   denied" says how to answer it, in a commit on this branch.
3. **Regenerate what ships**, from `frontend/`:
   - npm: `npm run regen:licenses`. Run it for a development-only bump too: the
     lockfile can move a transitive package that production shares, and the
     run shows whether the list moved.
   - Cargo: `npm run regen:licenses:tauri`.
   - Maven: `mvn -DskipTests package` in `backend/` first, because the
     inventory describes the packaged jar, then
     `npm run regen:licenses:backend`. A build plugin is not in the jar and
     moves nothing.
   - GitHub Actions: nothing to regenerate.

   When an inventory changed, render the notice from it:
   `npm run regen:sbom:all && npm run regen:notice`. The notice reads all three
   SBOMs and the packaged backend jar, so it needs `mvn -DskipTests package` in
   `backend/` and `cargo` on the PATH whichever ecosystem moved. Commit
   `NOTICE.txt` and `notice-inputs.json` with the inventory. Five dependency
   pull requests in a row once regenerated an inventory and not the notice
   (#890); `npm run check:notice` now fails while the notice is behind.

4. **Read what the new versions change for this app**, in the release notes of
   the parts it uses. Some bumps owe more than reading:
   - jsoup is the parser under `HtmlSanitizer`: run the differential in
     [CONTENT_RENDERING_AUDIT.md](docs/CONTENT_RENDERING_AUDIT.md) "Method when
     jsoup moves". `check:audits` cannot stand in for it, because no
     dependency manifest is under an audit's `Code paths` (DECISIONS.md,
     "Dependency manifests are not part of an audit's `Code paths`").
   - An actions bump that touches a workflow under an audit's `Code paths`,
     such as the signed-release workflow under the updater audit's, fails
     `check:audits` like any other change there: acknowledge the drift or
     re-verify the audit (#878).
   - The `tauri` crate's release notes also list fixes to the NSIS installer.
     Those reach the installer with `@tauri-apps/cli`, the bundler, not with
     the crate.
   - A file `npm run check:eol` reports after the merge carries the line
     endings the API commit wrote; `git add --renormalize <path>` in the record
     commit fixes it.

5. **Write the record commit.** One bullet under `## Unreleased` in
   `CHANGELOG.md`: a dependency that ships goes under its artifact
   (`### Backend`, `### Frontend`, `### Tauri / Release`) and says what changes
   for the app; tooling that does not ship goes under `### Repo`. Both end with
   the pull request number. The commit reads
   `docs(<scope>): record the … bump`, and its body says what was read and what
   was regenerated, which the squash merge keeps.
6. **Push, then arm the merge.** `git push` runs the pre-push gate: run it in
   the background and leave the working tree alone until it ends. Then
   `gh pr merge <number> --auto --squash`.

### When the vulnerability scan goes red

`vuln-scan.yml` runs OWASP Dependency-Check over the backend, `npm run audit:ci`
over the frontend, `cargo audit` over the Tauri crate, and a Trivy gate over
the backend SBOM. A scheduled run that fails or is cancelled opens an issue
labelled `vuln-scan-failure`, or comments on the open one. The issue closes
itself on the next scheduled run that passes with the OWASP gate evaluated. A
manual run neither opens nor closes it, and the schedule skips weekends, so an
issue whose cause was fixed on a Friday stays open until Monday's run.

**First tell "the gate said no" from "the gate never ran".** Most red runs are
the second, and the run summary does not tell them apart; the job log does.

- **Maven Central refused the runner.** It throttles GitHub runners with `429`
  and, in its harder form, `403`, which does not mean the artifact is missing.
  The job dies resolving a plugin (`could not be resolved`,
  `PluginResolutionException`, `NoPluginFoundForPrefixException`) before any
  CVE is read. Central has also answered `Could not find artifact … in central`
  for a jar that was there all along: when a `HEAD` request for that jar on
  `repo1.maven.org` returns 200, the outage was transient and the pinned plugin
  needs no bump. Leave all of these alone; the next night's run settles them.
  Why the workflow neither retries nor soft-fails them is in
  [DECISIONS.md](docs/DECISIONS.md) "A registry that refuses the vulnerability
  scan leaves it red".
- **The npm advisory registry did not answer.** `npm run audit:ci` tells a
  missing verdict from a finding and retries only the first (#388). When the
  retries run out it says `npm audit produced no verdict`, which is not a
  finding.
- **NVD did not answer.** The OWASP job handles this itself: it ends green with
  a warning that CVE gating was not evaluated, and the issue stays open,
  because a green run that evaluated nothing once closed an issue whose finding
  still stood (#852).
- **The OWASP job was cancelled.** It hit its time limit during a cold NVD
  download; the comment above `timeout-minutes` in the workflow is where to
  start. The Trivy gate still covered the backend that night.
- **The run never started.** A run that sits `queued` with no jobs is GitHub
  Actions, not the repository, and that holds for every workflow. Check
  `https://www.githubstatus.com/api/v2/summary.json` (the Actions component and
  the open incidents) and an unrelated recent run. Cancelling, re-running or
  reopening a pull request changes nothing during an outage, and closing a pull
  request disarms its auto-merge; wait for the incident to end.

**A finding is a log that lists findings.** Then:

- **Read the score against the vendor's.** The OWASP gate fails on NVD's CVSS
  score (`failBuildOnCVSS` in the workflow), and NVD's first scores for new
  CVEs can sit far above the vendor's own: four Tomcat CVEs NVD scored 9.1
  Critical were Low to the Apache Tomcat security team (#173). An OWASP failure
  beside a green Trivy gate is the usual sign. The vendor's security page (for
  Tomcat, `https://tomcat.apache.org/security-11.html`) names the fixed
  version. The score changes how urgent the fix is, not whether one is needed:
  the gate stays red until the version moves.
- **Check "no fixed version" against two more sources.** An advisory mirror
  such as osv.dev follows the GitHub advisory database with a delay, and has
  gone on flagging fixed versions for days after the fix shipped
  (jackson-databind, CVE-2026-54515). Before concluding that a fix does not
  exist, read the advisory itself
  (`https://api.github.com/advisories/<GHSA id>`, its `first_patched_version`)
  and whether the artifact is published (`maven-metadata.xml` under
  `https://repo1.maven.org/maven2/`; the search.maven.org index lags as well).
  Where they disagree with the mirror, they win, and the tracking issue should
  say that a scanner reading the mirror will go on flagging until it catches
  up.
- **Fix by moving the version.** For a backend dependency Spring Boot manages,
  override the BOM's property in `backend/pom.xml`, the way
  `sqlite-jdbc.version` is overridden there today: the comment beside it names
  the CVEs, why the version clears them, and when to drop the override. For a
  crate, `cargo update -p <crate>` often reaches the fix; the header of
  `frontend/src-tauri/.cargo/audit.toml` says when an advisory may be accepted
  instead. An npm advisory with no fixed release, on a package that does not
  apply here, can be accepted in `frontend/npm-audit-accepted.json` with a
  reason and an `until` date at most 90 days ahead; the gate fails again once
  the date passes or the advisory is gone, and the entry is then moved or
  deleted. [DECISIONS.md](docs/DECISIONS.md) "An npm advisory with no fix can
  be accepted, for a dated while" has the reasoning.
- **Prove the fix on its branch before merging.**
  `gh workflow run vuln-scan.yml --ref <branch>` runs the whole scan there. It
  restores `main`'s NVD database, and the tracking-issue jobs do not run on a
  manual run. Read what is left in the `backend-vuln-report` artifact. Only a
  finding that is still there and does not apply earns a Dependency-Check
  suppression, and the next run's log must not say
  `Suppression Rule had zero matches`. Dependency-Check reads a four-part
  version like `3.54.1.0` as later than `3.54.1`, so a suppression written for
  an NVD range ending at 3.54.1 matched nothing on the driver it was written
  for, which that CVE had never been reported against (#857). Afterwards delete
  the branch's caches (`gh cache list --ref refs/heads/<branch>`, then
  `gh cache delete <id>`): the scan itself prunes only the caches of closed
  pull requests and superseded entries.

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

Documentation is English as well, and no gate covers it — that scan reads
`frontend/src` and the Java tree, not `*.md`. Two files stay Czech because
they are product rather than documentation: `PRIVACY.md` (the Czech half of a
bilingual policy, paired with `PRIVACY.en.md`, both published on voxrox.org)
and `frontend/END_USER_README.md` (what a Czech end user reads). A quoted
Czech UI string inside an English doc stays as it is — it is the product's own
text, and a translated quote stops matching the screen.

`CHANGELOG.md` is written in English from 2026-09-09 on; older entries are
Czech and stay that way, because a changelog is a dated record of what was
written at the time. How an entry is shaped, user-visible against internal,
is in the header of the file itself.

## Filing Issues and PRs

- Raw feature ideas start in
  [Discussions → Ideas](https://github.com/TheVoxRox/mail/discussions/categories/ideas);
  once accepted they are converted to issues. Concrete, well-scoped feature
  requests may use the [feature template](.github/ISSUE_TEMPLATE/feature.yml)
  directly.
- Bug reports use the [bug template](.github/ISSUE_TEMPLATE/bug.yml).
- Security issues are reported privately, through GitHub's private
  vulnerability reporting or to **info@voxrox.org**, never in the public
  tracker — see [SECURITY.md](SECURITY.md).
- PRs should include a one-line summary + a checklist of which gates
  were run locally.

Open work is tracked in GitHub Issues. Standing decisions, with the reason
behind each and the condition that reopens it, are in
[docs/DECISIONS.md](docs/DECISIONS.md).
