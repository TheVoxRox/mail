import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { createGateRepo } from './test-support/gate-repo.mjs';
import { seedVersionFiles } from './test-support/version-files.mjs';

/*
 * Seven files across three build systems have to agree on one version. The
 * gate's value is not the comparison — it is that each version is extracted
 * from a format where a lookalike sits nearby: the Cargo manifest has
 * dependency versions, the pom has a parent version, version.ts is
 * TypeScript rather than data, and both lockfiles list every dependency with
 * a version of its own. An extractor that grabs the wrong one reports
 * agreement that is not there.
 */

let repo;

function seed(versions) {
	seedVersionFiles(repo, versions);
}

beforeEach(() => {
	repo = createGateRepo();
});

afterEach(() => {
	repo.cleanup();
});

describe('check-version-sync', () => {
	it('passes when all seven agree', () => {
		seed();

		const result = repo.run('check-version-sync.mjs');

		expect(result.status).toBe(0);
		expect(result.stdout).toContain('App version sync OK: 1.2.3');
	});

	it('fails and lists every file when one drifts', () => {
		seed({ cargo: '1.2.4' });

		const result = repo.run('check-version-sync.mjs');

		expect(result.status).toBe(1);
		expect(result.stderr).toContain('not in sync');
		expect(result.stderr).toContain('frontend/src-tauri/Cargo.toml: 1.2.4');
		expect(result.stderr).toContain('frontend/package.json: 1.2.3');
	});

	/*
	 * The two extractions that can plausibly grab a neighbour: Cargo's
	 * [dependencies] versions and the pom's <parent> version. If either were
	 * read instead, the gate would compare the wrong numbers and could pass on
	 * a genuinely desynchronised repo.
	 */
	it('reads the Cargo package version, not a dependency version', () => {
		seed({
			cargo: '7.7.7',
			pkg: '7.7.7',
			lock: '7.7.7',
			lockRoot: '7.7.7',
			tauri: '7.7.7',
			clientTs: '7.7.7',
			cargoLock: '7.7.7',
			pom: '7.7.7'
		});

		const result = repo.run('check-version-sync.mjs');

		expect(result.status).toBe(0);
		expect(result.stdout).toContain('7.7.7');
	});

	it('reads the pom project version, not the parent version', () => {
		seed({ pom: '9.9.9' });

		const result = repo.run('check-version-sync.mjs');

		// Would have passed had it read the parent's 4.0.0 or matched by accident.
		expect(result.status).toBe(1);
		expect(result.stderr).toContain('backend/pom.xml: 9.9.9');
	});

	it('fails when either root version of package-lock.json drifts', () => {
		seed({ lock: '1.2.2' });
		const top = repo.run('check-version-sync.mjs');

		seed({ lockRoot: '1.2.2' });
		const root = repo.run('check-version-sync.mjs');

		expect(top.status).toBe(1);
		expect(top.stderr).toContain('frontend/package-lock.json: 1.2.2');
		expect(root.status).toBe(1);
		expect(root.stderr).toContain('frontend/package-lock.json packages[""]: 1.2.2');
	});

	it("reads the project's Cargo.lock entry, not the first one", () => {
		seed({ cargoLock: '1.2.2' });

		const result = repo.run('check-version-sync.mjs');

		// The dependency ahead of it says 1.2.3, as every other file does.
		expect(result.status).toBe(1);
		expect(result.stderr).toContain('frontend/src-tauri/Cargo.lock: 1.2.2');
	});

	it('fails loudly when Cargo.lock has no entry for the package', () => {
		seed();
		repo.write('frontend/src-tauri/Cargo.lock', 'version = 4\n');

		const result = repo.run('check-version-sync.mjs');

		expect(result.status).not.toBe(0);
		expect(result.output).toContain('no [[package]] entry for "app"');
	});

	it('fails loudly when the Cargo manifest has no package version to read', () => {
		seed();
		repo.write('frontend/src-tauri/Cargo.toml', '[dependencies]\nserde = "1"\n');

		const result = repo.run('check-version-sync.mjs');

		expect(result.status).not.toBe(0);
		expect(result.output).toContain('[package] version');
	});
});
