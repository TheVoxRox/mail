import { readFileSync } from 'node:fs';
import path from 'node:path';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { createGateRepo } from './test-support/gate-repo.mjs';
import { seedVersionFiles } from './test-support/version-files.mjs';

/*
 * The bump has one job: leave every file check-version-sync reads on the new
 * version and nothing else moved. Until 2026-11-01 it wrote five of them and
 * left both lockfiles on the old version, which no check reported; the first
 * real bump after the release restart (v0.2.0-beta.1, #817) set them by hand.
 */

let repo;

function read(relPath) {
	return readFileSync(path.join(repo.root, relPath), 'utf8');
}

beforeEach(() => {
	repo = createGateRepo();
});

afterEach(() => {
	repo.cleanup();
});

describe('bump-version', () => {
	it('writes all seven files and leaves check-version-sync green', () => {
		seedVersionFiles(repo);

		const bump = repo.run('bump-version.mjs', ['2.0.0-beta.1']);
		const check = repo.run('check-version-sync.mjs');

		expect(bump.status).toBe(0);
		expect(bump.stdout.match(/^\+ .* -> 2\.0\.0-beta\.1$/gm)).toHaveLength(7);
		expect(bump.stdout).toContain('+ frontend/package-lock.json -> 2.0.0-beta.1');
		expect(bump.stdout).toContain('+ frontend/src-tauri/Cargo.lock -> 2.0.0-beta.1');
		expect(check.status).toBe(0);
		expect(check.stdout).toContain('App version sync OK: 2.0.0-beta.1');
	});

	it('moves only the project version in each lockfile', () => {
		seedVersionFiles(repo);

		repo.run('bump-version.mjs', ['2.0.0']);

		const lock = JSON.parse(read('frontend/package-lock.json'));
		expect(lock.version).toBe('2.0.0');
		expect(lock.packages[''].version).toBe('2.0.0');
		expect(lock.packages['node_modules/dep'].version).toBe('1.0.0');
		const cargoLock = read('frontend/src-tauri/Cargo.lock');
		expect(cargoLock).toContain('name = "aaa-dep"\nversion = "1.2.3"');
		expect(cargoLock).toContain('name = "app"\nversion = "2.0.0"');
		expect(cargoLock).toContain('version = 4\n');
	});

	it('writes nothing when a lockfile has no entry to set', () => {
		seedVersionFiles(repo);
		repo.write('frontend/src-tauri/Cargo.lock', 'version = 4\n');

		const result = repo.run('bump-version.mjs', ['2.0.0']);

		expect(result.status).toBe(1);
		expect(result.stderr).toContain('Failed in frontend/src-tauri/Cargo.lock');
		expect(read('frontend/package.json')).toContain('"version": "1.2.3"');
	});
});
