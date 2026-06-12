# Static analysis — frontend

Frontend counterpart of [backend/docs/STATIC_ANALYSIS.md](../../backend/docs/STATIC_ANALYSIS.md):
what runs, at which severity, and the policy decisions behind it.

## Tooling

| Tool                              | When                           | Gate                                                           |
| --------------------------------- | ------------------------------ | -------------------------------------------------------------- |
| TypeScript strict + svelte-check  | `npm run check` / pre-push     | fails on any error                                             |
| ESLint (js/ts/svelte recommended) | `npm run lint` / pre-push      | fails the run                                                  |
| ESLint type-aware promise rules   | `src/**/*.ts`                  | `no-floating-promises`, `await-thenable` as errors             |
| knip                              | `npm run knip` / pre-push      | unused files, exports, types, dependencies                     |
| i18n key checks                   | `check:i18n` (also pre-commit) | locale parity **and** unused base-locale keys fail             |
| translations whitelist            | `check:translations:strict`    | Czech diacritics outside i18n need a justified whitelist entry |

Policy notes:

- **Fire-and-forget promises must be explicit.** A dropped rejection in the
  boot/sidecar path surfaces as a silent hang or a spurious unhandled-rejection
  boot error; intentional cases are written as `void promise` with a comment
  (see `lib/i18n/index.ts` for the pattern).
- **Unused i18n keys fail the gate.** `scripts/check-i18n-keys.mjs` recognizes
  literal lookups and dynamic template prefixes (`` `folder.${role}` ``,
  `` `palette.group_${id}` ``); keys consumed via property access need a
  justified entry in its `USED_INDIRECTLY` list.

## Complexity audit (2026-06-12)

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
