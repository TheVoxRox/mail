import { execFileSync } from 'node:child_process';
import { chmodSync, mkdtempSync, readFileSync, rmSync, symlinkSync, writeFileSync } from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import process from 'node:process';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { createGateRepo } from './test-support/gate-repo.mjs';

/**
 * Whether this machine lets an unprivileged process create a symlink. Windows
 * needs Developer Mode or elevation, so the symlink case below is skipped
 * there rather than asserted on a setup the OS refused.
 */
const canSymlink = (() => {
	const probe = mkdtempSync(path.join(os.tmpdir(), 'voxrox-symlink-probe-'));
	try {
		symlinkSync(probe, path.join(probe, 'link'), 'dir');
		return true;
	} catch {
		return false;
	} finally {
		rmSync(probe, { recursive: true, force: true });
	}
})();

/**
 * Takes away this account's read access to `abs` and returns the undo, or
 * `null` if the denial did not actually take — root and an elevated Windows
 * account both read straight through one, and asserting on a restriction the
 * OS ignored would test nothing.
 */
function denyRead(abs) {
	const account = os.userInfo().username;
	const windows = process.platform === 'win32';
	const restore = () =>
		windows
			? execFileSync('icacls', [abs, '/remove:d', account], { stdio: 'ignore' })
			: chmodSync(abs, 0o644);

	try {
		if (windows) {
			execFileSync('icacls', [abs, '/deny', `${account}:(R)`], { stdio: 'ignore' });
		} else {
			chmodSync(abs, 0o000);
		}
	} catch {
		return null;
	}

	try {
		readFileSync(abs);
	} catch {
		return restore;
	}
	restore();
	return null;
}

/** Whether `denyRead` has any effect on this machine and account. */
const canDenyRead = (() => {
	const dir = mkdtempSync(path.join(os.tmpdir(), 'voxrox-deny-probe-'));
	try {
		const file = path.join(dir, 'probe.txt');
		writeFileSync(file, 'probe\n');
		const restore = denyRead(file);
		if (!restore) return false;
		restore();
		return true;
	} catch {
		return false;
	} finally {
		rmSync(dir, { recursive: true, force: true });
	}
})();

/*
 * The gate exists because a NUL byte passes every formatter, linter and test
 * — it is a legal string character — while turning the file binary for git.
 * These tests are therefore about the two decisions it makes: is the byte
 * there, and has the repo declared this file binary on purpose.
 */

const NUL = Buffer.from([0]);
const withNul = (before, after) => Buffer.concat([Buffer.from(before), NUL, Buffer.from(after)]);

let repo;

beforeEach(() => {
	repo = createGateRepo();
	repo.write('.gitattributes', '* text=auto eol=lf\n\n*.png binary\n');
});

afterEach(() => {
	repo.cleanup();
});

describe('check-no-nul-bytes', () => {
	it('passes over a tree of ordinary text files', () => {
		repo.write('src/app.ts', "export const x = 'hello';\n");
		repo.write('README.md', '# Title\n');
		repo.commit();

		const result = repo.run('check-no-nul-bytes.mjs');

		expect(result.status).toBe(0);
		expect(result.stdout).toContain('NUL check OK');
	});

	it('fails on a NUL in a source file, naming the file and the offset', () => {
		const prefix = "export const sep = 'a";
		repo.writeBytes('src/app.ts', withNul(prefix, "b';\n"));
		repo.commit();

		const result = repo.run('check-no-nul-bytes.mjs');

		expect(result.status).toBe(1);
		expect(result.stderr).toContain('src/app.ts');
		// The offset is what makes the message actionable in a file with no
		// visible defect, so it is asserted rather than taken on trust.
		expect(result.stderr).toContain(`byte ${prefix.length}`);
	});

	/*
	 * The whole point of deferring to .gitattributes rather than an extension
	 * list in the script: real binaries are declared once, in the file git
	 * itself reads, instead of being guessed at from a list that goes stale.
	 */
	it('skips files the repo has declared binary', () => {
		repo.writeBytes('assets/icon.png', withNul('PNG', 'data'));
		repo.commit();

		const result = repo.run('check-no-nul-bytes.mjs');

		expect(result.status).toBe(0);
		expect(result.stdout).toContain('1 declared binary and skipped');
	});

	it('fails on an undeclared binary, so new asset types have to be declared', () => {
		repo.writeBytes('assets/photo.jpg', withNul('JPEG', 'data'));
		repo.commit();

		const result = repo.run('check-no-nul-bytes.mjs');

		expect(result.status).toBe(1);
		expect(result.stderr).toContain('assets/photo.jpg');
		expect(result.stderr).toContain('.gitattributes');
	});

	/*
	 * Not everything git tracks is a regular file. A tracked symlink pointing
	 * at a directory made the gate die with EISDIR partway through the tree —
	 * so it reported nothing about the files it had not reached yet, which is
	 * the worst way for a gate to fail: loudly, and with a false all-clear for
	 * everything after the crash. Found on the first CI run of these tests,
	 * because the platform difference only shows on Linux.
	 */
	it.skipIf(!canSymlink)('walks past a tracked symlink instead of dying on it', () => {
		repo.write('src/target/file.ts', 'export const x = 1;\n');
		symlinkSync(path.join(repo.root, 'src/target'), path.join(repo.root, 'src/link'), 'dir');
		// Ordered after the symlink so a crash there would hide this one.
		repo.writeBytes('src/zz-offender.ts', withNul('a', 'b'));
		repo.commit();

		const result = repo.run('check-no-nul-bytes.mjs');

		expect(result.status).toBe(1);
		expect(result.stderr).toContain('src/zz-offender.ts');
		expect(result.stderr).not.toContain('EISDIR');
	});

	/*
	 * Skipping what cannot be opened is right for a symlink and wrong for a
	 * file that is merely locked or unreadable: one has no bytes of its own,
	 * the other has bytes nobody looked at. Treating them alike turned a
	 * Windows handle held by an editor or a scanner into a printed "NUL check
	 * OK" — the gate reporting a verdict on files it never read.
	 */
	it.skipIf(!canDenyRead)('refuses to call a file clean when it could not be read', () => {
		repo.write('src/app.ts', 'export const x = 1;\n');
		const locked = repo.write('src/locked.ts', 'export const y = 2;\n');
		repo.commit();

		const restore = denyRead(locked);
		try {
			const result = repo.run('check-no-nul-bytes.mjs');

			expect(result.status).toBe(1);
			expect(result.stderr).toContain('src/locked.ts');
			expect(result.stdout).not.toContain('NUL check OK');
		} finally {
			restore();
		}
	});

	it('ignores untracked files — the gate judges what git would carry', () => {
		repo.write('src/app.ts', 'export const x = 1;\n');
		repo.commit();
		repo.writeBytes('scratch.ts', withNul('a', 'b'));

		expect(repo.run('check-no-nul-bytes.mjs').status).toBe(0);
	});

	describe('--staged', () => {
		it('looks only at what is staged, not at the rest of the tree', () => {
			repo.writeBytes('src/legacy.ts', withNul('old', 'file'));
			repo.commit();
			repo.write('src/new.ts', 'export const ok = true;\n');
			repo.git(['add', 'src/new.ts']);

			const result = repo.run('check-no-nul-bytes.mjs', ['--staged']);

			expect(result.status).toBe(0);
			expect(result.stdout).toContain('1 staged file(s)');
		});

		it('fails on a staged NUL before it can reach a commit', () => {
			repo.write('keep.ts', 'export const ok = true;\n');
			repo.commit();
			repo.writeBytes('src/new.ts', withNul('a', 'b'));
			repo.git(['add', 'src/new.ts']);

			const result = repo.run('check-no-nul-bytes.mjs', ['--staged']);

			expect(result.status).toBe(1);
			expect(result.stderr).toContain('src/new.ts');
		});

		/*
		 * The pre-commit hook runs this on every commit, including ones that only
		 * delete. Reading a path that is no longer on disk must not throw.
		 */
		it('survives a staged deletion', () => {
			repo.write('gone.ts', 'export const x = 1;\n');
			repo.commit();
			repo.remove('gone.ts');
			repo.git(['add', '-A']);

			expect(repo.run('check-no-nul-bytes.mjs', ['--staged']).status).toBe(0);
		});
	});
});
