/**
 * The pure half of scripts/installed-app-smoke.mjs: what an installation, a
 * session file and a diagnostic dump are expected to hold, and how the result
 * is reported. The CLI installs, starts and kills the real application and
 * hands the raw facts over, so every judgement about them is tested here on
 * plain values instead of only on a Windows runner. The same goes for
 * scripts/installed-app-update-smoke.mjs.
 */

import { SEMVER_RE } from './semver.mjs';

/** Where the per-user NSIS installer puts the binaries, under %LOCALAPPDATA% (RELEASE_CHECKLIST §3). */
export const EXPECTED_INSTALL_SUBPATH = 'Programs\\VoxRox\\Mail';

/**
 * What the installation directory has to carry. `voxrox-mail.exe` is the Tauri
 * shell (named after the Cargo package), `voxrox-mail-backend.exe` the sidecar
 * launcher from `externalBin: binaries/voxrox-mail-backend`, and `app\` +
 * `runtime\` the jar and the bundled JRE the launcher loads — jpackage's fixed
 * layout, unrelated to the shell's name.
 */
export const INSTALL_ENTRIES = [
	'voxrox-mail.exe',
	'voxrox-mail-backend.exe',
	'app',
	'runtime',
	'NOTICE.txt',
	'uninstall.exe'
];

/** The JSON sections DiagnosticDumpService writes into the ZIP; threads.txt and logs/ are text. */
export const DUMP_ENTRIES = [
	'summary.json',
	'accounts.json',
	'folder-sync-states.json',
	'message-counts.json',
	'runtime.json',
	'client-boot.json',
	'startup-timings.json',
	'manifest.json'
];

/** Entries of `expected` absent from `present`, compared the way Windows compares file names. */
export function missingEntries(expected, present) {
	const have = new Set(present.map((name) => name.toLowerCase()));
	return expected.filter((name) => !have.has(name.toLowerCase()));
}

/**
 * Folders WebView2 or Tauri would create under the bundle identifier. All data
 * belongs under VoxRox\Mail (RELEASE_CHECKLIST §3); one of these means a path
 * the application no longer redirects.
 */
export function strayDataDirs(names) {
	return names.filter((name) => /^org\.voxrox\.mail/i.test(name));
}

/** What is wrong with session.json, as sentences; empty when nothing is. */
export function sessionProblems(session) {
	const problems = [];
	if (!/^http:\/\/127\.0\.0\.1:\d{1,5}\/api$/.test(session?.baseUrl ?? '')) {
		problems.push(
			`baseUrl is ${JSON.stringify(session?.baseUrl)}, expected http://127.0.0.1:<port>/api`
		);
	}
	if (typeof session?.apiKey !== 'string' || session.apiKey.length === 0) {
		problems.push('apiKey is missing');
	}
	return problems;
}

/**
 * Lines of the Tauri log in which the sidecar's JVM wrote a warning or an
 * error. The JVM says these and carries on, so nothing else notices: every
 * check still passes. 0.1.0 shipped like that, with an AOT cache the JVM
 * refused on every installed start and only a slower start to show for it.
 * The sidecar's stderr reaches mail-frontend.log line by line, in the
 * `[uptime][level][tags]` decoration the launcher's .cfg asks for, and the
 * level is what is read: any tag set, and only in that decoration, so a
 * message quoting one does not count.
 */
export function jvmLogProblems(log) {
	return log
		.split(/\r?\n/)
		.filter((line) => /\[\d+(?:\.\d+)?s\]\[(?:warning|error) *\]\[[^\]\s]+\]/.test(line))
		.map((line) => line.trim());
}

/** Two Windows paths name the same directory: quotes, trailing separators and case aside. */
export function samePath(a, b) {
	const normalize = (value) =>
		(value ?? '')
			.trim()
			.replace(/^"|"$/g, '')
			.replace(/[\\/]+$/, '')
			.replace(/\//g, '\\')
			.toLowerCase();
	return normalize(a) !== '' && normalize(a) === normalize(b);
}

/**
 * The version an installer was built for, from the name tauri-build.mjs gives
 * it (`voxrox-mail-<version>-windows-x64-setup.exe`), or null for any other
 * name. Not the setup executable's own version resource: that is the NSIS
 * VIProductVersion, which the bundler computes, and what it makes of a
 * prerelease suffix no build has shown yet (todo.md, the decision on the
 * first ship).
 */
export function installerVersion(fileName) {
	const match = /^voxrox-mail-(.+)-windows-x64-setup\.exe$/i.exec(fileName);
	return match && SEMVER_RE.test(match[1]) ? match[1] : null;
}

/**
 * How an installation that was updated differs from a fresh install of the
 * same version, both given as path → SHA-256 (`treeDigests`). `missing` and
 * `differing` mean the update did not lay the new version down: a file the
 * installer could not write, typically because a process still held it.
 * `leftover` is what the previous version left behind, which the uninstaller,
 * knowing only the files of the version it came with, will leave behind too.
 */
export function treeDifferences(updated, fresh) {
	const sorted = (names) => names.sort((a, b) => a.localeCompare(b));
	return {
		missing: sorted([...fresh.keys()].filter((name) => !updated.has(name))),
		differing: sorted(
			[...fresh.keys()].filter((name) => updated.has(name) && updated.get(name) !== fresh.get(name))
		),
		leftover: sorted([...updated.keys()].filter((name) => !fresh.has(name)))
	};
}

/**
 * The closing summary. Words rather than symbols, because it is read with a
 * screen reader, and the failures repeated at the end so nobody has to scroll
 * back through the run to find them. No checks at all is a failure too: a run
 * that stopped before its first check proved nothing.
 */
export function formatReport(checks, title = 'Installed app smoke') {
	const failed = checks.filter((check) => !check.ok);
	const verdict =
		checks.length > 0 && failed.length === 0
			? `${title} passed: all ${checks.length} checks.`
			: `${title} failed: ${failed.length} of ${checks.length} checks.`;
	return [
		verdict,
		...failed.map((check) => `Failed: ${check.name}${check.detail ? ` -- ${check.detail}` : ''}`)
	].join('\n');
}
