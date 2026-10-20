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
		expect(result.stdout).toContain(
			'backend/RELEASE_CHECKLIST.md on main has no "| section | rests on |" table, so no section can carry.'
		);
	});

	it('carries a section whose inputs did not move since the last sheet that ticked it', () => {
		const checklist = (date, ticked) =>
			[
				'# Release checklist',
				'',
				'## Appendix',
				'',
				'| section | rests on |',
				'| ------- | -------- |',
				'| §3a | `frontend/src-tauri/windows`, `frontend/src-tauri/tauri.conf.json#bundle` |',
				'| §4 | `docs/OAUTH_AUDIT.md` |',
				'| §5 | `frontend/src/lib/mail` |',
				'',
				`### Candidate ${date}`,
				'',
				'| path | object id |',
				'| ---- | --------- |',
				...['backend/src', 'frontend/src', 'frontend/src-tauri'].map(
					(row) => `| \`${row}\` | \`${repo.git(['rev-parse', `HEAD:${row}`]).slice(0, 12)}\` |`
				),
				'',
				...['§0', '§1', '§2'].map((id) => `- [x] **${id} Machine** — ok.`),
				...['§3a', '§4', '§5'].map((id) => `- [${ticked ? 'x' : ' '}] **${id} Manual** — by hand.`),
				'- [ ] **§9** — decision.',
				''
			].join('\n');
		const tauri = (version) =>
			JSON.stringify({ productName: 'VoxRox Mail', version, bundle: { targets: 'nsis' } });

		repo.write(CONFIG, tauri('0.1.0'));
		repo.write('frontend/src-tauri/windows/installer.nsi', '; installer\n');
		repo.write('frontend/src/lib/mail/list.ts', 'export const list = 1;\n');
		repo.write('backend/src/auth/Login.java', 'class Login {}\n');
		repo.write('docs/OAUTH_AUDIT.md', '| **Code paths** | `backend/src/auth` |\n');
		repo.commit('code');
		repo.write(CHECKLIST, checklist('2026-09-11', true));
		repo.commit('the first sheet, every section ticked');
		const ticked = repo.git(['rev-parse', 'HEAD']).slice(0, 7);
		// The next cut: a version bump and a change to the mail list, nothing else.
		repo.write(CONFIG, tauri('0.2.0'));
		repo.write('frontend/src/lib/mail/list.ts', 'export const list = 2;\n');
		repo.commit('the next cut');
		repo.write(CHECKLIST, checklist('2026-09-12', false));
		repo.commit('the second sheet, replacing the first');
		repo.git(['push', '--quiet', '--no-verify', 'origin', 'main']);

		const result = repo.run('release-status.mjs');
		const against = `the 2026-09-11 sheet as of ${ticked}`;

		expect(result.status).toBe(0);
		expect(result.stdout).toContain('Manual sections, against the last sheet that ticked each');
		expect(result.stdout).toContain(`§3a Manual: carry from ${against}`);
		expect(result.stdout).toContain(`§4 Manual: carry from ${against}`);
		expect(result.stdout).toContain(
			`§5 Manual: re-take: frontend/src/lib/mail. Against ${against}`
		);
		expect(result.stdout).toContain('§9: re-take, as on every candidate');
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
