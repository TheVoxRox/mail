import { execFileSync } from 'node:child_process';
import { mkdtempSync, rmSync } from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { createGateRepo } from './test-support/gate-repo.mjs';

/*
 * The git half of release:status, run as a process the way `npm run` runs it.
 * The decisions are covered in release-status.test.mjs; what is covered here
 * is what feeds them and cannot be reached from a plain value: reading the
 * checklist and the version from origin's main instead of the working tree,
 * resolving an annotated tag on origin to its commit, and noticing that main
 * is not in the clone.
 *
 * The fixture's origin is a bare repository on disk, so it is not a GitHub
 * remote and the script never reaches gh. Each run therefore ends at "GitHub
 * could not be read" when it gets that far — after everything under test here,
 * and without depending on the network or on anyone's gh login.
 */

const CONFIG = 'frontend/src-tauri/tauri.conf.json';
const CHECKLIST = 'backend/RELEASE_CHECKLIST.md';
const SHORT = /[0-9a-f]{7}/.source;

function sheet(date, backendSrcId) {
	return [
		'# Release checklist',
		'',
		'## Appendix',
		'',
		`### Candidate ${date}`,
		'',
		'| path | object id |',
		'| ---- | --------- |',
		`| \`backend/src\` | \`${backendSrcId.slice(0, 12)}\` |`,
		'',
		'- [x] **§0 Version** — ok.',
		'- [x] **§1 Backend build** — ok.',
		'- [x] **§2 Frontend automation** — ok.',
		'- [ ] **§3 Fresh install** — open.',
		''
	].join('\n');
}

const config = (version) => JSON.stringify({ productName: 'VoxRox Mail', version });

describe('release:status as a process', () => {
	let repo;
	let scratch;
	let origin;
	const git = (cwd, args) => execFileSync('git', args, { cwd, encoding: 'utf8' }).trim();

	beforeEach(() => {
		repo = createGateRepo();
		scratch = mkdtempSync(path.join(os.tmpdir(), 'voxrox-release-status-'));
		origin = path.join(scratch, 'origin.git');
		git(scratch, ['init', '--quiet', '--bare', '--initial-branch=main', origin]);
		repo.git(['remote', 'add', 'origin', origin]);
	});

	afterEach(() => {
		repo.cleanup();
		rmSync(scratch, { recursive: true, force: true });
	});

	/** Commits code at `version`, then a sheet naming that code's backend/src, and pushes main. */
	function candidateOnMain(version) {
		repo.write(CONFIG, config(version));
		repo.write('backend/src/App.java', 'class App {}\n');
		repo.commit('code');
		repo.write(CHECKLIST, sheet('2026-09-11', repo.git(['rev-parse', 'HEAD:backend/src'])));
		repo.commit('sheet');
		repo.git(['push', '--quiet', '--no-verify', 'origin', 'main']);
	}

	it('reads the sheet and the version from main, and notes where the working tree differs', () => {
		candidateOnMain('0.1.0');
		repo.write(CHECKLIST, sheet('2026-09-12', 'f'.repeat(40)));
		repo.write(CONFIG, config('0.2.0'));

		const result = repo.run('release-status.mjs');

		expect(result.status).toBe(0);
		expect(result.stdout).toContain('Release status: VoxRox Mail 0.1.0, tag v0.1.0');
		expect(result.stdout).toContain('Candidate sheet 2026-09-11');
		expect(result.stdout).toMatch(new RegExp(`Object ids against main ${SHORT}: match`));
		expect(result.stdout).toContain(
			"Your working tree's backend/RELEASE_CHECKLIST.md differs from main's"
		);
		expect(result.stdout).toContain('Your working tree says version 0.2.0; main says 0.1.0');
		expect(result.stdout).toContain('origin is not a GitHub remote');
	});

	it('resolves an annotated tag on origin to its commit and compares the sheet against both', () => {
		repo.write(CONFIG, config('0.1.0'));
		repo.write('backend/src/App.java', 'class App { int tagged; }\n');
		repo.commit('tagged');
		repo.git(['tag', '-a', 'v0.1.0', '-m', 'VoxRox Mail v0.1.0']);
		repo.write('backend/src/App.java', 'class App { int moved; }\n');
		repo.commit('moved');
		repo.write(CHECKLIST, sheet('2026-09-11', repo.git(['rev-parse', 'HEAD:backend/src'])));
		repo.commit('sheet');
		repo.git(['push', '--quiet', '--no-verify', 'origin', 'main', 'v0.1.0']);
		const taggedCommit = repo.git(['rev-parse', 'v0.1.0^{commit}']).slice(0, 7);

		const result = repo.run('release-status.mjs');

		expect(result.status).toBe(0);
		expect(result.stdout).toContain(`On origin: yes, at ${taggedCommit}`);
		expect(result.stdout).toContain('On main: yes');
		expect(result.stdout).toMatch(new RegExp(`Object ids against main ${SHORT}: match`));
		expect(result.stdout).toContain(
			`Object ids against v0.1.0 ${taggedCommit}: differ in backend/src`
		);
	});

	it('asks for a fetch, not a new §1, when main on origin is ahead of the clone', () => {
		candidateOnMain('0.1.0');
		const elsewhere = path.join(scratch, 'elsewhere');
		git(scratch, ['clone', '--quiet', origin, elsewhere]);
		git(elsewhere, [
			'-c',
			'user.email=gate-test@example.com',
			'-c',
			'user.name=Gate Test',
			'-c',
			'commit.gpgsign=false',
			'commit',
			'--quiet',
			'--allow-empty',
			'--no-verify',
			'-m',
			'merged elsewhere'
		]);
		git(elsewhere, ['push', '--quiet', '--no-verify', 'origin', 'main']);

		const result = repo.run('release-status.mjs');

		expect(result.status).toBe(0);
		expect(result.stdout).toContain('is not in this clone');
		expect(result.stdout).toContain('git fetch origin');
		expect(result.stdout).not.toContain('Re-take §1');
	});
});
