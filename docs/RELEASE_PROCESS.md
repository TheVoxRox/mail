# Release process

The steps of a release, top to bottom. This page says what to do and nothing
else:

- Why each rule exists: [DECISIONS.md](DECISIONS.md) "Release process".
- Channels, HALT and roll-forward: [OPERATIONS.md](../backend/OPERATIONS.md)
  "Release channels".
- What each checklist section (§) contains:
  [RELEASE_CHECKLIST.md](../backend/RELEASE_CHECKLIST.md).
- What the signed build produces:
  [WINDOWS_SIGNING.md](../frontend/docs/WINDOWS_SIGNING.md).

**At any point, from `frontend/`:**

```bash
npm run release:status
```

It reads `main`, the tag, CI, the signed build and the draft, and prints the
next step with its commands. It changes nothing, so run it whenever you are
unsure where you are.

## 1. Which release is this, and what it runs

- **Beta, `vX.Y.Z-beta.N`.** Anything that ships changed since the last
  release. This is the normal case.
- **Promotion, `vX.Y.Z`.** The code of the last beta under the stable number,
  once its beta cycle ended with no open blocker. Check before you start:

  ```bash
  git diff --stat vX.Y.Z-beta.N origin/main -- . ':(exclude)*.md' ':(exclude)frontend/scripts'
  ```

  Whatever this lists must not reach the installer. If something does, a fix
  or a dependency bump, cut another beta instead.

What runs:

- **Every candidate:** §0–§2, the signed build, Release Candidate Smoke (a
  fresh install, and the update from the latest stable release), §8a, §8b, §9,
  and Release Channel Check after publishing.
- **A beta adds nothing by hand.** §3 to §8 are taken during development. The
  only exception is §3b, the updater, and only when `release:status` says it
  does not carry.
- **A promotion takes by hand only what `release:status` reports as re-take**,
  which is nothing when its installer is the beta's code. It also takes what
  the beta's §9 named as not yet seen or heard.

## 2. Cut — first pull request

Before you start, CI on `main` must be green. Work on a branch from `main`,
from `frontend/` unless a step says otherwise.

1. **Licenses.** `npm ci`, then `npm run regen:licenses:all`. If anything but
   the `Generated:` line of `NOTICE.txt` changed, commit it on its own.
2. **Version.** `npm run bump:version X.Y.Z-beta.N` (or `X.Y.Z`). Never edit a
   version by hand.
3. **Changelog.** In the root `CHANGELOG.md`, rename `## Unreleased` to
   `## [X.Y.Z-beta.N] - YYYY-MM-DD` and open an empty `## Unreleased` above it.
   Use the publish date. Regroup the bullets into the order of "Template for a
   new release" at the bottom of the file. Write `### Known issues` from what
   is known now; step 4 reconciles it with §9. `backend/CHANGELOG.md` is not
   cut.
4. **Build and test locally** (checklist §1 and §2):

   ```bash
   cd ../backend ; mvn '-Dapp.data-dir=target/test-data' clean verify
   cd ../frontend ; npm run sidecar:rebuild:windows
   npm run generate:api ; npm run check:i18n ; npm run build ; npm run test:unit ; npm run test:e2e
   ```

   `generate:api` must leave no diff.

5. **Audit drift.** `npm run check:audits` must pass with nothing to
   acknowledge: the version `bump:version` writes into `tauri.conf.json` is not
   drift. If it reports drift, something besides the version moved under an
   audit; find out what before going on.
6. **Candidate sheet.** Add a new sheet at the top of the appendix of
   `backend/RELEASE_CHECKLIST.md` (skeleton below). Fill the object id table
   from the committed bump:

   ```bash
   for p in backend/src backend/pom.xml backend/scripts frontend/src frontend/src-tauri frontend/package.json frontend/package-lock.json; do
     printf '| `%s` | `%s` |\n' "$p" "$(git rev-parse --short=12 "HEAD:$p")"
   done
   ```

   Tick §0, §1 and §2 with one line each. Leave every section from §3 on open.

7. **Pull request,** titled:

   ```text
   docs(release): cut vX.Y.Z-beta.N on the YYYY-MM-DD candidate sheet
   ```

   The commit needs the trailer `Docs-impact: none —` with one sentence saying
   that only the version changed in `tauri.conf.json`. Squash-merge it, and
   merge nothing else until the tag exists.

## 3. Tag and build

Once the cut is on `main` and CI for it is green, `npm run release:status`
prints these commands with the commit filled in:

```bash
git tag -a vX.Y.Z-beta.N -m "VoxRox Mail vX.Y.Z-beta.N" <commit>
git push origin vX.Y.Z-beta.N
gh workflow run windows-signed-release.yml --ref vX.Y.Z-beta.N
```

Always build from the tag. The workflow refuses a tag that does not exist on
origin, names another commit, is not on `main` or has no green CI run. Leave
`skip-backend-tests` at its default, `false`.

The signed build takes about 12 minutes and creates the draft release, marked
as a prerelease when the tag has a suffix. Release Candidate Smoke then starts
on its own and takes about 3 minutes. If it did not start, `release:status`
prints the command.

**A red smoke stops the release.** Fix the problem, then re-tag (see "When
something goes wrong").

## 4. Decide — second pull request

1. **Manual sections.** `npm run release:status` says for each open section
   either _carry_ or _re-take_.
   - **Beta:** leave §3 to §8 open. §3b: tick it as carried when
     `release:status` says so. Otherwise it is taken after publishing (step 5).
   - **Promotion:** tick each section that carries as "carried from the
     `vX.Y.Z-beta.N` sheet". Take each re-take on the installed candidate,
     using the installer from the draft and never a local build, then tick it.
2. **§9 on the sheet:** blockers, known issues, the signed build and smoke run
   links, and `Approved: the maintainer, YYYY-MM-DD, by merging #N`. On a beta,
   also list what nobody has seen or heard yet: a pull request's "Not verified"
   note that no `Heard by the maintainer: …` note on it answers.
3. **Release notes.** Write `docs/release-notes/<tag>.md` from the template
   below, named after the tag (`v0.2.0-beta.4.md`).
4. **Known issues match.** The changelog section, the release notes and §9
   list the same known issues.
5. **Before approving, check:**
   - `npm run check` is green.
   - If `PRIVACY*.md` or `SECURITY.md` changed since the last release,
     voxrox.org is resynced, both CS and EN.
   - `frontend/END_USER_README.md` matches this version.
   - No new temporary pin or audit exception is left without its removal
     condition (checklist §8b).
   - The last scheduled vulnerability scan is green, or its run is opened and
     the cause named in §9. Registry throttling turns it red without a
     finding.
6. **Pull request,** titled:

   ```text
   docs(release): record the vX.Y.Z-beta.N release decision and notes
   ```

   **Merging it is the approval.**

## 5. Publish

```bash
gh release edit <tag> --notes-file docs/release-notes/<tag>.md
```

Run `npm run release:status` again. It must report every section ticked, or
left for later on a beta, and the body matching the file. Check that the draft
is a prerelease exactly when the tag has a suffix. Then publish the draft on
the GitHub release page.

**Publishing cannot be undone.** The files and the tag lock. A stable release
reaches every installation on the stable channel at once.

After publishing, two workflows run on their own, and both must end green:

- **Release Channel Check**, which fetches `latest.json` through the channel.
- **Beta Channel Manifest**, which moves the beta channel. A red run here is a
  signal, never noise. Do not re-run it with `force`; see OPERATIONS.md.

**Beta with §3b open:** let an installation of the previous beta, on the beta
channel, take this release through its updater. Record the result on the sheet
in a follow-up pull request.

## When something goes wrong

- **Before publishing**, a bad tag is disposable. Delete the draft release,
  then delete the tag and tag again:

  ```bash
  git tag -d vX.Y.Z-beta.N
  git push origin :refs/tags/vX.Y.Z-beta.N
  ```

  A sheet that a re-cut replaces is deleted. Git keeps it.

- **After publishing**, only roll-forward to a higher version: OPERATIONS.md
  "Roll-forward".
- **A faulty beta already published:** OPERATIONS.md "HALT — withdrawing a
  faulty beta".

## Templates

### Candidate sheet

````markdown
### Candidate YYYY-MM-DD — tag `vX.Y.Z-beta.N` (see the object ids below)

```text
Platform:  Windows 11 Pro x64, machine <name> (§0–§2)
Tester:    machine half automated; §3–§9 are the maintainer's
```

| path          | object id |
| ------------- | --------- |
| `backend/src` | `<id>`    |

- [x] **§0 Version** — `npm run bump:version X.Y.Z-beta.N`, `check:versions` OK.
- [x] **§1 Backend build** — `clean verify` green; sidecar packaged and its smoke passed.
- [x] **§2 Frontend automation** — `generate:api` without drift, `check:i18n`, `build`, `test:unit`, `test:e2e`.
- [ ] **§3 Fresh install**
- [ ] **§3a Installer behaviour**
- [ ] **§3b Updater**
- [ ] **§4 Account flows**
- [ ] **§5 Mail workflows**
- [ ] **§6 Sidecar lifecycle**
- [ ] **§7 Diagnostics**
- [ ] **§8 Long run**
- [ ] **§9** — Blockers: … Known issues: … Approved: …

Findings:

- <only what went wrong or stays open>
````

### Release notes

Written for someone deciding whether to install, and readable in a minute. The
opening states the limits that decide whether the build works for the reader at
all, such as the closed beta and the platform. The notes link to the changelog
and to `END_USER_README.md` instead of copying them, and an empty known-issues
list is written as "None known.".

```markdown
<one or two sentences: what this is, and the limits that decide whether it works for the reader>

## Highlights

- <three to five items, in the reader's words, no implementation terms>

All changes: <link to CHANGELOG.md>

## Install or update

<in place or not; anything done by hand; while unsigned, the unknown-publisher warning and a link to END_USER_README.md>

## Known issues

- <from §9, or "None known.">
```
