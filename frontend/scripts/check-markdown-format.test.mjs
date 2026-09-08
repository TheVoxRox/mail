import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { createGateRepo, fixtureHasDependencies } from './test-support/gate-repo.mjs';

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

// The gate is prettier; without it in the fixture there is nothing to assert.
describe.skipIf(!fixtureHasDependencies())('check-markdown-format', () => {
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

	/*
	 * A file prettier reformats differently on every pass. `--write` cannot fix
	 * it — it rewrites and the next check fails again — so pointing the author
	 * at format:md is advice they cannot follow. Both modes must say so.
	 *
	 * The fixture is the real construct that hit this (prettier 3.9.6): a table
	 * indented to the content column of a task-list item. Should prettier fix
	 * it upstream, these two tests go green for the wrong reason and the
	 * fixture needs replacing, not deleting — the branch it covers is "output
	 * is not idempotent", not this one table.
	 */
	const unstableMarkdown = [
		'- [ ] Title.',
		'',
		'      | a | b |',
		'      | --- | --- |',
		'      | 1 | 2 |',
		''
	].join('\n');

	it('names a file prettier cannot format stably, instead of blaming the author', () => {
		repo.write('README.md', unstableMarkdown);
		repo.commit();

		const result = repo.run('check-markdown-format.mjs');

		expect(result.status).not.toBe(0);
		expect(result.output).toContain('README.md');
		expect(result.output).toContain('cannot be formatted stably');
		expect(result.output).not.toContain('Fix with');
	});

	it('refuses an unstable file in --write mode rather than rewriting it forever', () => {
		repo.write('README.md', unstableMarkdown);
		repo.commit();

		const result = repo.run('check-markdown-format.mjs', ['--write']);

		expect(result.status).not.toBe(0);
		expect(result.output).toContain('cannot be formatted stably');
	});

	/*
	 * Both lists come out of one loop, so reporting only the unstable one would
	 * cost a second run to learn about drift already measured.
	 */
	it('names ordinary drift alongside the unstable file, not on a second run', () => {
		repo.write('README.md', unstableMarkdown);
		repo.write('docs/GUIDE.md', '#    Guide\n\n\n\ntext\n');
		repo.commit();

		const result = repo.run('check-markdown-format.mjs');
		const output = result.output.replaceAll('\\', '/');

		expect(result.status).not.toBe(0);
		expect(output).toContain('cannot be formatted stably');
		expect(output).toContain('README.md');
		expect(output).toContain('docs/GUIDE.md');
	});

	it('says which files --write already rewrote before it hit the unstable one', () => {
		repo.write('AAA.md', '#    Rewritten\n\n\n\ntext\n');
		repo.write('README.md', unstableMarkdown);
		repo.commit();

		const result = repo.run('check-markdown-format.mjs', ['--write']);

		expect(result.status).not.toBe(0);
		expect(result.output).toContain('Already rewritten');
		expect(result.output).toContain('AAA.md');
	});
});
