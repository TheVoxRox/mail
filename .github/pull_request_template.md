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
- [ ] `npm run knip` (frontend) — dead-code analysis
- [ ] `npm run check` (frontend) — version sync, schema drift, svelte-check
- [ ] `npm run test:unit:coverage` (frontend) — passing thresholds
- [ ] `npm run test:functional:stable` (frontend)
- [ ] `npm run test:a11y:stable` (frontend)
- [ ] `cargo check && cargo clippy -- -D warnings` (Tauri)

## Notes for Reviewers

<!--
Anything reviewers should pay extra attention to: a deliberate threshold
relaxation, a deferred follow-up, a regenerated artefact (schema.d.ts,
THIRD_PARTY_LICENSES.md), etc.
-->
