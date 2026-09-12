# Release process

The ordered mechanics of cutting a release: version, tag, changelog, release
notes, known issues, checksums, draft to public, and the publish decision.

This document owns the steps **around** the build. It does not repeat what the
neighbouring documents own:

| Document                                                                | Owns                                                                   |
| ----------------------------------------------------------------------- | ---------------------------------------------------------------------- |
| [backend/RELEASE_CHECKLIST.md](../backend/RELEASE_CHECKLIST.md)         | Per-candidate verification — did _this_ build pass §1–§9               |
| [backend/OPERATIONS.md](../backend/OPERATIONS.md) "Release channels"    | Channel mechanics, the beta-first release model, HALT and roll-forward |
| [frontend/docs/WINDOWS_SIGNING.md](../frontend/docs/WINDOWS_SIGNING.md) | What the signed build produces and how it is signed                    |
| [frontend/END_USER_README.md](../frontend/END_USER_README.md)           | How a user verifies a download                                         |

**Reading the references.** Both documents number their sections 1–8, so a bare
number would be ambiguous: §6 is Approval here and Sidecar lifecycle there. In
this document, **`§` always means a section of the checklist** and its own
sections are called **steps** — "step 6" is below, "§6" is not. Sections that
exist in only one of the two (§0, §3a, §8a, §8b, §9) carry no ambiguity, but
take the prefix on first mention anyway.

Read [OPERATIONS.md](../backend/OPERATIONS.md) "The release model:
everything goes through beta" first if you have not: **every release goes out as a beta and is
promoted to stable afterwards**, so most runs of this document produce a
prerelease-suffixed version. The steps are identical either way; the tag shape
is what differs, and the workflow derives the prerelease flag from it.

The one recorded exception is the **first ship, `v0.1.0`, which goes straight to
stable** — the closed beta is the whole audience there, and a prerelease-only
repository leaves the stable channel (every installation's default) without a
manifest. The reasoning is in that same OPERATIONS section; from `0.2.0` on the
model holds literally.

**Where the release stands.** `npm run release:status` (from `frontend/`) reads
the newest candidate sheet in the checklist on `main`, the tag on origin, CI for
`main` and for the tagged commit, the signed build, the draft's assets and the
ref and commit its installer was built from, and prints the next step of this
document with the commands for it. It changes nothing — no fetch, no tag, no workflow run — so it is safe to run
at any point, and it answers "where was I" from the sources themselves rather
than from a list someone kept by hand.

## 1. Version

```powershell
cd frontend ; npm run bump:version <X.Y.Z>
```

One command, five files (`frontend/package.json`,
`frontend/src-tauri/tauri.conf.json`, `frontend/src/lib/version.ts`,
`frontend/src-tauri/Cargo.toml`, `backend/pom.xml`). Editing any of them by
hand splits the rest; `npm run check:versions` catches the split, but only
after it exists. Details and the argument grammar: RELEASE_CHECKLIST §0.

The bump is its own commit, and it carries the changelog cut from step 2. The tag
in step 3 points at that commit, so the version files and the changelog must
already say the released version at the moment it is created — otherwise the
shipped build's own `About` screen disagrees with the release page.

## 2. Changelog

In the root [CHANGELOG.md](../CHANGELOG.md), which is canonical for the
monorepo:

1. Rename `## Unreleased` to `## [X.Y.Z] - YYYY-MM-DD` (the publish date, not
   the build date).
2. Open a fresh, empty `## Unreleased` above it.
3. Reshape the section to the subsection order in "Template for a new release"
   at the bottom of the file. Development bullets accumulate in commit order;
   a release section is read by someone who was not there, so it is grouped by
   artifact, not by the order the work happened.
4. Fill `### Known issues` provisionally, and reconcile it with §9 before
   publishing. §9 of the checklist is where that list comes from, but it does
   not exist yet at this point: it is filled after the manual smoke, and the
   smoke runs on the build made from the tag this cut precedes. So write what
   is already known — open blockers, accepted risks, whatever the §8b review
   and the scan review turned up — and treat it as provisional; the condition
   in step 6 is what closes the loop. The draft body draws on the same list but
   is written after step 4a, so it can use the real one — the changelog cut is
   the only one of the two that has to be provisional. The section stays here
   even after the release page is edited, which is the point — release notes are
   mutable, the changelog entry is the record.

Module changelogs (`backend/CHANGELOG.md`) stay technical and migration-facing
and are not cut per release.

## 3. Tag

**Create and push the annotated tag before running the release workflow, and
run the workflow from the tag.**

```bash
git tag -a v<X.Y.Z> -m "VoxRox Mail v<X.Y.Z>"
git push origin v<X.Y.Z>
```

Not optional, and not the same as letting the release page create it. A GitHub
draft release does **not** create a tag — it stores a `target_commitish` and
creates the tag from it at publish time. For the draft this repo has been
carrying since 2026-06-17, that value is the literal branch name `main`
(verified 2026-09-08: `target_commitish: "main"`, and
`git/matching-refs/tags` returns an empty list, so no tag exists yet).
Publishing it would therefore create `v0.1.0` at whatever `main` points to in
that moment, which is not the commit the attached installer was built from.
The tag would name a tree that never shipped, and a published tag cannot be
moved.

Creating the tag first removes the question entirely: the release is attached
to a ref that already exists and already points at the built commit.

Two rules follow from what CI does and does not cover:

- **Tag only a commit that is on `main` and whose CI run was green.**
  [ci.yml](../.github/workflows/ci.yml) triggers on `push` to `main` and on
  pull requests — **not on tags**, so tagging does not re-run anything. The
  green run you are relying on is the one from the merge that produced the
  commit.
- **Before publish, a bad tag is disposable.** Delete it locally and on the
  remote (`git push origin :refs/tags/v<X.Y.Z>`), delete the draft release,
  fix, re-tag. After publish it is immovable and the only repair is
  roll-forward — OPERATIONS "Roll-forward".

## 4. Build the signed artifacts

Actions → **Windows Signed Release** → Run workflow, from the tag. With the tag
selected as the ref, leave `release-tag` empty; the workflow reads it from the
ref. `skip-backend-tests` stays `false` for anything that will be published.

The first step compares the tag against `version` in
`frontend/src-tauri/tauri.conf.json` and fails the run on a mismatch, because
`latest.json` builds its download URL from the tag while taking its version
field from the config — a mismatch ships a manifest whose installer URL 404s.

What the run produces, uploads and attests is described in
[WINDOWS_SIGNING.md](../frontend/docs/WINDOWS_SIGNING.md) "Release artifacts".
Two consequences for this process:

- **Checksums are not a manual step.** The workflow computes the SHA-256 of
  each installer and uploads it as `<installer>.sha256`. There is nothing to
  compute by hand; the step here is to confirm it is attached (step 6).
- **The release is created as a draft**, and with `--prerelease` when the tag
  carries a prerelease suffix. Nothing has reached a user yet — publishing in
  step 7 is the irreversible one.

## 4a. Verify the candidate

The signed build from step 4 is what the checklist runs against. Download the
installer from the draft rather than using a locally assembled one —
RELEASE_CHECKLIST §3a refuses that build for a concrete reason: a bare
`npm run tauri:build` ships the base updater values without a `.sig`.

This is a step of its own rather than a precondition of step 6 because the two
documents interleave here and neither of them said so. It is also the longest
stretch of the release by wall clock, and the only part that cannot be
compressed.

- RELEASE_CHECKLIST §3–§7 on the installed candidate, then §8.1 in the same
  sitting.
- §8.2 overnight; §8a and §9 the following morning.
- §9 is where the blockers, the known issues and the signature are recorded.
  Step 5 below is written against it, and the provisional list from step 2 is
  reconciled against it in step 6.

## 5. Release notes and known issues

The draft's body is what a user reads before downloading, so it is written for
that reader and kept short: someone deciding whether to install should be done
in about a minute. It is not a copy of the changelog section. The changelog is
the full record, and the body links to it instead of repeating it.

The same four parts every release, in this order, so a reader knows where to
look:

1. **Opening** — one or two sentences on what this is, and any limit that
   decides whether it works for the reader at all: the closed beta, the
   platform. It comes first because it is what goes wrong when it is missed —
   without it, a Gmail sign-in refused to an account off the tester list reads
   as a bug.
2. **Highlights** — three to five items in the reader's words, then a link to
   the changelog. Not a feature inventory: a first release invites listing
   everything, which is how the first v0.1.0 draft grew into one. Leave
   implementation terms out (ARIA roles, DPAPI, Ed25519) and say what the
   reader gets instead.
3. **Install or update** — whether this build updates in place, and anything a
   user must do by hand. Downgrades are blocked, so "install the previous
   version" is never a valid note. While the installer is unsigned, say that
   Windows reports an unknown publisher and link
   [END_USER_README.md](../frontend/END_USER_README.md) for verifying the
   download. Do not restate its `gh attestation verify` and
   `certutil -hashfile` commands, because two copies of a command drift; the
   README also explains why the `.sha256` alone is not a tamper defence.
4. **Known issues** — carried over from §9 of the filled checklist, where they
   were recorded as part of the release decision. An empty list is written as
   "none known", not omitted: the next release's operator cannot tell a
   missing section from an oversight.

```markdown
<one or two sentences: what this is, and the limits that decide whether it works for the reader>

## Highlights

- <three to five items, in the reader's words>

All changes: <link to CHANGELOG.md>

## Install or update

<in place or not; anything done by hand; while unsigned, the unknown-publisher warning and a link to END_USER_README.md>

## Known issues

- <from §9, or "None known.">
```

## 6. Approval — the publish decision

Publish only when all of these hold. Each is a fact you can check, not a
judgement call:

- [ ] Checklist §3–§8 filled **for this candidate**, with §9 carrying no open
      blocker.
- [ ] `### Known issues` in the changelog section, and the same list in the
      release body, match checklist §9 as signed. Only the changelog cut is
      written before §9 exists (step 2, item 4); the release body is written
      after it in step 5. This is where the provisional half is reconciled with
      the decision that was actually taken.
- [ ] The tag exists, points at the commit the artifacts were built from, and
      that commit is on `main` with a green CI run (step 3).
- [ ] Assets attached: installer, `.sig`, `.sha256`, `latest.json`. **Never
      publish a release without `latest.json` and `.sig`** — the stable channel
      is a redirect to the newest non-prerelease publish, so a bare release
      becomes "latest" and hands every installation an update-check error on
      every start (RELEASE_CHECKLIST §8b).
- [ ] The prerelease flag matches the tag shape. A suffixed tag published
      without it goes to the stable channel.
- [ ] Docs and web sync done — RELEASE_CHECKLIST §8a, including the
      `voxrox.org` resync that any `PRIVACY*.md` or `SECURITY.md` change
      requires.
- [ ] The scheduled vulnerability scan is not red for an unmitigated reason. A
      red scan is not automatically a blocker — registry throttling and NVD
      feed timeouts trip it without a finding — but the run must be opened and
      the cause named before publishing over it.
- [ ] Temporary pins and audit exceptions reviewed against their removal
      conditions (RELEASE_CHECKLIST §8b).
- [ ] The update smoke vN-1 → vN is either done or its absence is written into
      §9 as an accepted risk. RELEASE_CHECKLIST §3a owns the item and states the
      first-ship exception; it is repeated here because it is the only condition
      in this list whose failure cannot be repaired after publishing — an
      updater cannot fix the updater.

Then record the decision in §9 of the checklist — blockers, known issues,
approved by, date. That block is the release's approval record; the release
page is not, because its body can be edited afterwards without a trace.

## 7. Draft to public

Publish from the GitHub release page. From that moment:

- The **stable** channel serves the build immediately if it is not a
  prerelease (`releases/latest` redirect). A prerelease is skipped by that
  redirect and reaches beta installs only.
- [beta-channel.yml](../.github/workflows/beta-channel.yml) fires on a
  published release and refreshes the moving `beta` manifest. This is expected
  for both a beta ship and a stable promotion — a stable publish is how beta users
  converge onto the stable build. A **red** run of that workflow is a signal,
  not noise: OPERATIONS "The release model" explains why, and `force=true` is
  reserved for the HALT path.

Post-publish, confirm the manifest is actually reachable at the channel URL —
this is the one part of the chain that a green build does not prove, since the
workflow uploads the asset but never fetches it back through the redirect.

## 8. What the first publish turns on permanently

RELEASE_CHECKLIST §8b holds the full list; it is referenced here because these
are the rules that make the _next_ release different from this one, and they
take effect on publish day with no action:

- **`V1__init.sql` is frozen.** Every later schema change is a new `V2+`
  migration. Re-pinning `PINNED_V1_CHECKSUM` was legitimate before the first
  publish and is always a bug after it.
- **Every published release is a full signed build.** Notes-only releases and
  partial builds stay drafts or prereleases.
