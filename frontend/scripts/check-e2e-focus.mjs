/**
 * A test that takes focus may not send a key without saying where it landed.
 *
 * The shape this catches is one line of setup and one line of intent:
 *
 *     await trigger.focus();
 *     await page.keyboard.press('Enter');
 *
 * Nothing between them checks the focus arrived, and the app is allowed to
 * move it in that gap. MessageContent parks the reading cursor in the message
 * body a frame after a deliberate open — the Outlook model, deferred so the
 * move lands after SvelteKit's own post-navigation focus reset — so a test
 * that grabs a toolbar button inside that window has it taken back, and the
 * key goes into the body frame instead. The trigger never fires.
 *
 * It fails as something else entirely. The menu never opens, so the failure
 * reads `element(s) not found` or a `toBeFocused()` timeout somewhere below,
 * pointing at the assertion rather than at the missing wait. And it fails
 * *sometimes*: measured at roughly one run in five, which is frequent enough
 * to redden an unrelated PR and rare enough that a rerun clears it, so the
 * cost lands on whoever pushed next.
 *
 * That has now happened three times — #284, #293 and #412 — and the third
 * time it took down four PRs in a day. `waitForFocus` in
 * `src/routes/e2e-helpers.ts` was written after the second and documents the
 * failure in full; the four tests of #412 simply never called it. A helper
 * nothing obliges you to use is a helper you find out about afterwards, which
 * is what this gate changes.
 *
 * Two ways to satisfy it, both already idiomatic here:
 *
 *   1. `await waitForFocus(target)` (or an `expect(...).toBeFocused()`)
 *      between the two. Right when the test is *about* focus: it asserts the
 *      app put focus where the test claims before the key depends on it.
 *   2. `await target.press('Key')` instead of the pair. Playwright focuses the
 *      element as part of the press, so the gap does not exist. Right when the
 *      key send is incidental — activating a button, typing into a field.
 *
 * Scope is deliberately narrow: only a `.focus()` the test performs itself
 * arms the check. Focus that arrives from a click, from Tab, or from the app
 * is a different question with a different answer, and a gate that guessed at
 * it would fail on tests it cannot judge — which teaches people to reach for
 * --no-verify rather than to look.
 *
 * A deliberate exception carries `// focus-settled: <reason>` on the
 * `.focus()` line or directly above the key press. Reason required: the
 * argument for one is the part worth reading.
 */

import { execFileSync } from 'node:child_process';
import { readFile } from 'node:fs/promises';
import path from 'node:path';
import process from 'node:process';

const repoRoot = path.resolve(process.cwd(), '..');

/** A `test(` / `it(` opener, including `test.describe`, `test.skip` and friends. */
const TEST_START = /^\s*(?:test|it)(?:\.\w+)*\s*\(/;

/**
 * A focus the test performs itself. Anchored on `await` because every one in
 * the suite is a statement, and because the alternative — a bare `.focus()` —
 * is in-page code inside `page.evaluate`, which this gate has no business
 * judging.
 *
 * The trailing comment is part of the pattern, not decoration: without it any
 * `await x.focus(); // note` stopped matching and slipped the check silently,
 * which this file's own suite caught.
 */
const FOCUS_CALL = /^\s*await\s+.*\.focus\(\)\s*;\s*(?:\/\/.*)?$/;

/** Either half of "focus settled": the helper, or the assertion it wraps. */
const FOCUS_SETTLED = /waitForFocus\s*\(|toBeFocused\s*\(/;

/**
 * Sending a key through the keyboard rather than through a locator. A
 * `locator.press()` is deliberately absent: it focuses the element itself, so
 * it closes the gap rather than racing it.
 */
const KEY_SEND = /\bkeyboard\s*\.\s*(?:press|type|down|insertText)\s*\(/;

/** The escape hatch, reason required. */
const EXCEPTION = /\/\/\s*focus-settled:\s*(\S.*)$/;

function trackedTestFiles() {
	return execFileSync('git', ['ls-files', '*.e2e.ts'], {
		cwd: repoRoot,
		encoding: 'utf8'
	})
		.split('\n')
		.map((line) => line.trim())
		.filter(Boolean);
}

/**
 * The unchecked pairs in one file.
 *
 * State is a single pending focus rather than a set: a second `.focus()`
 * before any key supersedes the first, because the last one is the one whose
 * target the key will reach.
 */
function findUncheckedPairs(source) {
	const lines = source.split(/\r?\n/);
	const found = [];
	let pending = null;

	lines.forEach((line, index) => {
		const lineNumber = index + 1;

		// A new test cannot inherit the previous one's focus.
		if (TEST_START.test(line)) pending = null;

		if (FOCUS_SETTLED.test(line)) pending = null;

		if (pending && KEY_SEND.test(line)) {
			const excused = EXCEPTION.test(line) || EXCEPTION.test(lines[index - 2] ?? '');
			if (!excused) {
				found.push({ focusLine: pending.line, focusText: pending.text, keyLine: lineNumber });
			}
			// Reported or excused, the pair is closed either way: a second report
			// for the next key press in the same test would name the same cause.
			pending = null;
		}

		if (FOCUS_CALL.test(line) && !EXCEPTION.test(line)) {
			pending = { line: lineNumber, text: line.trim() };
		}
	});

	return found;
}

const files = trackedTestFiles();
const problems = [];
let pairsChecked = 0;

for (const relative of files) {
	const source = await readFile(path.join(repoRoot, relative), 'utf8');
	const unchecked = findUncheckedPairs(source);
	pairsChecked += (source.match(new RegExp(FOCUS_CALL.source, 'gm')) ?? []).length;
	for (const pair of unchecked) {
		problems.push(
			`  ${relative}:${pair.keyLine} sends a key; focus was taken at line ${pair.focusLine} ` +
				`and never checked\n      ${pair.focusText}`
		);
	}
}

if (problems.length > 0) {
	throw new Error(
		`A test takes focus and then sends a key without checking the focus landed ` +
			`(${problems.length} place(s)):\n\n${problems.join('\n')}\n\n` +
			`The app is allowed to move focus in that gap — opening a message parks the reading ` +
			`cursor in the body frame a frame later — so the key can land somewhere else and the ` +
			`test fails far from here, intermittently. Fix it either way:\n` +
			`  - await waitForFocus(target) before the key, when the test is about focus; or\n` +
			`  - await target.press('Key') instead of the pair, when the key send is incidental.\n` +
			`A deliberate exception takes // focus-settled: <reason>.`
	);
}

console.log(
	`E2E focus OK: ${pairsChecked} self-performed focus call(s) across ${files.length} spec file(s), ` +
		`each checked before the next key or followed by none.`
);
