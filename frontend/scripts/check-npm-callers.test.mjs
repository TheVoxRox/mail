import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { createGateRepo } from './test-support/gate-repo.mjs';

/*
 * The gate walks package.json -> the repository: an npm entry nothing reaches
 * is residue, or a tool nobody was told about. `check:refs` walks the other
 * way, and the pair is what makes "reachable from documentation" a legitimate
 * verdict rather than a hole — a documented `npm run` cannot rot into naming
 * something that does not exist.
 *
 * Every fixture declares the exempt entries. They are exempt because nothing
 * can reach them (an npm lifecycle hook, two interactive watch loops), and the
 * gate fails when an exemption names an entry package.json no longer has — so
 * a fixture that omitted them would fail for a reason the test is not about.
 */

let repo;

const EXEMPT_ENTRIES = {
	prepare: 'svelte-kit sync',
	'check:watch': 'svelte-check --watch',
	'test:unit:watch': 'vitest'
};

function seed(scripts) {
	repo.write(
		'frontend/package.json',
		JSON.stringify({ name: 'f', scripts: { ...EXEMPT_ENTRIES, ...scripts } }, null, '\t')
	);
}

beforeEach(() => {
	repo = createGateRepo();
});

afterEach(() => {
	repo.cleanup();
});

describe('check-npm-callers', () => {
	it('passes when every entry is reached, and says by which route', () => {
		seed({
			build: 'vite build',
			'check:all': 'npm run build',
			deploy: 'node scripts/deploy.mjs'
		});
		repo.write('.github/workflows/ci.yml', 'jobs:\n  a:\n    run: npm run check:all\n');
		repo.write('CONTRIBUTING.md', 'Ship it with `npm run deploy`.\n');
		repo.commit();

		const result = repo.run('check-npm-callers.mjs');

		expect(result.status).toBe(0);
		expect(result.stdout).toContain('npm script callers OK');
		expect(result.stdout).toContain('1 automation, 1 composite, 1 documentation');
		expect(result.stdout).toContain('3 exempt');
	});

	it('fails on an entry nothing in the repository names', () => {
		seed({ build: 'vite build', 'ghost:tool': 'node scripts/ghost.mjs' });
		repo.write('CONTRIBUTING.md', 'Build with `npm run build`.\n');
		repo.commit();

		const result = repo.run('check-npm-callers.mjs');

		expect(result.status).toBe(1);
		expect(result.stderr).toContain('ghost:tool');
		expect(result.stderr).toContain('no caller anywhere');
	});

	/*
	 * The measured ratio, not a guess: of the six entries this gate was built
	 * for, five were working tools that wanted a line of documentation and one
	 * was residue. A report that led with "delete it" would have been wrong five
	 * times out of six, so the advice has to lead the other way.
	 */
	it('tells the reader to document before deleting', () => {
		seed({ 'ghost:tool': 'node scripts/ghost.mjs' });
		repo.commit();

		const result = repo.run('check-npm-callers.mjs');

		expect(result.status).toBe(1);
		expect(result.stderr).toContain('Document it first');
	});

	/*
	 * A changelog names a script *because* it changed or was retired. Counting
	 * that as a caller would make every entry immortal from the commit that
	 * touched it — which is how the #422 script survived from the initial import.
	 */
	it('does not accept a changelog mention as a caller, and says where it looked', () => {
		seed({ 'ghost:tool': 'node scripts/ghost.mjs' });
		repo.write('CHANGELOG.md', 'Removed the last caller of `npm run ghost:tool`.\n');
		repo.commit();

		const result = repo.run('check-npm-callers.mjs');

		expect(result.status).toBe(1);
		expect(result.stderr).toContain('ghost:tool');
		expect(result.stderr).toContain('named only in CHANGELOG.md');
	});

	it('separates an entry only a test names from one nothing names', () => {
		seed({ preview: 'vite preview' });
		repo.write(
			'frontend/src/routes/a11y.e2e.ts',
			'// when a preview is already running (the `npm run preview` case)\n'
		);
		repo.commit();

		const result = repo.run('check-npm-callers.mjs');

		expect(result.status).toBe(1);
		expect(result.stderr).toContain('only a test names these');
		expect(result.stderr).toContain('preview');
		expect(result.stderr).not.toContain('no caller anywhere');
	});

	it('accepts documentation as a route in its own right', () => {
		seed({ 'bump:version': 'node scripts/bump-version.mjs' });
		repo.write('backend/RELEASE_CHECKLIST.md', 'Run `npm run bump:version` first.\n');
		repo.commit();

		const result = repo.run('check-npm-callers.mjs');

		expect(result.status).toBe(0);
		expect(result.stdout).toContain('1 documentation');
	});

	/*
	 * `.githooks/pre-push` wraps `npm run --silent "$@"` in a `run()` helper and
	 * then names eight gates as bare words. Without the hook rule every one of
	 * them reads as uncalled — a false finding on the file whose job is calling
	 * them.
	 */
	it('sees the pre-push hook calling a gate through its run() helper', () => {
		seed({ 'check:eol': 'node scripts/check-eol.mjs' });
		repo.write('.githooks/pre-push', 'run() {\n\tnpm run --silent "$@"\n}\n\nrun check:eol\n');
		repo.commit();

		const result = repo.run('check-npm-callers.mjs');

		expect(result.status).toBe(0);
	});

	/*
	 * The bare word is enough to credit a name package.json declares, and
	 * nowhere near enough to accuse one it does not: the helper takes anything
	 * the hook wants to run, and a shell function called `run` is not this
	 * repository's invention.
	 */
	it('credits the hook helper only for names package.json declares', () => {
		seed({ lint: 'eslint .' });
		repo.write('CONTRIBUTING.md', 'Run `npm run lint`.\n');
		repo.write(
			'.githooks/pre-push',
			'run() {\n\tnpm run --silent "$@"\n}\n\nrun npm ci\nrun lint\n'
		);
		repo.commit();

		const result = repo.run('check-npm-callers.mjs');

		expect(result.status).toBe(0);
		expect(result.output).not.toContain('named but not defined');
	});

	/*
	 * `in` would walk the prototype chain and read this entry as exempt, which
	 * drops it from the findings *and* from the route counts — the quietest
	 * possible way for a gate to stop covering something.
	 */
	it('reports an entry whose name collides with an Object.prototype member', () => {
		seed({ constructor: 'node scripts/ghost.mjs' });
		repo.commit();

		const result = repo.run('check-npm-callers.mjs');

		expect(result.status).toBe(1);
		expect(result.stderr).toContain('constructor');
	});

	it('quotes a dead reference the way the file spells it', () => {
		seed({ build: 'vite build' });
		repo.write('.github/workflows/ci.yml', 'run: npm start\n');
		repo.commit();

		const result = repo.run('check-npm-callers.mjs');

		expect(result.status).toBe(1);
		expect(result.stderr).toContain('npm start');
		expect(result.stderr).not.toContain('npm run start');
	});

	it('stays quiet about a changelog naming a script that is gone', () => {
		seed({ build: 'vite build' });
		repo.write('CONTRIBUTING.md', 'Build with `npm run build`.\n');
		repo.write('CHANGELOG.md', 'Deleted `npm run test:e2e:stable`, broken since the start.\n');
		repo.commit();

		const result = repo.run('check-npm-callers.mjs');

		expect(result.status).toBe(0);
	});

	it('sees the npm shorthands that skip the word run', () => {
		seed({ test: 'vitest run' });
		repo.write('CONTRIBUTING.md', 'Then `npm test`.\n');
		repo.commit();

		const result = repo.run('check-npm-callers.mjs');

		expect(result.status).toBe(0);
	});

	it('sees a call that carries npm flags on either side of run', () => {
		seed({ lint: 'eslint .', 'check:md': 'node scripts/check-md.mjs' });
		repo.write('.githooks/pre-commit', 'npm run --silent check:md\n');
		repo.write('.github/workflows/ci.yml', 'run: npm --prefix frontend run lint\n');
		repo.commit();

		const result = repo.run('check-npm-callers.mjs');

		expect(result.status).toBe(0);
	});

	it('fails on a workflow naming a script that does not exist', () => {
		seed({ build: 'vite build' });
		repo.write('.github/workflows/ci.yml', 'run: npm run buidl\n');
		repo.commit();

		const result = repo.run('check-npm-callers.mjs');

		expect(result.status).toBe(1);
		expect(result.stderr).toContain('named but not defined');
		expect(result.stderr).toContain('buidl');
	});

	/*
	 * Prose is `check:refs`, and a gate's own suite writes fixtures naming
	 * scripts that deliberately do not exist. Reporting either here would only
	 * mean two places to silence one finding.
	 */
	it('leaves a dead reference in prose or in a test fixture to their own gates', () => {
		seed({ build: 'vite build' });
		repo.write('CONTRIBUTING.md', 'Build with `npm run build`, then `npm run deploy`.\n');
		repo.write('frontend/scripts/other-gate.test.mjs', "write('Run `npm run deploy`.');\n");
		repo.commit();

		const result = repo.run('check-npm-callers.mjs');

		expect(result.status).toBe(0);
	});

	it('fails when an exemption names an entry package.json no longer has', () => {
		repo.write(
			'frontend/package.json',
			JSON.stringify({ name: 'f', scripts: { prepare: 'svelte-kit sync' } }, null, '\t')
		);
		repo.commit();

		const result = repo.run('check-npm-callers.mjs');

		expect(result.status).toBe(1);
		expect(result.stderr).toContain('exemptions that no longer hold');
		expect(result.stderr).toContain('check:watch');
	});

	it('fails when an exempt entry turns out to be reachable after all', () => {
		seed({});
		repo.write('CONTRIBUTING.md', 'Watch the types with `npm run check:watch`.\n');
		repo.commit();

		const result = repo.run('check-npm-callers.mjs');

		expect(result.status).toBe(1);
		expect(result.stderr).toContain('exemptions that no longer hold');
		expect(result.stderr).toContain('drop it from EXEMPT');
	});

	/*
	 * Resolution is over tracked files, like every other gate here: an untracked
	 * file exists on one machine, and a check whose answer depends on that is
	 * green locally and red for everyone else.
	 */
	it('does not accept an untracked file as a caller', () => {
		seed({ 'ghost:tool': 'node scripts/ghost.mjs' });
		repo.commit();
		repo.write('NOTES.md', 'Run `npm run ghost:tool`.\n');

		const result = repo.run('check-npm-callers.mjs');

		expect(result.status).toBe(1);
		expect(result.stderr).toContain('ghost:tool');
	});
});
