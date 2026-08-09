import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { createGateRepo } from './test-support/gate-repo.mjs';

/*
 * The one gate that cannot decide whether a document is still true — it only
 * refuses to let the question go unasked when a change touches a path that
 * governs what leaves the device. So the behaviour worth pinning is the shape
 * of that refusal: which paths trigger it, that touching the policy satisfies
 * it, and that the waiver works — a gate with no way out gets bypassed
 * wholesale, and a reason in the history is worth more than a green check.
 */

const EGRESS_PATH = 'frontend/src-tauri/capabilities/default.json';

let repo;

/** Commits a baseline and returns the ref to diff against. */
function baseline() {
	repo.write('README.md', '# Fixture\n');
	repo.write(EGRESS_PATH, JSON.stringify({ permissions: [] }));
	repo.write('PRIVACY.md', '# Privacy\n');
	repo.write('PRIVACY.en.md', '# Privacy\n');
	repo.commit('baseline');
	return repo.git(['rev-parse', 'HEAD']);
}

beforeEach(() => {
	repo = createGateRepo();
});

afterEach(() => {
	repo.cleanup();
});

describe('check-docs-impact', () => {
	it('passes when nothing egress-relevant changed', () => {
		const base = baseline();
		repo.write('README.md', '# Fixture\n\nMore prose.\n');
		repo.commit('docs only');

		const result = repo.run('check-docs-impact.mjs', ['--base', base]);

		expect(result.status).toBe(0);
		expect(result.stdout).toContain('no rule triggered');
	});

	it('fails when a triggering path changes without the privacy policy', () => {
		const base = baseline();
		repo.write(EGRESS_PATH, JSON.stringify({ permissions: ['http:default'] }));
		repo.commit('widen capabilities');

		const result = repo.run('check-docs-impact.mjs', ['--base', base]);

		expect(result.status).toBe(1);
		expect(result.stderr).toContain('may need a documentation update');
		expect(result.stderr).toContain('capabilities');
	});

	it('passes when the change updates both privacy policies', () => {
		const base = baseline();
		repo.write(EGRESS_PATH, JSON.stringify({ permissions: ['http:default'] }));
		repo.write('PRIVACY.md', '# Privacy\n\nNow with an extra endpoint.\n');
		repo.write('PRIVACY.en.md', '# Privacy\n\nNow with an extra endpoint.\n');
		repo.commit('widen capabilities and say so');

		expect(repo.run('check-docs-impact.mjs', ['--base', base]).status).toBe(0);
	});

	it('is not satisfied by updating only one language of the policy', () => {
		const base = baseline();
		repo.write(EGRESS_PATH, JSON.stringify({ permissions: ['http:default'] }));
		repo.write('PRIVACY.md', '# Privacy\n\nCzech only.\n');
		repo.commit('half a policy update');

		const result = repo.run('check-docs-impact.mjs', ['--base', base]);

		expect(result.status).toBe(1);
		expect(result.stderr).toContain('PRIVACY.en.md');
	});

	it('accepts a Docs-impact trailer as a waiver, and says so out loud', () => {
		const base = baseline();
		repo.write(EGRESS_PATH, JSON.stringify({ permissions: ['http:default'] }));
		repo.git(['add', '-A']);
		repo.git([
			'commit',
			'--quiet',
			'--no-verify',
			'-m',
			'refactor: reshuffle capabilities\n\nDocs-impact: none — no change to what leaves the device'
		]);

		const result = repo.run('check-docs-impact.mjs', ['--base', base]);

		expect(result.status).toBe(0);
		expect(result.stdout).toContain('Docs impact waived');
		expect(result.stdout).toContain('no change to what leaves the device');
	});

	it('skips the all-zero base a first push to a new branch reports', () => {
		baseline();

		const result = repo.run('check-docs-impact.mjs', ['--base', '0'.repeat(40)]);

		expect(result.status).toBe(0);
		expect(result.stdout).toContain('no base commit');
	});

	it('refuses to run without a base rather than guessing one', () => {
		baseline();

		const result = repo.run('check-docs-impact.mjs');

		expect(result.status).toBe(2);
		expect(result.stderr).toContain('--base');
	});

	it('reports the shallow-clone failure mode by name', () => {
		baseline();

		const result = repo.run('check-docs-impact.mjs', ['--base', 'deadbeefdeadbeef']);

		expect(result.status).toBe(2);
		expect(result.stderr).toContain('fetch-depth: 0');
	});
});
