import { execFileSync } from 'node:child_process';
import { cpSync, existsSync, readdirSync, readFileSync } from 'node:fs';
import { mkdir, writeFile } from 'node:fs/promises';
import path from 'node:path';
import process from 'node:process';
import { resolveMailDataDir } from './lib/data-dirs.mjs';
import {
	createChecks,
	psQuote,
	pwsh,
	registeredInstallDir,
	request,
	runToExit,
	sha256,
	sidecarProcesses,
	sidecarsGone,
	startApp as startInstalledApp,
	stopApp as stopInstalledApp,
	uninstall,
	until
} from './lib/installed-app.mjs';
import {
	DUMP_ENTRIES,
	EXPECTED_INSTALL_SUBPATH,
	INSTALL_ENTRIES,
	formatReport,
	jvmLogProblems,
	missingEntries,
	samePath,
	sessionProblems,
	strayDataDirs
} from './lib/installed-app-smoke.mjs';
import { waitForExit } from './lib/process-tree.mjs';
import { readZipEntryJson } from './lib/zip-entry.mjs';

/*
 * `node scripts/installed-app-smoke.mjs --installer=<setup.exe>` (from
 * frontend/) — installs a signed candidate on a clean Windows profile and
 * checks what RELEASE_CHECKLIST §3, part of §3a and the orphan half of §6 ask
 * of the INSTALLED application. .github/workflows/release-candidate-smoke.yml
 * runs it on windows-latest against the installer attached to a release.
 *
 * Every other smoke in this repo stops short of the installer: tauri:smoke:sidecar
 * runs the packaged sidecar headless, tauri:smoke:release-startup runs the exe
 * out of target/. What a user gets is the NSIS installer's output — its install
 * path, its shortcuts, its uninstaller, and a Tauri shell spawning the sidecar
 * from where the installer put it — and until this script that was checked
 * only by hand, once per candidate.
 *
 * DESTRUCTIVE by design: it installs, force-kills and uninstalls the
 * application, and it refuses to run on a profile that already has either the
 * installation or the data directory, because that profile is somebody's
 * working machine.
 *
 * What it does not prove, on purpose or by limitation:
 * - A first start while WebView2 is still being installed. Hosted runners have
 *   WebView2 preinstalled, so the unresolved "no window on first launch"
 *   finding on the candidate sheet cannot reproduce here.
 * - That the install needs no elevation. Hosted runners run as an
 *   administrator, so the absence of a UAC prompt is not observable.
 * - Account flows and mail against a real provider. The update from vN-1 and
 *   the downgrade block are installed-app-update-smoke.mjs's.
 *
 * The output uses words rather than symbols, because it is read with a screen
 * reader.
 */

const args = new Map(
	process.argv
		.slice(2)
		.filter((arg) => arg.startsWith('--') && arg.includes('='))
		.map((arg) => {
			const [key, ...valueParts] = arg.slice(2).split('=');
			return [key, valueParts.join('=')];
		})
);

function positiveInt(raw, fallback) {
	const value = Number.parseInt(raw ?? '', 10);
	return Number.isFinite(value) && value > 0 ? value : fallback;
}

if (process.platform !== 'win32') {
	throw new Error('installed-app-smoke runs only on Windows; the application ships Windows-only.');
}
if (!args.get('installer')) {
	throw new Error('Usage: node scripts/installed-app-smoke.mjs --installer=<path to setup.exe>');
}

const installer = path.resolve(args.get('installer'));
const timeoutMs = positiveInt(args.get('timeout-ms'), 180_000);
const orphanTimeoutMs = positiveInt(args.get('orphan-timeout-ms'), 60_000);
const localAppData = process.env.LOCALAPPDATA;
const roamingAppData = process.env.APPDATA;
const expectedInstallDir = path.join(localAppData, EXPECTED_INSTALL_SUBPATH);
const dataDir = resolveMailDataDir();
const targetDir = path.join(process.cwd(), 'target');
const stamp = new Date().toISOString().replace(/[:.]/g, '-');
const reportPath = path.join(targetDir, `installed-app-smoke-${stamp}.json`);
const logsCopyDir = path.join(targetDir, `installed-app-smoke-${stamp}-logs`);

for (const dir of [expectedInstallDir, dataDir]) {
	if (existsSync(dir)) {
		throw new Error(
			`${dir} already exists. This smoke installs, kills and uninstalls the application, ` +
				'so it runs only on a profile that has never had it: a CI runner, not a working machine.'
		);
	}
}
if (!existsSync(installer)) {
	throw new Error(`Installer not found: ${installer}`);
}

/** Thrown after a check whose failure leaves nothing meaningful to check next. */
class Stop extends Error {}

const { checks, record } = createChecks();
const startApp = (dir) => startInstalledApp(dir, { dataDir, timeoutMs });
const stopApp = (app, dir) => stopInstalledApp(app, dir, { orphanTimeoutMs });

await mkdir(targetDir, { recursive: true });
console.log(`Installing ${installer}`);

let installDir = expectedInstallDir;
let running = null;

try {
	// §3 Fresh install
	const installCode = await runToExit(installer, ['/S']);
	if (
		!record('the installer exits 0 in silent mode', installCode === 0, `exit code ${installCode}`)
	) {
		throw new Stop();
	}

	const registered = registeredInstallDir();
	record(
		`the installation is registered per user at %LOCALAPPDATA%\\${EXPECTED_INSTALL_SUBPATH}`,
		samePath(registered, expectedInstallDir),
		registered ? `registered at ${registered}` : 'no uninstall entry named VoxRox Mail under HKCU'
	);
	if (registered) installDir = registered;

	// §3a privacy page: a silent install shows no pages, so it must record no
	// answer about the startup update check. A written 0 would switch the check
	// off for every unattended deployment, and nothing on screen would say so.
	const recordedChoice = pwsh(
		"(Get-ItemProperty -Path 'HKCU:\\Software\\VoxRox\\Mail' -Name UpdateStartupCheck " +
			'-ErrorAction SilentlyContinue).UpdateStartupCheck'
	).trim();
	record(
		'the silent install records no answer about the startup update check',
		recordedChoice === '',
		recordedChoice === '' ? '' : `UpdateStartupCheck is ${recordedChoice}`
	);

	const missing = missingEntries(
		INSTALL_ENTRIES,
		existsSync(installDir) ? readdirSync(installDir) : []
	);
	if (
		!record(
			'the installation carries voxrox-mail.exe, the sidecar, app, runtime, NOTICE.txt and the uninstaller',
			missing.length === 0,
			missing.length > 0 ? `missing ${missing.join(', ')}` : ''
		)
	) {
		throw new Stop();
	}

	const appExe = path.join(installDir, 'voxrox-mail.exe');
	for (const [label, folder, relative] of [
		['desktop', 'Desktop', 'VoxRox Mail.lnk'],
		['Start menu', 'Programs', 'VoxRox\\VoxRox Mail.lnk']
	]) {
		const shortcut = path.join(pwsh(`[Environment]::GetFolderPath('${folder}')`), relative);
		const target = existsSync(shortcut)
			? pwsh(
					`(New-Object -ComObject WScript.Shell).CreateShortcut(${psQuote(shortcut)}).TargetPath`
				)
			: '';
		record(
			`the ${label} shortcut points at voxrox-mail.exe`,
			samePath(target, appExe),
			existsSync(shortcut) ? `target ${target}` : `${shortcut} does not exist`
		);
	}

	try {
		running = await startApp(installDir);
		record(
			'the application starts and the sidecar reports ready',
			true,
			`in ${running.readyMs} ms`
		);
	} catch (error) {
		record('the application starts and the sidecar reports ready', false, error.message);
		throw new Stop();
	}
	const { baseUrl, apiKey } = running.session;

	const problems = sessionProblems(running.session);
	record(
		'session.json carries a loopback base URL and an API key',
		problems.length === 0,
		problems.join('; ')
	);

	const health = await request(`${baseUrl}/internal/health`, apiKey).catch((error) => ({
		status: 0,
		error: error.message
	}));
	record(
		'/api/internal/health answers 200 with the session API key',
		health.status === 200,
		health.error ?? `HTTP ${health.status}`
	);

	for (const [label, relative] of [
		['crypto.bin', 'crypto.bin'],
		['the WebView2 profile', 'webview'],
		['the Tauri log', 'logs\\mail-frontend.log']
	]) {
		const file = path.join(dataDir, relative);
		const found = await until(`${label} in the data directory`, 20_000, () =>
			existsSync(file) ? { done: true, value: true } : { reason: 'not there yet' }
		).catch(() => false);
		record(`${label} is created under VoxRox\\Mail`, found, found ? '' : `${file} does not exist`);
	}

	const stray = strayDataDirs([
		...(existsSync(localAppData) ? readdirSync(localAppData) : []),
		...(existsSync(roamingAppData) ? readdirSync(roamingAppData) : [])
	]);
	record(
		'no org.voxrox.mail folder is created next to VoxRox\\Mail',
		stray.length === 0,
		stray.length > 0 ? `found ${stray.join(', ')}` : ''
	);

	try {
		const dump = await until('the webview to report boot phase ready', timeoutMs, async () => {
			if (running.child.exitCode !== null)
				return { abort: `voxrox-mail.exe exited with code ${running.child.exitCode}` };
			const response = await request(`${baseUrl}/internal/diagnostic-dump`, apiKey, {
				timeoutMs: 10_000
			});
			if (response.status !== 200) return { reason: `HTTP ${response.status}` };
			const boot = readZipEntryJson(response.body, 'client-boot.json');
			if (boot?.phase !== 'ready') {
				return {
					reason: boot ? `boot phase is "${boot.phase}"` : 'the client has not reported yet'
				};
			}
			return { done: true, value: response.body };
		});
		record('the webview boots to ready, as client-boot.json in the diagnostic dump reports', true);
		const empty = DUMP_ENTRIES.filter((name) => readZipEntryJson(dump, name) === null);
		record(
			'the diagnostic dump carries every section',
			empty.length === 0,
			empty.length > 0 ? `empty or missing: ${empty.join(', ')}` : ''
		);
	} catch (error) {
		record(
			'the webview boots to ready, as client-boot.json in the diagnostic dump reports',
			false,
			error.message
		);
	}

	// §6 Sidecar lifecycle: kill the parent only, never the tree
	const before = sidecarProcesses(installDir);
	const taskkill = path.join(process.env.SystemRoot ?? 'C:\\Windows', 'System32', 'taskkill.exe');
	execFileSync(taskkill, ['/PID', String(running.child.pid), '/F'], {
		stdio: 'ignore',
		windowsHide: true
	});
	await waitForExit(running.child).catch(() => null);
	running = null;
	const killedAt = Date.now();
	const orphans = await sidecarsGone(installDir, orphanTimeoutMs);
	record(
		'killing voxrox-mail.exe alone takes the sidecar with it',
		before.length > 0 && orphans.length === 0,
		before.length === 0
			? 'no sidecar process was running before the kill, so nothing was proven'
			: orphans.length > 0
				? `still running ${orphanTimeoutMs} ms after the kill: PIDs ${orphans.join(', ')}`
				: `gone ${Date.now() - killedAt} ms after the kill`
	);

	// §3 A repeated start over the existing crypto.bin
	const cryptoFile = path.join(dataDir, 'crypto.bin');
	const cryptoBefore = existsSync(cryptoFile) ? sha256(cryptoFile) : null;
	try {
		running = await startApp(installDir);
		record(
			'a second start over the existing crypto.bin reaches ready',
			true,
			`in ${running.readyMs} ms`
		);
	} catch (error) {
		record('a second start over the existing crypto.bin reaches ready', false, error.message);
		throw new Stop();
	}
	record(
		'the second start leaves crypto.bin unchanged',
		cryptoBefore !== null && existsSync(cryptoFile) && sha256(cryptoFile) === cryptoBefore
	);
	await stopApp(running, installDir);
	running = null;

	// Read after both starts, whose lines the log holds one after the other.
	const frontendLog = path.join(dataDir, 'logs', 'mail-frontend.log');
	const jvmProblems = existsSync(frontendLog)
		? jvmLogProblems(readFileSync(frontendLog, 'utf8'))
		: null;
	record(
		'the sidecar writes no JVM warning or error to the Tauri log',
		jvmProblems?.length === 0,
		jvmProblems === null ? `${frontendLog} does not exist` : jvmProblems.join('; ')
	);

	// §3a Reinstalling the same version
	const reinstallCode = await runToExit(installer, ['/S']);
	record(
		'reinstalling the same version exits 0',
		reinstallCode === 0,
		`exit code ${reinstallCode}`
	);
	record(
		'the reinstall keeps crypto.bin',
		cryptoBefore !== null && existsSync(cryptoFile) && sha256(cryptoFile) === cryptoBefore
	);
} catch (error) {
	if (!(error instanceof Stop))
		record('the smoke runs to its end', false, error.stack ?? error.message);
} finally {
	if (running) await stopApp(running, installDir);

	if (existsSync(path.join(dataDir, 'logs'))) {
		cpSync(path.join(dataDir, 'logs'), logsCopyDir, { recursive: true });
	}

	const uninstallCode = await uninstall(installDir);
	if (uninstallCode !== null) {
		record(
			'the uninstaller exits 0 in silent mode',
			uninstallCode === 0,
			`exit code ${uninstallCode}`
		);
		record(
			'uninstalling removes voxrox-mail.exe',
			!existsSync(path.join(installDir, 'voxrox-mail.exe'))
		);
	}

	await writeFile(
		reportPath,
		`${JSON.stringify({ installer, installDir, dataDir, checks }, null, 2)}\n`,
		'utf8'
	);
	console.log('');
	console.log(formatReport(checks));
	console.log(`Report: ${reportPath}`);
}

process.exitCode = checks.length > 0 && checks.every((check) => check.ok) ? 0 : 1;
