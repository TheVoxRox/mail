import { readFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';
import {
	DUMP_ENTRIES,
	INSTALL_ENTRIES,
	formatReport,
	installerVersion,
	jvmLogProblems,
	missingEntries,
	samePath,
	sessionProblems,
	strayDataDirs,
	treeDifferences
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

		expect(cargo).toMatch(/^name = "voxrox-mail"$/m);
		expect(config.bundle.externalBin).toEqual(['binaries/voxrox-mail-backend']);
		expect(INSTALL_ENTRIES).toEqual(
			expect.arrayContaining(['voxrox-mail.exe', 'voxrox-mail-backend.exe'])
		);
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
		expect(
			missingEntries(['voxrox-mail.exe', 'NOTICE.txt'], ['VOXROX-MAIL.EXE', 'notice.txt'])
		).toEqual([]);
	});

	it('returns what is absent, in the expected order', () => {
		expect(
			missingEntries(['voxrox-mail.exe', 'voxrox-mail-backend.exe', 'runtime'], ['runtime'])
		).toEqual(['voxrox-mail.exe', 'voxrox-mail-backend.exe']);
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

describe('jvmLogProblems', () => {
	// What 0.1.0's sidecar wrote on every installed start, as the Tauri log
	// records it; only the user directory is renamed.
	const sidecar = '[2026-10-20][07:46:38][voxrox_mail_lib::logs][WARN] Sidecar stderr: ';
	const refused = [
		`${sidecar}[0.057s][warning][aot] This file is not the one used while building the AOT cache: 'C:\\Users\\a\\AppData\\Local\\Programs\\VoxRox\\Mail\\app\\mail-backend-0.1.0.jar', timestamp has changed`,
		`${sidecar}[0.057s][error  ][aot] shared class paths mismatch (hint: enable -Xlog:class+path=info to diagnose the failure)`,
		`${sidecar}[0.057s][error  ][aot] An error has occurred while processing the AOT cache. Run with -Xlog:aot for details.`,
		`${sidecar}[0.065s][error  ][aot] Unable to map shared spaces`
	];
	const started = [
		'[2026-10-20][07:46:37][voxrox_mail_lib][INFO] Mail Tauri application started',
		'[2026-10-20][07:46:37][voxrox_mail_lib][INFO] Data root: C:\\Users\\a\\AppData\\Local\\VoxRox\\Mail'
	];

	it('returns every warning and error line and nothing around them', () => {
		expect(jvmLogProblems([...started, ...refused, ''].join('\r\n'))).toEqual(refused);
	});

	it('finds nothing in the log of a clean start', () => {
		expect(jvmLogProblems(started.join('\n'))).toEqual([]);
	});

	it('reads the level, whatever the tags or the message', () => {
		expect(
			jvmLogProblems(
				[
					`${sidecar}[0.1s][warning][cds,heap] Unable to map the archived heap`,
					`${sidecar}[0.1s][warning][gc] Insufficient space for the heap`,
					`${sidecar}[0.1s][info   ][aot] Using AOT-linked classes`,
					`${sidecar}a message that only mentions [warning][gc]`
				].join('\n')
			)
		).toEqual([
			`${sidecar}[0.1s][warning][cds,heap] Unable to map the archived heap`,
			`${sidecar}[0.1s][warning][gc] Insufficient space for the heap`
		]);
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

	it('names the smoke it reports for', () => {
		expect(formatReport([], 'Installed app update smoke')).toBe(
			'Installed app update smoke failed: 0 of 0 checks.'
		);
	});
});

describe('installerVersion', () => {
	it('reads the version, prerelease suffix included, from the name the build gives an installer', () => {
		expect(installerVersion('voxrox-mail-0.1.0-windows-x64-setup.exe')).toBe('0.1.0');
		expect(installerVersion('voxrox-mail-0.1.1-beta.1-windows-x64-setup.exe')).toBe('0.1.1-beta.1');
	});

	it('refuses a name that does not carry a version where the build puts it', () => {
		expect(installerVersion('setup.exe')).toBeNull();
		expect(installerVersion('voxrox-mail-latest-windows-x64-setup.exe')).toBeNull();
		expect(installerVersion('voxrox-mail-0.1.0-windows-x64-setup.exe.sha256')).toBeNull();
	});

	it('reads the names tauri-build.mjs writes', () => {
		expect(read('tauri-build.mjs')).toMatch(
			/\$\{slug\}-\$\{version\}-windows-\$\{artifact\.arch\}-setup\.exe/
		);
	});
});

describe('treeDifferences', () => {
	const tree = (entries) => new Map(Object.entries(entries));

	it('finds nothing when the update laid down exactly what a fresh install does', () => {
		const files = { 'voxrox-mail.exe': 'a', 'app\\mail.aot': 'b' };

		expect(treeDifferences(tree(files), tree(files))).toEqual({
			missing: [],
			differing: [],
			leftover: []
		});
	});

	it('tells a file the update did not write from one it did not replace', () => {
		const fresh = tree({ 'runtime\\bin\\server\\jvm.dll': 'new', 'app\\mail.aot': 'new' });
		const updated = tree({ 'runtime\\bin\\server\\jvm.dll': 'old' });

		expect(treeDifferences(updated, fresh)).toEqual({
			missing: ['app\\mail.aot'],
			differing: ['runtime\\bin\\server\\jvm.dll'],
			leftover: []
		});
	});

	it('names what the previous version left behind, the versioned jar being the expected one', () => {
		const fresh = tree({ 'app\\mail-backend-0.1.1.jar': 'new' });
		const updated = tree({
			'app\\mail-backend-0.1.0.jar': 'old',
			'app\\mail-backend-0.1.1.jar': 'new'
		});

		expect(treeDifferences(updated, fresh).leftover).toEqual(['app\\mail-backend-0.1.0.jar']);
	});
});
