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
const EXPERIMENTAL_WCAG_RULES = ['label-content-name-mismatch'];

/**
 * Rules axe files under `best-practice` rather than under a criterion, run
 * because each states a rule of ARIA or of page structure this codebase holds
 * itself to: a role allowed on its element (`aria-allowed-role`), a name on
 * every dialog and tree item, no `role="presentation"` on something focusable
 * or named, one `main`, landmarks of one kind told apart by name, an `<h1>`
 * on every page and no empty heading, no positive `tabindex`. axe stays silent
 * about a rule it does not run, so before these were named a dialog without a
 * name or an ARIA role its element does not allow passed every scan.
 *
 * Every such rule of the installed axe-core is either here or in
 * `BEST_PRACTICE_LEFT_OUT` with its reason, and `a11y-target.test.ts` fails a
 * bump that adds one to neither. Experimental and review-item rules are not
 * asked about: they report "check by hand", which a scan cannot answer.
 */
export const BEST_PRACTICE_RULES = [
	'accesskeys',
	'aria-allowed-role',
	'aria-dialog-name',
	'aria-text',
	'aria-treeitem-name',
	'empty-heading',
	'empty-table-header',
	'heading-order',
	'image-redundant-alt',
	'label-title-only',
	'landmark-banner-is-top-level',
	'landmark-contentinfo-is-top-level',
	'landmark-main-is-top-level',
	'landmark-no-duplicate-banner',
	'landmark-no-duplicate-contentinfo',
	'landmark-no-duplicate-main',
	'landmark-one-main',
	'landmark-unique',
	'meta-viewport-large',
	'page-has-heading-one',
	'presentation-role-conflict',
	'scope-attr-valid',
	'skip-link',
	'tabindex',
	'table-duplicate-name'
];

/** The best-practice rules that stay off, each with its reason. */
export const BEST_PRACTICE_LEFT_OUT: Record<string, string> = {
	region:
		'Content outside a landmark is no WCAG failure, and the pane toggle stands outside the rail landmark on purpose (docs/DECISIONS.md).'
};

export const FORCED_RULES = {
	...Object.fromEntries(
		[...EXPERIMENTAL_WCAG_RULES, ...BEST_PRACTICE_RULES].map((rule) => [rule, { enabled: true }])
	)
};

/**
 * Check options the scans run with. axe merges them into each check's own
 * defaults, so only the keys named here change.
 *
 * `color-contrast` with `ignoreLength`: by default axe declines to judge text
 * of one character and files a failing one under "incomplete", which a scan
 * reading `violations` never sees. One character is exactly what the count
 * badges show ("3" unread), and the badge on the current sidebar item sat at
 * 4.38:1 against the 4.5:1 of WCAG 1.4.3 while every scan stayed green. The
 * criterion has no length exemption, so the scans do not take one either.
 */
export const CHECK_OPTIONS = {
	'color-contrast': { options: { ignoreLength: true } }
};
