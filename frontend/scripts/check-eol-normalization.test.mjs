import { randomUUID } from 'node:crypto';
import { rmSync, writeFileSync } from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { createGateRepo } from './test-support/gate-repo.mjs';

/*
 * The gate exists because git normalizes a text blob to LF on the way in, so
 * nothing a contributor can do with `git add` produces the defect — and
 * nothing a contributor can do with `git checkout` or `git reset` clears it.
 * These tests are therefore about the one decision it makes (does the index
 * blob match what .gitattributes declares) and about the two things that
 * decision is easy to get wrong: judging the working tree instead of the
 * index, and trusting `git status`.
 */

const CRLF = (...lines) => Buffer.from(lines.map((l) => `${l}\r\n`).join(''));
const LF = (...lines) => lines.map((l) => `${l}\n`).join('');

let repo;

beforeEach(() => {
	repo = createGateRepo();
	/*
	 * An LF `.cmd` under `eol=crlf` is the correct state, and git narrates it
	 * on every add ("LF will be replaced by CRLF"). Same reasoning as the
	 * `core.autocrlf` line in the fixture itself: the warning is expected here,
	 * and left on it buries the output of whichever test actually failed.
	 */
	repo.git(['config', 'core.safecrlf', 'false']);
	repo.write('.gitattributes', '* text=auto eol=lf\n\n*.cmd text eol=crlf\n\n*.png binary\n');
});

afterEach(() => {
	repo.cleanup();
});

/**
 * Commits `bytes` as the blob for `relPath` with git's clean filter bypassed,
 * then materializes the working copy the way a clone would.
 *
 * This is the only way to build the defect. `git add` normalizes, so a fixture
 * written through it can never carry a CRLF blob however the bytes are
 * spelled — the whole reason the bug arrives through the GitHub API, which
 * writes blobs directly and does not apply .gitattributes.
 *
 * The carrier file lives outside the repo: several gates enumerate tracked
 * files, and a fixture that tracks its own scaffolding tests the wrong tree.
 */
function commitUnfilteredBlob(relPath, bytes) {
	const carrier = path.join(os.tmpdir(), `voxrox-eol-blob-${randomUUID()}`);
	writeFileSync(carrier, bytes);
	try {
		const sha = repo.git(['hash-object', '-w', '--no-filters', carrier]);
		repo.git(['update-index', '--add', '--cacheinfo', `100644,${sha},${relPath}`]);
		repo.git(['commit', '--quiet', '--no-verify', '-m', `raw ${relPath}`]);
	} finally {
		rmSync(carrier, { force: true });
	}
	repo.git(['checkout', '--', relPath]);
}

describe('check-eol-normalization', () => {
	/*
	 * `mvnw.cmd` is here on purpose. Its blob is LF while .gitattributes says
	 * `eol=crlf`, which reads like the defect and is the correct state: `eol=`
	 * describes the checkout, never the blob. A gate that compared the two
	 * naively would fail this repo on day one.
	 */
	it('passes a tree whose text blobs are LF, including one declared eol=crlf', () => {
		repo.write('src/app.ts', LF("export const x = 'hello';"));
		repo.write('mvnw.cmd', LF('@echo off', 'echo build'));
		repo.commit();

		const result = repo.run('check-eol-normalization.mjs');

		expect(result.status).toBe(0);
		expect(result.stdout).toContain('EOL check OK');
	});

	it('fails on a CRLF blob under `text eol=crlf`, naming the file and the fix', () => {
		repo.write('keep.ts', LF('export const ok = true;'));
		repo.commit();
		commitUnfilteredBlob('mvnw.cmd', CRLF('@echo off', 'echo build'));

		const result = repo.run('check-eol-normalization.mjs');

		expect(result.status).toBe(1);
		expect(result.stderr).toContain('mvnw.cmd');
		expect(result.stderr).toContain('index holds crlf');
		// The remedy is the non-obvious half: the reflexes people reach for
		// first (checkout, reset) leave the file dirty, as the test below shows.
		expect(result.stderr).toContain('git add --renormalize mvnw.cmd');
	});

	/*
	 * The printed command is the half of the message that is not obvious, so
	 * it has to survive a paste. Unquoted, a path with a space arrives as two
	 * pathspecs and git answers "did not match any files" — an error about the
	 * remedy, at the moment someone is already confused about the cause.
	 */
	it('quotes a path with a space so the printed fix stays pasteable', () => {
		repo.write('keep.ts', LF('export const ok = true;'));
		repo.commit();
		commitUnfilteredBlob('docs/release notes.md', CRLF('# Notes'));

		const result = repo.run('check-eol-normalization.mjs');

		expect(result.status).toBe(1);
		expect(result.stderr).toContain("git add --renormalize 'docs/release notes.md'");
	});

	/*
	 * The reason this gate is not a `git status` wrapper.
	 *
	 * A blob with mixed endings is a blob `git add --renormalize` demonstrably
	 * rewrites — it is wrong by git's own reckoning — and `git status` reports
	 * the tree clean anyway, freshly checked out and after the stat cache has
	 * been invalidated. Detecting drift by status would have shipped a gate
	 * that passes this file.
	 */
	it('catches a mixed-ending blob that `git status` calls clean', () => {
		repo.write('keep.ts', LF('export const ok = true;'));
		repo.commit();
		commitUnfilteredBlob('docs/notes.md', Buffer.from('first\r\nsecond\nthird\r\n'));

		expect(repo.git(['status', '--porcelain'])).toBe('');
		// Touch the file so the stat cache cannot be what is hiding it.
		repo.git(['update-index', '--really-refresh']);
		expect(repo.git(['status', '--porcelain'])).toBe('');

		const result = repo.run('check-eol-normalization.mjs');

		expect(result.status).toBe(1);
		expect(result.stderr).toContain('docs/notes.md');
		expect(result.stderr).toContain('index holds mixed');
	});

	/*
	 * The symptom that makes this worth a gate rather than a note: the file is
	 * modified in every clone and the usual ways of discarding local changes
	 * do not touch it, because the smudge filter rebuilds the working copy
	 * from the same bad blob every time.
	 */
	it('flags a file that `checkout --` and `reset --hard` cannot clean, until renormalized', () => {
		repo.write('keep.ts', LF('export const ok = true;'));
		repo.commit();
		commitUnfilteredBlob('mvnw.cmd', CRLF('@echo off', 'echo build'));

		repo.git(['checkout', '--force', '--', 'mvnw.cmd']);
		expect(repo.git(['status', '--porcelain'])).toContain('mvnw.cmd');
		repo.git(['reset', '--hard', '--quiet']);
		expect(repo.git(['status', '--porcelain'])).toContain('mvnw.cmd');

		repo.git(['add', '--renormalize', 'mvnw.cmd']);
		repo.git(['commit', '--quiet', '--no-verify', '-m', 'renormalize']);

		expect(repo.git(['status', '--porcelain'])).toBe('');
		expect(repo.run('check-eol-normalization.mjs').status).toBe(0);
	});

	/*
	 * Index only, deliberately. A working copy with CRLF under `eol=lf` is
	 * clean as far as git is concerned — the clean filter normalizes it on the
	 * way in — and the real repo carries one (`frontend/src-tauri/.gitignore`,
	 * written by the tauri CLI). Judging `w/` would fail a repo that has
	 * nothing wrong with it.
	 */
	it('ignores a CRLF working copy when the blob itself is LF', () => {
		repo.write('src/app.ts', LF('export const x = 1;'));
		repo.commit();
		repo.writeBytes('src/app.ts', CRLF('export const x = 1;'));

		const result = repo.run('check-eol-normalization.mjs');

		expect(result.status).toBe(0);
		expect(result.stdout).toContain('EOL check OK');
	});

	it('skips a file the repo has declared binary', () => {
		repo.write('keep.ts', LF('export const ok = true;'));
		repo.commit();
		commitUnfilteredBlob('assets/icon.png', CRLF('PNG', 'data'));

		const result = repo.run('check-eol-normalization.mjs');

		expect(result.status).toBe(0);
		// The count, not just the exit code: a gate that enumerated nothing at
		// all would pass every one of the skip cases below on a technicality.
		expect(result.stdout).toContain('1 binary or undeclared and skipped');
	});

	/*
	 * Where nothing is declared, git promises nothing, and a CRLF blob is a
	 * legitimate choice rather than drift. This repo's root `* text=auto`
	 * means the branch never fires here — it exists so a future subdirectory
	 * .gitattributes cannot turn the gate into a liar.
	 */
	it('skips a path no .gitattributes rule declares as text', () => {
		repo.write('.gitattributes', '*.ts text=auto eol=lf\n');
		repo.write('keep.ts', LF('export const ok = true;'));
		repo.commit();
		commitUnfilteredBlob('vendor/legacy.f90', CRLF('      PROGRAM OLD', '      END'));

		const result = repo.run('check-eol-normalization.mjs');

		expect(result.status).toBe(0);
		// `.gitattributes` itself falls outside the `*.ts` rule here, so two
		// paths are undeclared and only `keep.ts` carries an expectation.
		expect(result.stdout).toContain('1 declared-text file(s)');
		expect(result.stdout).toContain('2 binary or undeclared and skipped');
	});

	/*
	 * A blob with no line ending at all contradicts no declaration. The real
	 * repo has one (a single-line SVG), so getting this wrong would fail the
	 * gate's first run against the tree it was written for.
	 */
	it('skips a blob that carries no line ending at all', () => {
		repo.write('keep.ts', LF('export const ok = true;'));
		repo.writeBytes('src/oneline.svg', Buffer.from('<svg viewBox="0 0 1 1"></svg>'));
		repo.commit();

		expect(repo.run('check-eol-normalization.mjs').status).toBe(0);
	});

	it('ignores untracked files — the gate judges what git would carry', () => {
		repo.write('src/app.ts', LF('export const x = 1;'));
		repo.commit();
		repo.writeBytes('scratch.cmd', CRLF('echo scratch'));

		expect(repo.run('check-eol-normalization.mjs').status).toBe(0);
	});
});
