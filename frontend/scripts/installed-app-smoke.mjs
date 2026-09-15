import { execFileSync, spawn } from 'node:child_process';
import { createHash } from 'node:crypto';
import { cpSync, existsSync, readdirSync, readFileSync, statSync } from 'node:fs';
import { mkdir, writeFile } from 'node:fs/promises';
import http from 'node:http';
import path from 'node:path';
import process from 'node:process';
import { resolveMailDataDir } from './lib/data-dirs.mjs';
import {
	DUMP_ENTRIES,
	EXPECTED_INSTALL_SUBPATH,
	INSTALL_ENTRIES,
	formatReport,
	missingEntries,
	samePath,
	sessionProblems,
	strayDataDirs
} from './lib/installed-app-smoke.mjs';
import { terminateProcessTree, waitForExit } from './lib/process-tree.mjs';
import { wait } from './lib/run.mjs';
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
 *   finding from the a2caa27 sheet cannot reproduce here.
 * - That the install needs no elevation. Hosted runners run as an
 *   administrator, so the absence of a UAC prompt is not observable.
 * - Account flows, mail against a real provider, the downgrade block (a first
 *   ship has no older installer) and the update from vN-1.
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

const checks = [];

function record(name, ok, detail = '') {
	checks.push({ name, ok, detail });
	console.log(`${ok ? 'Passed' : 'FAILED'}: ${name}${detail ? ` -- ${detail}` : ''}`);
	return ok;
}

const psQuote = (value) => `'${String(value).replaceAll("'", "''")}'`;

function pwsh(script) {
	return execFileSync('pwsh', ['-NoProfile', '-NonInteractive', '-Command', script], {
		encoding: 'utf8',
		windowsHide: true
	}).trim();
}

/**
 * Runs a program to completion. `verbatim` hands the command line over
 * unquoted, which the NSIS uninstaller needs: `_?=<dir>` must be the last
 * argument and must not be quoted, even when the directory has a space in it.
 */
function runToExit(file, argv, verbatim = false) {
	return new Promise((resolve, reject) => {
		const child = spawn(file, argv, {
			stdio: 'ignore',
			windowsHide: true,
			windowsVerbatimArguments: verbatim
		});
		child.once('error', reject);
		child.once('exit', (code) => resolve(code));
	});
}

function request(url, apiKey, requestTimeoutMs = 5_000) {
	return new Promise((resolve, reject) => {
		const req = http.get(
			url,
			{ headers: { 'X-API-KEY': apiKey }, timeout: requestTimeoutMs },
			(response) => {
				const chunks = [];
				response.on('data', (chunk) => chunks.push(chunk));
				response.on('end', () =>
					resolve({ status: response.statusCode ?? 0, body: Buffer.concat(chunks) })
				);
			}
		);
		req.on('timeout', () =>
			req.destroy(new Error(`GET ${url} timed out after ${requestTimeoutMs} ms`))
		);
		req.on('error', reject);
	});
}

/**
 * Polls `probe` until it answers `{ done: true, value }`. `{ reason }` means
 * not yet; `{ abort }` means never, for instance because the process under test
 * has exited. A throwing probe counts as not yet, since a half-written file or a
 * refused connection is what "not yet" looks like from outside.
 */
async function until(what, limitMs, probe) {
	const deadline = Date.now() + limitMs;
	let last = 'no attempt completed';
	while (Date.now() < deadline) {
		let answer;
		try {
			answer = await probe();
		} catch (error) {
			answer = { reason: error.message };
		}
		if (answer.abort) throw new Error(`${what}: ${answer.abort}`);
		if (answer.done) return answer.value;
		last = answer.reason;
		await wait(500);
	}
	throw new Error(`timed out after ${limitMs} ms waiting for ${what}: ${last}`);
}

const sha256 = (file) => createHash('sha256').update(readFileSync(file)).digest('hex');

/** PIDs of sidecar launchers running out of the installation directory. */
function sidecarProcesses(installDir) {
	const out = pwsh(
		`@(Get-CimInstance Win32_Process -Filter "Name='voxrox-mail-backend.exe'" | ` +
			`Where-Object { $_.ExecutablePath -and $_.ExecutablePath.StartsWith(${psQuote(installDir)}, ` +
			`[System.StringComparison]::OrdinalIgnoreCase) } | ForEach-Object { $_.ProcessId }) -join ','`
	);
	return out ? out.split(',').map(Number) : [];
}

async function startApp(installDir) {
	const startedAt = Date.now();
	const child = spawn(path.join(installDir, 'voxrox-mail.exe'), [], {
		cwd: installDir,
		stdio: 'ignore',
		windowsHide: false
	});
	child.once('error', () => {});
	const exited = () =>
		child.exitCode !== null
			? { abort: `voxrox-mail.exe exited with code ${child.exitCode}` }
			: null;
	const sessionPath = path.join(dataDir, 'session.json');
	const readyPath = path.join(dataDir, '.ready');

	try {
		const session = await until('session.json and .ready from this start', timeoutMs, () => {
			if (exited()) return exited();
			if (!existsSync(sessionPath) || !existsSync(readyPath)) return { reason: 'not written yet' };
			const fresh = (file) => statSync(file).mtimeMs >= startedAt - 500;
			if (!fresh(sessionPath) || !fresh(readyPath))
				return { reason: 'left over from an earlier start' };
			return { done: true, value: JSON.parse(readFileSync(sessionPath, 'utf8')) };
		});
		await until('readiness', timeoutMs, async () => {
			if (exited()) return exited();
			const response = await request(
				`${session.baseUrl}/v1/system/readiness`,
				session.apiKey,
				3_000
			);
			if (response.status !== 200) return { reason: `HTTP ${response.status}` };
			const readiness = JSON.parse(response.body.toString('utf8'));
			return readiness.ready === true
				? { done: true, value: readiness }
				: { reason: `ready is ${JSON.stringify(readiness.ready)}` };
		});
		return { child, session, readyMs: Date.now() - startedAt };
	} catch (error) {
		await terminateProcessTree(child).catch(() => null);
		throw error;
	}
}

async function stopApp(running, installDir) {
	await terminateProcessTree(running.child).catch(() => null);
	await waitForExit(running.child).catch(() => null);
	await until('the sidecar to exit after the app was stopped', orphanTimeoutMs, () => {
		const left = sidecarProcesses(installDir);
		return left.length === 0 ? { done: true } : { reason: `PIDs ${left.join(', ')}` };
	}).catch(() => null);
}

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

	const registered = pwsh(
		"$entry = Get-ChildItem 'HKCU:\\Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall' | " +
			"Get-ItemProperty | Where-Object DisplayName -eq 'VoxRox Mail' | Select-Object -First 1; " +
			'if ($entry) { $entry.InstallLocation }'
	).replace(/^"|"$/g, '');
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
			const response = await request(`${baseUrl}/internal/diagnostic-dump`, apiKey, 10_000);
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
	const orphans = await until('the sidecar to follow voxrox-mail.exe', orphanTimeoutMs, () => {
		const left = sidecarProcesses(installDir);
		return left.length === 0 ? { done: true, value: [] } : { reason: `PIDs ${left.join(', ')}` };
	}).catch(() => sidecarProcesses(installDir));
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

	const uninstaller = path.join(installDir, 'uninstall.exe');
	if (existsSync(uninstaller)) {
		const uninstallCode = await runToExit(uninstaller, [`/S _?=${installDir}`], true);
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
