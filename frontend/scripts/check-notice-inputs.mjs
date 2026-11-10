/**
 * Fails when the bundled NOTICE.txt has fallen behind the license inventories.
 *
 * `frontend/src-tauri/resources/NOTICE.txt` ships inside the installer and is
 * shown in Settings, so a stale one hands the user a component list that does
 * not match the package. It went stale in five dependency PRs in a row (#872,
 * #873, #875, #876, #886): each regenerated the inventory its bump needed and
 * none rendered the notice again, and nothing noticed until the release cut
 * (#890) compared them by hand.
 *
 * Rendering the notice here, in memory, and comparing is not possible. It is
 * built from the three CycloneDX SBOMs, the packaged backend jar, `cargo tree`
 * and the installed `node_modules` — none committed, all needing a full
 * toolchain, and the npm half different per platform. Comparing the notice's
 * component list with the inventories' is not possible either, not for npm:
 * the two are produced by different tools (cyclonedx-npm and license-checker)
 * and do not list the same packages even when both are fresh. So the generator
 * records a digest of each inventory's component list beside the notice
 * (`scripts/lib/notice-inputs.mjs` says exactly what is hashed), and this gate
 * recomputes the digests from the committed inventories. It reads only
 * committed files, so it gives the same answer on every platform and in CI.
 *
 * What it cannot see: a dependency bump that regenerated no inventory at all.
 * The inventories are the trigger. Release checklist section 8a still covers
 * that half, by regenerating everything on the release platform.
 *
 * A comparison by content would replace this once the two npm lists agree —
 * then the notice could be checked against the inventories directly and the
 * record could go.
 *
 * Usage: node scripts/check-notice-inputs.mjs (or `npm run check:notice`).
 */

import { execFileSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import path from 'node:path';
import process from 'node:process';
import { fileURLToPath } from 'node:url';
import { NOTICE_INVENTORIES, NOTICE_RECORD, inventoryDigest } from './lib/notice-inputs.mjs';

/*
 * From where this script lives, like the NUL and line-ending gates: a hook in a
 * linked worktree exports GIT_DIR, which makes git take the current directory
 * for the top of the tree.
 */
const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');

const REGEN =
	'Render the notice again — from frontend/, `npm run regen:sbom:all && npm run regen:notice`, ' +
	'or `npm run regen:licenses:all`, which also regenerates the inventories — and commit ' +
	`NOTICE.txt together with ${NOTICE_RECORD}. Do not edit the record by hand: a digest copied ` +
	'into it says the notice matches an inventory it was never rendered from.';

function fail(message, remedy = REGEN) {
	console.error(`check:notice: ${message}\n\n${remedy}`);
	process.exit(1);
}

/*
 * Tracked, not merely present: a record that was rendered but never added
 * passes here and fails in CI, where the checkout has no such file.
 */
const wanted = [NOTICE_RECORD, ...NOTICE_INVENTORIES];
const tracked = new Set(
	execFileSync('git', ['ls-files', '-z', '--', ...wanted], { cwd: repoRoot, encoding: 'utf8' })
		.split('\0')
		.filter(Boolean)
);
const untracked = wanted.filter((file) => !tracked.has(file));
if (untracked.length > 0) {
	fail(
		`not tracked by git: ${untracked.join(', ')}.`,
		'`git add` them: CI checks out only what is committed, so it would not find them.'
	);
}

let record;
try {
	record = JSON.parse(readFileSync(path.join(repoRoot, NOTICE_RECORD), 'utf8'));
} catch (e) {
	fail(`cannot read ${NOTICE_RECORD}: ${e.message}`);
}
const recorded = record?.inventories;
if (!recorded || typeof recorded !== 'object' || Array.isArray(recorded)) {
	fail(`${NOTICE_RECORD} has no "inventories" object.`);
}

const problems = [];

for (const file of Object.keys(recorded)) {
	if (!NOTICE_INVENTORIES.includes(file)) {
		problems.push(`  ${file}: in the record, but not one of the inventories the notice covers`);
	}
}

for (const file of NOTICE_INVENTORIES) {
	let digest;
	try {
		digest = inventoryDigest(readFileSync(path.join(repoRoot, file), 'utf8'));
	} catch (e) {
		problems.push(`  ${file}: ${e.message}`);
		continue;
	}
	if (!(file in recorded)) {
		problems.push(`  ${file}: missing from the record`);
	} else if (recorded[file] !== digest) {
		problems.push(`  ${file}: changed since NOTICE.txt was last rendered`);
	}
}

if (problems.length > 0) {
	fail(
		`frontend/src-tauri/resources/NOTICE.txt is behind the license inventories.\n` +
			problems.join('\n')
	);
}

console.log(
	`Notice inputs OK: NOTICE.txt was rendered from the current ${NOTICE_INVENTORIES.length} license inventories.`
);
