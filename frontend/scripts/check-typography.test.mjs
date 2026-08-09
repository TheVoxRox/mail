import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { createGateRepo } from './test-support/gate-repo.mjs';

/*
 * Arbitrary font sizes are banned because they opt out of the type scale, and
 * a scale that some components ignore stops being one — which matters most to
 * the users who change their text size. The gate is a regex, so the tests that
 * earn their keep are the boundaries: a hard-coded size is caught, a scale
 * class that merely looks similar is not.
 */

let repo;

beforeEach(() => {
	repo = createGateRepo();
});

afterEach(() => {
	repo.cleanup();
});

describe('check-typography', () => {
	it('passes on components that use the type scale', () => {
		repo.write('frontend/src/lib/A.svelte', '<p class="text-sm font-medium">Hello</p>\n');
		repo.write('frontend/src/lib/B.svelte', '<p class="text-lg">Hello</p>\n');

		const result = repo.run('check-typography.mjs');

		expect(result.status).toBe(0);
		expect(result.stdout).toContain('Typography OK');
	});

	it('fails on a hard-coded rem size, with file and line', () => {
		repo.write(
			'frontend/src/lib/A.svelte',
			['<div>', '  <p class="text-[0.8125rem]">Small</p>', '</div>', ''].join('\n')
		);

		const result = repo.run('check-typography.mjs');

		expect(result.status).not.toBe(0);
		// The script reports with `path.relative`, so the separator is whatever
		// the OS uses — the line number is the part that has to be right.
		expect(result.output.replaceAll('\\', '/')).toContain('src/lib/A.svelte:2');
		expect(result.output).toContain('text-[0.8125rem]');
	});

	it('fails on a hard-coded px size', () => {
		repo.write('frontend/src/lib/A.svelte', '<p class="text-[13px]">Small</p>\n');

		expect(repo.run('check-typography.mjs').status).not.toBe(0);
	});

	/*
	 * The regex must not swallow the arbitrary *colour* and *width* utilities,
	 * which share the `text-[...]` shape but are not type-scale escapes.
	 */
	it('leaves arbitrary colour utilities alone', () => {
		repo.write('frontend/src/lib/A.svelte', '<p class="text-[#ff0000]">Red</p>\n');

		expect(repo.run('check-typography.mjs').status).toBe(0);
	});

	it('only scans Svelte components', () => {
		repo.write('frontend/src/lib/notes.ts', "export const css = 'text-[13px]';\n");

		expect(repo.run('check-typography.mjs').status).toBe(0);
	});

	it('reports every violation, not just the first', () => {
		repo.write('frontend/src/lib/A.svelte', '<p class="text-[13px]">a</p>\n');
		repo.write('frontend/src/lib/B.svelte', '<p class="text-[14px]">b</p>\n');

		const result = repo.run('check-typography.mjs');

		expect(result.output).toContain('A.svelte');
		expect(result.output).toContain('B.svelte');
	});
});
