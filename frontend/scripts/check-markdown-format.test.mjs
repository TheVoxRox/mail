import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { createGateRepo } from './test-support/gate-repo.mjs';

/*
 * A thin wrapper around prettier, so the formatting itself is not what is
 * tested here — prettier's own suite does that. What is worth pinning is the
 * wrapper's two decisions: which files it considers (tracked ones, repo-wide,
 * because prettier's config lives under frontend/ and would otherwise never
 * see the root or docs/), and whether it rewrites or refuses.
 */

let repo;

beforeEach(() => {
	repo = createGateRepo();
	// Prettier resolves config upward from each file; give the fixture its own
	// so the result does not depend on the machine's global settings.
	repo.write('.prettierrc', JSON.stringify({ useTabs: true, printWidth: 100 }));
});

afterEach(() => {
	repo.cleanup();
});

describe('check-markdown-format', () => {
	it('passes on formatted Markdown', () => {
		repo.write('README.md', '# Title\n\nA paragraph.\n');
		repo.commit();

		const result = repo.run('check-markdown-format.mjs');

		expect(result.status).toBe(0);
		expect(result.stdout).toContain('Markdown OK');
	});

	it('fails on unformatted Markdown and names the file', () => {
		repo.write('README.md', '#    Title\n\n\n\nA   paragraph.\n');
		repo.commit();

		const result = repo.run('check-markdown-format.mjs');

		expect(result.status).not.toBe(0);
		expect(result.output).toContain('README.md');
	});

	/*
	 * The reason the list comes from `git ls-files` rather than a glob: build
	 * output and node_modules are full of Markdown nobody owns, and an
	 * untracked scratch note must not fail someone else's commit.
	 */
	it('ignores untracked Markdown', () => {
		repo.write('README.md', '# Title\n');
		repo.commit();
		repo.write('scratch.md', '#    badly    formatted\n\n\n');

		expect(repo.run('check-markdown-format.mjs').status).toBe(0);
	});

	it('reaches Markdown outside frontend/, where the prettier config lives', () => {
		repo.write('README.md', '# Title\n');
		repo.write('docs/GUIDE.md', '#    Guide\n\n\n\ntext\n');
		repo.commit();

		const result = repo.run('check-markdown-format.mjs');

		expect(result.status).not.toBe(0);
		expect(result.output.replaceAll('\\', '/')).toContain('docs/GUIDE.md');
	});

	it('rewrites in --write mode instead of failing', () => {
		repo.write('README.md', '#    Title\n\n\n\ntext\n');
		repo.commit();

		const result = repo.run('check-markdown-format.mjs', ['--write']);

		expect(result.status).toBe(0);
		expect(repo.run('check-markdown-format.mjs').status).toBe(0);
	});
});
