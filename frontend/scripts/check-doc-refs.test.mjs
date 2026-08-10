import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { createGateRepo } from './test-support/gate-repo.mjs';

/*
 * Markdown links were already safe — a broken one is visible when you click
 * it. What rots unseen is the path named in prose or in a comment, which this
 * gate resolves against *tracked* files rather than the working tree: a
 * developer's checkout carries build output and scratch files that CI does
 * not, and a check whose answer depends on that is the worst kind — green
 * locally, red for everyone else.
 */

let repo;

function seed({ scripts = {} } = {}) {
	repo.write('frontend/package.json', JSON.stringify({ name: 'f', scripts }, null, '\t'));
	repo.write('frontend/src/lib/real.ts', 'export const x = 1;\n');
}

beforeEach(() => {
	repo = createGateRepo();
});

afterEach(() => {
	repo.cleanup();
});

describe('check-doc-refs', () => {
	it('passes when every named path exists', () => {
		seed();
		repo.write('docs/GUIDE.md', 'See `frontend/src/lib/real.ts` for details.\n');
		repo.commit();

		const result = repo.run('check-doc-refs.mjs');

		expect(result.status).toBe(0);
		expect(result.stdout).toContain('Doc refs OK');
	});

	it('fails on a path named in prose that does not exist', () => {
		seed();
		repo.write('docs/GUIDE.md', 'See `frontend/src/lib/ghost.ts` for details.\n');
		repo.commit();

		const result = repo.run('check-doc-refs.mjs');

		expect(result.status).toBe(1);
		expect(result.stderr).toContain('ghost.ts');
	});

	it('fails on a dead path named in a source comment', () => {
		seed();
		repo.write(
			'frontend/src/lib/real.ts',
			'// see frontend/src/lib/ghost.ts\nexport const x = 1;\n'
		);
		repo.commit();

		const result = repo.run('check-doc-refs.mjs');

		expect(result.status).toBe(1);
		expect(result.stderr).toContain('ghost.ts');
	});

	/*
	 * Resolution is against tracked paths on purpose. An untracked file exists
	 * on the author's machine and nowhere else, so treating it as resolved
	 * would let a reference ship that is already broken for every other clone.
	 */
	it('does not accept an untracked file as a resolution', () => {
		seed();
		repo.write('docs/GUIDE.md', 'See `frontend/src/lib/scratch.ts`.\n');
		repo.commit();
		repo.write('frontend/src/lib/scratch.ts', 'export const y = 2;\n');

		expect(repo.run('check-doc-refs.mjs').status).toBe(1);
	});

	it('resolves a named npm script', () => {
		seed({ scripts: { build: 'vite build' } });
		repo.write('docs/GUIDE.md', 'Run `npm run build` first.\n');
		repo.commit();

		expect(repo.run('check-doc-refs.mjs').status).toBe(0);
	});

	it('fails on an npm script that is not in package.json', () => {
		seed({ scripts: { build: 'vite build' } });
		repo.write('docs/GUIDE.md', 'Run `npm run deploy` first.\n');
		repo.commit();

		const result = repo.run('check-doc-refs.mjs');

		expect(result.status).toBe(1);
		expect(result.stderr).toContain('deploy');
	});

	/*
	 * A changelog entry describing a migration file is correct precisely
	 * because the file is gone; demanding it exist would force the record to
	 * be falsified.
	 */
	it('skips historical documents, whose dead references are the record', () => {
		seed();
		repo.write('CHANGELOG.md', 'Removed `backend/src/main/resources/db/V2__gone.sql`.\n');
		repo.commit();

		expect(repo.run('check-doc-refs.mjs').status).toBe(0);
	});

	it('accepts a directory when anything under it is tracked', () => {
		seed();
		repo.write('docs/GUIDE.md', 'Sources live in `frontend/src/lib`.\n');
		repo.commit();

		expect(repo.run('check-doc-refs.mjs').status).toBe(0);
	});
});
