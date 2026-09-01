import { execFileSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';
import { FORCED_RULES, WCAG_TAGS } from './a11y-target.js';

const frontendRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');

/*
 * The axe-core the scans actually run, resolved the way `@axe-core/playwright`
 * resolves it rather than by a bare import: axe-core is that package's
 * dependency, not ours, so a second copy at another version would leave a bare
 * import checking a ruleset no scan uses.
 */
const require = createRequire(import.meta.url);
const axe = createRequire(require.resolve('@axe-core/playwright'))('axe-core') as {
	version: string;
	getRules: () => { ruleId: string; tags: string[] }[];
};

const ruleset = axe.getRules();
const rulesetTags = new Set(ruleset.flatMap((rule) => rule.tags));

/**
 * A conformance-level tag (`wcag2a`, `wcag21aa`, …) as opposed to a
 * per-criterion one (`wcag412`) or a suffixed variant (`wcag2a-obsolete`):
 * digits for the generation, then one to three `a`s for the level.
 */
const LEVEL_TAG = /^wcag\d+(a{1,3})$/;

function levelTags(level: 'aa-and-below' | 'aaa'): string[] {
	return [...rulesetTags]
		.filter((tag) => {
			const match = LEVEL_TAG.exec(tag);
			if (!match) return false;
			return level === 'aaa' ? match[1] === 'aaa' : match[1] !== 'aaa';
		})
		.sort();
}

describe('WCAG conformance target', () => {
	/*
	 * The point of the suite: an axe-core bump that ships a new WCAG generation
	 * adds rules under a tag nobody named, and nothing else would say so — axe
	 * reports no violation for a rule it never ran, so the suite would stay
	 * green while measuring against the older standard. This fails instead, and
	 * the fix is one line in a11y-target.ts.
	 */
	it('names every A and AA level tag the installed axe-core carries', () => {
		expect([...WCAG_TAGS].sort()).toEqual(levelTags('aa-and-below'));
	});

	it('leaves AAA out, which the product does not target', () => {
		// Guards the assertion above from being satisfied by widening to
		// everything: axe does carry AAA rules, and they must stay unnamed.
		expect(levelTags('aaa').length).toBeGreaterThan(0);
		for (const tag of levelTags('aaa')) expect(WCAG_TAGS).not.toContain(tag);
	});

	it('forces on only rules that exist and sit inside the target', () => {
		for (const ruleId of Object.keys(FORCED_RULES)) {
			const rule = ruleset.find((candidate) => candidate.ruleId === ruleId);
			// A rule axe renamed or dropped would otherwise go on being "enabled"
			// against nothing, which reads like coverage and is not.
			expect(rule, `${ruleId} is not in axe-core ${axe.version}`).toBeDefined();
			expect(
				rule?.tags.some((tag) => WCAG_TAGS.includes(tag)),
				`${ruleId} carries no level tag from the target`
			).toBe(true);
		}
	});
});

describe('axe scans in the e2e suites', () => {
	/*
	 * Tracked files only, so an editor's scratch copy neither fails the gate nor
	 * hides a real one.
	 */
	const e2eFiles = execFileSync('git', ['ls-files', '-z', 'src'], {
		cwd: frontendRoot,
		encoding: 'utf8',
		maxBuffer: 64 * 1024 * 1024
	})
		.split('\0')
		.filter((file) => file.endsWith('.e2e.ts'));

	it('finds e2e files to check', () => {
		expect(e2eFiles.length).toBeGreaterThan(0);
	});

	/*
	 * A scan that builds its own AxeBuilder can pin its own tags, and a narrower
	 * tag list is invisible in a green suite — axe stays silent about rules it
	 * did not run. That is exactly how the scan in compose.functional.e2e.ts sat
	 * on the WCAG 2.0 baseline after the nine in a11y.e2e.ts had moved on.
	 */
	it.each(['new AxeBuilder', '.withTags('])(
		'routes every scan through wcagScan, not %s',
		(needle) => {
			const offenders = e2eFiles.filter((file) =>
				readFileSync(path.join(frontendRoot, file), 'utf8').includes(needle)
			);
			expect(offenders).toEqual([]);
		}
	);
});
