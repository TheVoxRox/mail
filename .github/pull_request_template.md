<!--
Thank you for the PR. Keep the description short — the diff explains the
"what", here we capture the "why" and the pre-push verification.
-->

## Summary

<!-- 1–3 bullets on the user-visible change and the motivation. -->

## Pre-Push Gate (delete what does not apply)

I have run the relevant gates locally and they are **all green**:

- [ ] `mvn verify` (backend) — Spotless, SpotBugs, unit + IT, Jacoco threshold
- [ ] `npm run lint` (frontend) — Prettier, ESLint, i18n parity
- [ ] `npm run check:i18n:backend` (frontend) — backend `messages.properties` cs/en parity
- [ ] `npm run knip` (frontend) — dead-code analysis
- [ ] `npm run knip:production` (frontend) — dead-code analysis without the test graph
- [ ] `npm run check:rename-residue -- --base <ref>` (frontend) — leftovers of a rename
- [ ] `npm run check` (frontend) — version sync, schema drift, svelte-check
- [ ] `npm run test:unit:coverage` (frontend) — passing thresholds
- [ ] `npm run test:functional` (frontend)
- [ ] `npm run test:a11y` (frontend)
- [ ] `cargo check && cargo clippy -- -D warnings` (Tauri)

## Docs & Web Sync (delete if not applicable)

- [ ] This PR touches `PRIVACY*.md` or `SECURITY.md` — the matching
      [voxrox.org](https://voxrox.org) pages (`/privacy/`, `/support/`, CS + EN
      in [TheVoxRox.github.io](https://github.com/TheVoxRox/TheVoxRox.github.io))
      are updated, or a follow-up is filed.

## Notes for Reviewers

<!--
Anything reviewers should pay extra attention to: a deliberate threshold
relaxation, a deferred follow-up, a regenerated artefact (schema.d.ts,
THIRD_PARTY_LICENSES.md), etc.

A check only a person can take (a screen-reader pass, an installed build) that
the author did not take goes in as `Not verified: …`. When the maintainer takes
it during development, add `Heard by the maintainer: …`: a release counts that
and does not ask for the pass again (docs/DECISIONS.md, "A beta takes no
manual section; the promotion takes them once").
-->
