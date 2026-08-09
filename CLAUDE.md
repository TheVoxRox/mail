# Working in this repository

Conventions an agent cannot infer from the code alone. Everything here has cost
someone a rework at least once. For the human-facing versions see
[CONTRIBUTING.md](CONTRIBUTING.md) (gates, commit format) and
[docs/AUDIT_GUIDE.md](docs/AUDIT_GUIDE.md) (security audits).

## Language

- **Talk to the maintainer in Czech.** Code, comments, commit messages, PR
  descriptions and log/exception strings are **English only** — enforced by
  `check:translations:strict`, which fails on Czech diacritics outside i18n
  bundles and whitelisted fixtures.
- User-visible strings live in `frontend/src/lib/i18n/messages/{cs,en}.json`
  (keys must match) and `backend/src/main/resources/messages_{cs,en}.properties`.
  Unused base-locale keys fail the gate too.

## Build and test

- Use **`mvn`**, not `./mvnw` — `mvnw.cmd` shells out to `powershell.exe` 5.1,
  which is absent on PowerShell-7-only machines.
- Always **`mvn clean verify`**. Without `clean`, SpotBugs analyses the
  `__BeanDefinitions` classes a previous `-Paot package` left in `target/` and
  fails on generated code.
- Playwright runs against a static `vite preview` on 4173. Use
  `npm run test:e2e` / `npm run test:a11y`, which manage that server — a
  stale reused one silently tests an old build.
- `git push` runs the full pre-push gate (3+ min). Run it in the background,
  never `--no-verify`, and **do not touch the working tree while it runs** —
  the gate reads it. `push | tail` reports exit 0 even when the push failed;
  check the exit code directly.

## Documentation is part of the change

- **Every commit gets a bullet in `CHANGELOG.md` under Unreleased**, in the
  same commit — including internal cleanup. Write why, not just what.
- `todo.md` holds live tasks only. Finished work is one line, detail moves to
  `todo-archive.md`. No implementation diaries.
- Docs are typed: **living** (kept current), **snapshot** (dated record with a
  HISTORICAL SNAPSHOT banner, never updated) or **worksheet** (filled per
  release candidate). Check the type before "fixing" a stale-looking claim in a
  snapshot — it is stale on purpose.
- Changing `PRIVACY*.md` or `SECURITY.md` means resyncing
  [voxrox.org](https://voxrox.org) — a **separate repo** at
  `C:\dev\java\TheVoxRox.github.io`, both CS and EN, plus the version header.
- Don't state a number a machine could recompute unless a gate recomputes it.
  Test counts and file counts rot within a week — delete them rather than
  writing them down. What is checked, all inside `npm run check`:
  - `check:docs` — stack versions, plus computed claims (controller
    enumeration in the B3 audit, CI job count vs `ci.yml`). A claim pattern
    that stops matching **fails**; rewording forces you to re-check the number.
  - `check:audits` — audits vs the code under their `Code paths`.
  - `check:refs` — every repo path and `npm run` script named in prose or in a
    comment exists.
  - `check:nul` — no NUL byte in a file `.gitattributes` has not declared
    binary. One turns a source file binary for git (no diff, no blame) while
    passing every formatter and test, because it is a legal string character.
    Deliberate NUL goes in as an escape (`"\0"`), never as the byte. Also runs
    on staged files in the pre-commit hook.
  - `check:docs-impact` (CI only) — egress/storage-relevant changes must touch
    `PRIVACY*.md` or carry a `Docs-impact:` commit trailer.

## Security audits

- A claim lives in exactly **two** places: the audit doc in `docs/` and the
  STRIDE rows in `SECURITY_THREAT_MODEL.md`. `SECURITY_RELEASE_CHECK.md` is an
  index of verdicts and must not restate claims.
- Updating an audit means: (a) the audit itself + version bump + change-log
  entry, (b) a one-line entry in the threat model change log, (c) the verdict
  index row in `backend/SECURITY_RELEASE_CHECK.md`.
- Enumerations must ship with the command that regenerates them. `npm run
check:audits` fails when code under an audit's `Code paths` changed after its
  `Audited commit`.

## Accessibility

- The maintainer uses a screen reader. Data-rich rows are **ARIA grids with
  roving tabindex**, never one button carrying a monolithic `aria-label`.
- Keyboard shortcuts follow Microsoft Outlook. Ctrl+R/F/U collide with the
  webview, so `preventDefault` and respect `defaultPrevented`.
- Sidebars are `region` landmarks named "Podokno …". Never put the role in the
  accessible name.
- Changing a component's behaviour or affordance means finding and updating the
  dependent tests **in the same commit**, then running that suite.

## Claims about tests

Never write "this test would have failed before the fix" without reverting the
fix and watching it fail. Several such claims turned out to be false.

## Desktop, not web

The app runs in a Tauri shell. Middle-click, copy-link-address and other
browser affordances do not exist for internal navigation. Backend data lives in
`%LOCALAPPDATA%\VoxRox\Mail` (dev: `Mail.dev`); `~/.voxrox/mail` is only the
bare-`mvn` fallback.
