import { readFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';
import {
	DUMP_ENTRIES,
	INSTALL_ENTRIES,
	formatReport,
	missingEntries,
	samePath,
	sessionProblems,
	strayDataDirs
} from './lib/installed-app-smoke.mjs';

/*
 * installed-app-smoke.mjs itself needs a Windows runner and an installer, so
 * it is proven by release-candidate-smoke.yml. What it decides about the facts
 * it gathers is decided in the lib, and tested here — including the two lists
 * that name things outside this directory, which are checked against the files
 * that define them so a rename there fails here rather than on a runner.
 */

const here = path.dirname(fileURLToPath(import.meta.url));
const read = (relative) => readFileSync(path.join(here, relative), 'utf8');

describe('INSTALL_ENTRIES and DUMP_ENTRIES', () => {
	it('name the shell after the Cargo package and the sidecar after externalBin', () => {
		const cargo = read('../src-tauri/Cargo.toml');
		const config = JSON.parse(read('../src-tauri/tauri.conf.json'));

		expect(cargo).toMatch(/^\[package\]\r?\nname = "app"$/m);
		expect(config.bundle.externalBin).toEqual(['binaries/mail']);
		expect(INSTALL_ENTRIES).toEqual(expect.arrayContaining(['app.exe', 'mail.exe']));
		expect(INSTALL_ENTRIES).toEqual(expect.arrayContaining(Object.values(config.bundle.resources)));
	});

	it('list every section the diagnostic dump writes', () => {
		const service = read(
			'../../backend/src/main/java/org/voxrox/mailbackend/core/diagnostic/DiagnosticDumpService.java'
		);
		const written = [...service.matchAll(/addJson\(zip, "([^"]+)"/g)].map((match) => match[1]);

		expect(written.length).toBeGreaterThan(0);
		expect([...DUMP_ENTRIES].sort()).toEqual([...written].sort());
	});
});

describe('missingEntries', () => {
	it('compares names the way Windows does', () => {
		expect(missingEntries(['app.exe', 'NOTICE.txt'], ['APP.EXE', 'notice.txt'])).toEqual([]);
	});

	it('returns what is absent, in the expected order', () => {
		expect(missingEntries(['app.exe', 'mail.exe', 'runtime'], ['runtime'])).toEqual([
			'app.exe',
			'mail.exe'
		]);
	});
});

describe('strayDataDirs', () => {
	it('finds folders named after the bundle identifier and nothing else', () => {
		expect(
			strayDataDirs(['VoxRox', 'org.voxrox.mail', 'ORG.VOXROX.MAIL.dev', 'Microsoft', 'voxrox.org'])
		).toEqual(['org.voxrox.mail', 'ORG.VOXROX.MAIL.dev']);
	});
});

describe('sessionProblems', () => {
	it('accepts a loopback base URL with a port and an API key', () => {
		expect(sessionProblems({ baseUrl: 'http://127.0.0.1:51234/api', apiKey: 'k' })).toEqual([]);
	});

	it('rejects a base URL off loopback, without a port or without /api', () => {
		for (const baseUrl of [
			'http://localhost:51234/api',
			'http://127.0.0.1/api',
			'http://127.0.0.1:51234',
			'http://0.0.0.0:51234/api'
		]) {
			expect(sessionProblems({ baseUrl, apiKey: 'k' })).toHaveLength(1);
		}
	});

	it('reports a missing API key and a missing session alike', () => {
		expect(sessionProblems({ baseUrl: 'http://127.0.0.1:1/api', apiKey: '' })).toEqual([
			'apiKey is missing'
		]);
		expect(sessionProblems(null)).toHaveLength(2);
	});
});

describe('samePath', () => {
	it('ignores quotes, trailing separators, slash direction and case', () => {
		expect(
			samePath(
				'"C:\\Users\\a\\AppData\\Local\\Programs\\VoxRox\\Mail\\"',
				'c:/users/a/appdata/local/programs/voxrox/mail'
			)
		).toBe(true);
	});

	it('does not call an empty path the same as another empty path', () => {
		expect(samePath('', '')).toBe(false);
		expect(samePath('C:\\a', 'C:\\b')).toBe(false);
	});
});

describe('formatReport', () => {
	it('says passed and lists nothing when every check passed', () => {
		expect(formatReport([{ name: 'a', ok: true, detail: '' }])).toBe(
			'Installed app smoke passed: all 1 checks.'
		);
	});

	it('repeats each failure with its detail', () => {
		const report = formatReport([
			{ name: 'installs', ok: true, detail: '' },
			{ name: 'kills the sidecar', ok: false, detail: 'PIDs 12' }
		]);

		expect(report).toBe(
			'Installed app smoke failed: 1 of 2 checks.\nFailed: kills the sidecar -- PIDs 12'
		);
	});

	it('fails a run that recorded no check at all', () => {
		expect(formatReport([])).toBe('Installed app smoke failed: 0 of 0 checks.');
	});
});
