/**
 * Driving an installed application from outside: running its installers,
 * starting it, talking to its sidecar and stopping it again. Shared by
 * scripts/installed-app-smoke.mjs (a fresh install) and
 * scripts/installed-app-update-smoke.mjs (an update over the previous
 * version). Everything here touches a real Windows profile; what the two
 * smokes conclude from it lives in ./installed-app-smoke.mjs, on plain values.
 */

import { execFileSync, spawn } from 'node:child_process';
import { createHash } from 'node:crypto';
import { existsSync, readdirSync, readFileSync, statSync } from 'node:fs';
import http from 'node:http';
import path from 'node:path';
import { terminateProcessTree, waitForExit } from './process-tree.mjs';
import { wait } from './run.mjs';

export const psQuote = (value) => `'${String(value).replaceAll("'", "''")}'`;

export function pwsh(script) {
	return execFileSync('pwsh', ['-NoProfile', '-NonInteractive', '-Command', script], {
		encoding: 'utf8',
		windowsHide: true
	}).trim();
}

/**
 * Runs a program to completion. `verbatim` hands the command line over
 * unquoted, which the NSIS uninstaller needs: `_?=<dir>` must be the last
 * argument and must not be quoted, even when the directory has a space in it.
 * With `limitMs`, a program still running after it is killed and the promise
 * resolves to `null`: an installer waiting on a dialog nobody will answer
 * would otherwise hold the job until its timeout, with no word on why.
 */
export function runToExit(file, argv, verbatim = false, limitMs = 0) {
	return new Promise((resolve, reject) => {
		const child = spawn(file, argv, {
			stdio: 'ignore',
			windowsHide: true,
			windowsVerbatimArguments: verbatim
		});
		const timer =
			limitMs > 0
				? setTimeout(() => {
						void terminateProcessTree(child).catch(() => null);
						resolve(null);
					}, limitMs)
				: null;
		child.once('error', (error) => {
			if (timer) clearTimeout(timer);
			reject(error);
		});
		child.once('exit', (code) => {
			if (timer) clearTimeout(timer);
			resolve(code);
		});
	});
}

/** How a `runToExit` ended, for a check's detail. */
export const exitText = (code) =>
	code === null ? 'still running when the limit ran out, so it was killed' : `exit code ${code}`;

/** One request to the sidecar, authenticated with the session's API key. */
export function request(url, apiKey, { timeoutMs = 5_000, method = 'GET', json } = {}) {
	return new Promise((resolve, reject) => {
		const body = json === undefined ? null : Buffer.from(JSON.stringify(json), 'utf8');
		const req = http.request(
			url,
			{
				method,
				headers: {
					'X-API-KEY': apiKey,
					...(body ? { 'Content-Type': 'application/json', 'Content-Length': body.length } : {})
				},
				timeout: timeoutMs
			},
			(response) => {
				const chunks = [];
				response.on('data', (chunk) => chunks.push(chunk));
				response.on('end', () =>
					resolve({ status: response.statusCode ?? 0, body: Buffer.concat(chunks) })
				);
			}
		);
		req.on('timeout', () =>
			req.destroy(new Error(`${method} ${url} timed out after ${timeoutMs} ms`))
		);
		req.on('error', reject);
		req.end(body ?? undefined);
	});
}

/**
 * Polls `probe` until it answers `{ done: true, value }`. `{ reason }` means
 * not yet; `{ abort }` means never, for instance because the process under test
 * has exited. A throwing probe counts as not yet, since a half-written file or a
 * refused connection is what "not yet" looks like from outside.
 */
export async function until(what, limitMs, probe) {
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

export const sha256 = (file) => createHash('sha256').update(readFileSync(file)).digest('hex');

/** SHA-256 of every file under `root`, keyed by its path relative to it. */
export function treeDigests(root) {
	const digests = new Map();
	const walk = (dir) => {
		for (const entry of readdirSync(dir, { withFileTypes: true })) {
			const full = path.join(dir, entry.name);
			if (entry.isDirectory()) walk(full);
			else digests.set(path.relative(root, full), sha256(full));
		}
	};
	if (existsSync(root)) walk(root);
	return digests;
}

/** Where the uninstall entry named VoxRox Mail says the application is installed, or ''. */
export function registeredInstallDir() {
	return pwsh(
		"$entry = Get-ChildItem 'HKCU:\\Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall' | " +
			"Get-ItemProperty | Where-Object DisplayName -eq 'VoxRox Mail' | Select-Object -First 1; " +
			'if ($entry) { $entry.InstallLocation }'
	).replace(/^"|"$/g, '');
}

/** PIDs of sidecar launchers running out of the installation directory. */
export function sidecarProcesses(installDir) {
	const out = pwsh(
		`@(Get-CimInstance Win32_Process -Filter "Name='voxrox-mail-backend.exe'" | ` +
			`Where-Object { $_.ExecutablePath -and $_.ExecutablePath.StartsWith(${psQuote(installDir)}, ` +
			`[System.StringComparison]::OrdinalIgnoreCase) } | ForEach-Object { $_.ProcessId }) -join ','`
	);
	return out ? out.split(',').map(Number) : [];
}

/**
 * Starts the installed shell and waits until its sidecar has written a fresh
 * session.json and .ready and answers readiness with `ready: true`.
 */
export async function startApp(installDir, { dataDir, timeoutMs }) {
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
		const readiness = await until('readiness', timeoutMs, async () => {
			if (exited()) return exited();
			const response = await request(`${session.baseUrl}/v1/system/readiness`, session.apiKey, {
				timeoutMs: 3_000
			});
			if (response.status !== 200) return { reason: `HTTP ${response.status}` };
			const readiness = JSON.parse(response.body.toString('utf8'));
			return readiness.ready === true
				? { done: true, value: readiness }
				: { reason: `ready is ${JSON.stringify(readiness.ready)}` };
		});
		return { child, session, readiness, readyMs: Date.now() - startedAt };
	} catch (error) {
		await terminateProcessTree(child).catch(() => null);
		throw error;
	}
}

/** Waits until no sidecar runs out of `installDir`; the PIDs still running when it gives up. */
export async function sidecarsGone(installDir, limitMs) {
	return until('the sidecar to exit', limitMs, () => {
		const left = sidecarProcesses(installDir);
		return left.length === 0 ? { done: true, value: [] } : { reason: `PIDs ${left.join(', ')}` };
	}).catch(() => sidecarProcesses(installDir));
}

export async function stopApp(running, installDir, { orphanTimeoutMs }) {
	await terminateProcessTree(running.child).catch(() => null);
	await waitForExit(running.child).catch(() => null);
	await sidecarsGone(installDir, orphanTimeoutMs);
}

/** Runs the uninstaller in place and silently; its exit code, or null with nothing to run. */
export async function uninstall(installDir) {
	const uninstaller = path.join(installDir, 'uninstall.exe');
	if (!existsSync(uninstaller)) return null;
	return runToExit(uninstaller, [`/S _?=${installDir}`], true);
}

/**
 * The checks a smoke has run, and how each one is printed. Words rather than
 * symbols, because the output is read with a screen reader.
 */
export function createChecks() {
	const checks = [];
	return {
		checks,
		record(name, ok, detail = '') {
			checks.push({ name, ok, detail });
			console.log(`${ok ? 'Passed' : 'FAILED'}: ${name}${detail ? ` -- ${detail}` : ''}`);
			return ok;
		}
	};
}
