/**
 * The pure half of scripts/installed-app-smoke.mjs: what an installation, a
 * session file and a diagnostic dump are expected to hold, and how the result
 * is reported. The CLI installs, starts and kills the real application and
 * hands the raw facts over, so every judgement about them is tested here on
 * plain values instead of only on a Windows runner.
 */

/** Where the per-user NSIS installer puts the binaries, under %LOCALAPPDATA% (RELEASE_CHECKLIST §3). */
export const EXPECTED_INSTALL_SUBPATH = 'Programs\\VoxRox\\Mail';

/**
 * What the installation directory has to carry. `app.exe` is the Tauri shell
 * (the Cargo package is named `app`), `mail.exe` the sidecar launcher from
 * `externalBin: binaries/mail`, and `app\` + `runtime\` the jar and the bundled
 * JRE the launcher loads.
 */
export const INSTALL_ENTRIES = [
	'app.exe',
	'mail.exe',
	'app',
	'runtime',
	'NOTICE.txt',
	'uninstall.exe'
];

/** The sections DiagnosticDumpService writes into the ZIP. */
export const DUMP_ENTRIES = [
	'summary.json',
	'accounts.json',
	'folder-sync-states.json',
	'message-counts.json',
	'runtime.json',
	'client-boot.json',
	'startup-timings.json'
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
 * The closing summary. Words rather than symbols, because it is read with a
 * screen reader, and the failures repeated at the end so nobody has to scroll
 * back through the run to find them. No checks at all is a failure too: a run
 * that stopped before its first check proved nothing.
 */
export function formatReport(checks) {
	const failed = checks.filter((check) => !check.ok);
	const verdict =
		checks.length > 0 && failed.length === 0
			? `Installed app smoke passed: all ${checks.length} checks.`
			: `Installed app smoke failed: ${failed.length} of ${checks.length} checks.`;
	return [
		verdict,
		...failed.map((check) => `Failed: ${check.name}${check.detail ? ` -- ${check.detail}` : ''}`)
	].join('\n');
}
