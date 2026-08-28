import { execFileSync } from 'node:child_process';
import { mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

/*
 * latest.json is what every installed copy of the app asks for when it checks
 * for an update, so a manifest that names one version and points at another
 * build is a shipped-and-unfixable mistake. The artifact it points at used to
 * be chosen by a score in which carrying the right version was worth ten
 * points — enough to win on a clean runner, and nothing at all next to a
 * previous release still sitting in bundle/.
 *
 * These run the real script against a throwaway bundle directory, the same way
 * the gate suites run against a throwaway git repo.
 */
const scriptPath = path.resolve(
	path.dirname(fileURLToPath(import.meta.url)),
	'generate-tauri-latest-windows.mjs'
);

let workDir;

beforeEach(() => {
	workDir = mkdtempSync(path.join(os.tmpdir(), 'latest-json-'));
	mkdirSync(path.join(workDir, 'bundle', 'nsis'), { recursive: true });
});

afterEach(() => {
	rmSync(workDir, { recursive: true, force: true });
});

/** Writes an installer and its sibling signature into the throwaway bundle. */
function addSignedArtifact(name, signature = 'c2lnbmF0dXJl') {
	const file = path.join(workDir, 'bundle', 'nsis', name);
	writeFileSync(file, 'installer bytes');
	writeFileSync(`${file}.sig`, `${signature}\n`);
}

function run(version, extraArgs = []) {
	return execFileSync(
		process.execPath,
		[
			scriptPath,
			'--bundle-dir',
			path.join(workDir, 'bundle'),
			'--output',
			path.join(workDir, 'latest.json'),
			'--repository',
			'TheVoxRox/mail',
			'--tag',
			`v${version}`,
			'--version',
			version,
			...extraArgs
		],
		{ encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] }
	);
}

function manifest() {
	return JSON.parse(readFileSync(path.join(workDir, 'latest.json'), 'utf8'));
}

describe('artifact selection', () => {
	it('points at the installer carrying the version being released', () => {
		addSignedArtifact('voxrox-mail-0.1.1-windows-x64-setup.exe');

		run('0.1.1');

		expect(manifest().platforms['windows-x86_64'].url).toBe(
			'https://github.com/TheVoxRox/mail/releases/download/v0.1.1/voxrox-mail-0.1.1-windows-x64-setup.exe'
		);
	});

	it('refuses a stale artifact from an earlier build rather than publishing it', () => {
		// The exact local accident this guards: last release still in bundle/,
		// this one not built yet. Under the old score the stale file won on the
		// name prefix and the -setup.exe suffix alone.
		addSignedArtifact('voxrox-mail-0.1.0-windows-x64-setup.exe');

		expect(() => run('0.1.1')).toThrow(/carries version 0\.1\.1/);
	});

	it('names the artifacts it did find, so the cause is visible without a rerun', () => {
		addSignedArtifact('voxrox-mail-0.1.0-windows-x64-setup.exe');

		expect(() => run('0.1.1')).toThrow(/voxrox-mail-0\.1\.0-windows-x64-setup\.exe/);
	});

	it('ignores a stale artifact when the right one is also present', () => {
		addSignedArtifact('voxrox-mail-0.1.0-windows-x64-setup.exe');
		addSignedArtifact('voxrox-mail-0.1.1-windows-x64-setup.exe');

		run('0.1.1');

		expect(manifest().platforms['windows-x86_64'].url).toContain(
			'voxrox-mail-0.1.1-windows-x64-setup.exe'
		);
	});

	it('does not accept a version that only appears inside a longer number', () => {
		// 0.1.0 is a substring of 0.1.01; a plain `includes` would take it.
		addSignedArtifact('voxrox-mail-0.1.01-windows-x64-setup.exe');

		expect(() => run('0.1.0')).toThrow(/carries version 0\.1\.0/);
	});

	it('does not accept a version that only appears inside a longer dotted one', () => {
		// The left-hand boundary has to exclude `.` as well as a digit, or the
		// tail of 1.0.1.0 reads as 0.1.0.
		addSignedArtifact('voxrox-mail-1.0.1.0-windows-x64-setup.exe');

		expect(() => run('0.1.0')).toThrow(/carries version 0\.1\.0/);
	});

	it('accepts the version immediately before the extension', () => {
		// The right-hand boundary must NOT exclude `.`: this shape, and a
		// <version>.nsis.zip, put the extension straight after the version. The
		// version check is a hard condition now, so over-rejecting aborts a
		// release that has the correct installer sitting right there.
		addSignedArtifact('voxrox-mail-0.1.1.exe');

		run('0.1.1');

		expect(manifest().platforms['windows-x86_64'].url).toContain('voxrox-mail-0.1.1.exe');
	});

	it('prefers the setup installer when several artifacts carry the version', () => {
		addSignedArtifact('voxrox-mail-0.1.1-windows-x64.exe');
		addSignedArtifact('voxrox-mail-0.1.1-windows-x64-setup.exe');

		run('0.1.1');

		expect(manifest().platforms['windows-x86_64'].url).toContain('-setup.exe');
	});

	it('reports no signed artifact separately from a version mismatch', () => {
		// An unsigned build and a stale build are different mistakes with
		// different fixes, so they must not share one message.
		writeFileSync(path.join(workDir, 'bundle', 'nsis', 'unsigned.exe'), 'installer bytes');

		expect(() => run('0.1.1')).toThrow(/No signed Windows updater artifact found/);
	});

	it('refuses an empty signature', () => {
		addSignedArtifact('voxrox-mail-0.1.1-windows-x64-setup.exe', '');

		expect(() => run('0.1.1')).toThrow(/signature is empty/);
	});
});
