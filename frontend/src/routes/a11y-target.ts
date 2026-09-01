/**
 * What the axe scans in this repository are measured against, kept apart from
 * the Playwright helpers so `a11y-target.test.ts` can check it against the
 * installed axe-core without loading a browser harness.
 */

/**
 * The conformance target: WCAG 2.2 AA.
 *
 * The tags are cumulative rather than a version selector — axe files each rule
 * under the generation that introduced its criterion, so `wcag22aa` alone would
 * run the handful of rules 2.2 added and nothing else. Naming 2.2 therefore
 * means naming 2.0 and 2.1 alongside it. AAA is deliberately absent; the
 * product targets AA.
 *
 * `a11y-target.test.ts` fails when the installed axe-core carries a level tag
 * this list does not name, so an axe-core bump that ships a new WCAG generation
 * has to be answered rather than quietly narrowing what the suite measures.
 */
export const WCAG_TAGS = ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa'];

/**
 * Rules that a tag alone will not start.
 *
 * `enabled: false` is not what holds a rule back — `runOnly` starts a disabled
 * rule whose tag matches, which is how `target-size` (2.5.8) runs. The flag
 * that keeps one out is `experimental`, and the only way past it is naming the
 * rule here.
 *
 * `label-content-name-mismatch` (2.5.3 Label in Name) is worth turning on even
 * though it currently finds nothing to test: the criterion it guards — the
 * visible label has to be contained in the accessible name — is the one this
 * codebase is most exposed to, because controls carry a hand-written Czech
 * `aria-label` next to their visible text and the two are maintained
 * separately. A rule that is inapplicable today is what catches the pair
 * drifting apart tomorrow.
 *
 * The other experimental rule, `css-orientation-lock` (1.3.4), stays off: it
 * sweeps every stylesheet for an orientation lock, and the app is a desktop
 * window with no orientation media query in its styles and no way for the user
 * to rotate it.
 */
export const FORCED_RULES = {
	'label-content-name-mismatch': { enabled: true }
};
