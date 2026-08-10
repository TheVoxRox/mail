import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { createGateRepo } from './test-support/gate-repo.mjs';

/*
 * The rule this enforces is "code and comments are English"; the mechanism is
 * "no Czech diacritics outside a whitelist". Those are not the same thing, and
 * the gap is a documented blind spot — diacritic-free Czech sails through. The
 * tests below pin what the gate does claim, so the claim stays honest: the
 * whitelist is respected, an un-whitelisted file fails in strict mode, and
 * report mode never fails a build.
 */

let repo;

beforeEach(() => {
	repo = createGateRepo();
	repo.write('frontend/docs/translation-whitelist.txt', '');
	repo.write('backend/docs/translation-whitelist.txt', '');
});

afterEach(() => {
	repo.cleanup();
});

describe('check-translation-whitelist', () => {
	it('passes on an English-only frontend tree', () => {
		repo.write('frontend/src/lib/app.ts', "export const label = 'Send';\n");

		const result = repo.run('check-translation-whitelist.mjs', ['--mode=strict']);

		expect(result.status).toBe(0);
		expect(result.stdout).toContain('OK (strict)');
	});

	it('fails in strict mode on un-whitelisted diacritics, naming the file', () => {
		repo.write('frontend/src/lib/app.ts', '// Odesílá zprávu\nexport const x = 1;\n');

		const result = repo.run('check-translation-whitelist.mjs', ['--mode=strict']);

		expect(result.status).toBe(1);
		expect(result.output).toContain('src/lib/app.ts');
	});

	it('accepts a file listed in the whitelist', () => {
		repo.write('frontend/src/lib/app.ts', '// Odesílá zprávu\nexport const x = 1;\n');
		repo.write('frontend/docs/translation-whitelist.txt', 'frontend/src/lib/app.ts\n');

		expect(repo.run('check-translation-whitelist.mjs', ['--mode=strict']).status).toBe(0);
	});

	/*
	 * Report mode is what the maintainer runs while migrating; it must never be
	 * the thing that fails a build, or the two modes collapse into one.
	 */
	it('reports without failing in report mode', () => {
		repo.write('frontend/src/lib/app.ts', '// Odesílá zprávu\nexport const x = 1;\n');

		const result = repo.run('check-translation-whitelist.mjs', ['--mode=report']);

		expect(result.status).toBe(0);
		expect(result.output).toContain('src/lib/app.ts');
	});

	it('scans the backend Java tree under --target=backend', () => {
		repo.write('backend/src/main/java/App.java', '// Odesílá zprávu\nclass App {}\n');

		const result = repo.run('check-translation-whitelist.mjs', [
			'--target=backend',
			'--mode=strict'
		]);

		expect(result.status).toBe(1);
		expect(result.output).toContain('App.java');
	});

	it('keeps the two targets apart', () => {
		// Czech in the backend must not fail a frontend run, or the whitelists
		// would have to be merged and each target would lose its own scope.
		repo.write('backend/src/main/java/App.java', '// Odesílá zprávu\nclass App {}\n');
		repo.write('frontend/src/lib/app.ts', "export const label = 'Send';\n");

		expect(repo.run('check-translation-whitelist.mjs', ['--mode=strict']).status).toBe(0);
	});

	it('only looks at the extensions its target declares', () => {
		repo.write('frontend/src/lib/notes.txt', 'Odesílá zprávu\n');

		expect(repo.run('check-translation-whitelist.mjs', ['--mode=strict']).status).toBe(0);
	});

	it('rejects an unknown argument rather than scanning something unintended', () => {
		repo.write('frontend/src/lib/app.ts', 'export const x = 1;\n');

		const result = repo.run('check-translation-whitelist.mjs', ['--mode=lenient']);

		expect(result.status).toBe(2);
		expect(result.output).toContain('Unknown argument');
	});
});
