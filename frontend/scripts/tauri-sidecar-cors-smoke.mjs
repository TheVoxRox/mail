/**
 * Headless release gate for the CORS contract that broke in v0.1.0.
 *
 * The packaged Windows WebView2 talks to the loopback backend with
 * `Origin: http://tauri.localhost`. `tauri dev` instead serves the frontend
 * from `http://localhost:<port>`, so the webview origin only ever appears in a
 * real bundle — both a `tauri dev` run and a backend-only probe (no Origin
 * header) miss a CORS allowlist regression, which is exactly why the original
 * 403 shipped. This smoke launches the EXACT packaged sidecar binary and
 * asserts:
 *   - the webview origin is accepted (2xx) and echoed back in
 *     Access-Control-Allow-Origin;
 *   - a foreign web origin is rejected (403), so the allowlist cannot silently
 *     widen to "*".
 *
 * It deliberately does NOT launch the full Tauri app: the sidecar is what
 * enforces CORS, and a headless check is deterministic on a CI runner (no
 * WebView2 / desktop session needed). It needs no backend/.env either — the
 * packaged sidecar has its OAuth client ids baked in by
 * package-sidecar-windows.ps1 and self-bootstraps its crypto key from the
 * (isolated, temporary) data dir.
 *
 * Usage: node scripts/tauri-sidecar-cors-smoke.mjs [--exe=<path>] [--timeout-ms=<n>]
 */

import { spawn } from 'node:child_process';
import { mkdtemp, readFile, rm, stat } from 'node:fs/promises';
import http from 'node:http';
import os from 'node:os';
import path from 'node:path';
import process from 'node:process';
import { fileURLToPath } from 'node:url';
import { terminateProcessTree, waitForExit } from './lib/process-tree.mjs';
import { wait } from './lib/run.mjs';

const scriptDir = path.dirname(fileURLToPath(import.meta.url));

/*
 * Kept in sync with the CORS allowlist in backend
 * SecurityConfig#corsConfigurationSource. Windows WebView2 reports this origin;
 * macOS/Linux use tauri://localhost (V0.1.0 ships Windows-only).
 */
const WEBVIEW_ORIGIN = 'http://tauri.localhost';
const FOREIGN_ORIGIN = 'https://cors-probe.invalid';
const SIDECAR_NAME = 'mail-x86_64-pc-windows-msvc.exe';

const args = new Map(
	process.argv
		.slice(2)
		.filter((arg) => arg.startsWith('--') && arg.includes('='))
		.map((arg) => {
			const [key, ...rest] = arg.slice(2).split('=');
			return [key, rest.join('=')];
		})
);

const exePath = path.resolve(
	args.get('exe') ?? path.join(scriptDir, '..', 'src-tauri', 'binaries', SIDECAR_NAME)
);
const startupTimeoutMs = positiveInt(args.get('timeout-ms'), 60_000);

function positiveInt(raw, fallback) {
	const value = Number.parseInt(raw ?? '', 10);
	return Number.isFinite(value) && value > 0 ? value : fallback;
}

async function exists(filePath) {
	try {
		await stat(filePath);
		return true;
	} catch {
		return false;
	}
}

/** GET that resolves with { status, headers } for ANY response (a 403 is an expected outcome here). */
function probe(url, { origin, apiKey, timeoutMs }) {
	return new Promise((resolve, reject) => {
		const headers = {};
		if (apiKey) headers['X-API-KEY'] = apiKey;
		if (origin) headers.Origin = origin;
		const request = http.get(url, { headers, timeout: timeoutMs }, (response) => {
			response.setEncoding('utf8');
			response.on('data', () => {});
			response.on('end', () =>
				resolve({ status: response.statusCode ?? 0, headers: response.headers })
			);
		});
		request.on('timeout', () =>
			request.destroy(new Error(`GET ${url} timed out after ${timeoutMs} ms`))
		);
		request.on('error', reject);
	});
}

async function waitForSession(sessionPath, child) {
	const deadline = Date.now() + startupTimeoutMs;
	while (Date.now() < deadline) {
		if (child.exitCode !== null) {
			throw new Error(`Sidecar exited before writing session.json (code ${child.exitCode}).`);
		}
		if (await exists(sessionPath)) {
			return JSON.parse(await readFile(sessionPath, 'utf8'));
		}
		await wait(150);
	}
	throw new Error(`Timed out after ${startupTimeoutMs} ms waiting for ${sessionPath}.`);
}

async function assertCorsContract(session) {
	const url = `${session.baseUrl}/v1/system/readiness`;

	const allowed = await probe(url, {
		origin: WEBVIEW_ORIGIN,
		apiKey: session.apiKey,
		timeoutMs: 3_000
	});
	if (allowed.status < 200 || allowed.status >= 300) {
		throw new Error(
			`Webview origin ${WEBVIEW_ORIGIN} got HTTP ${allowed.status} (expected 2xx). ` +
				`The installed app would fail to reach the backend — CORS allowlist regression.`
		);
	}
	const allowOrigin = allowed.headers['access-control-allow-origin'];
	if (allowOrigin !== WEBVIEW_ORIGIN) {
		throw new Error(
			`Expected Access-Control-Allow-Origin=${WEBVIEW_ORIGIN}, got ${allowOrigin ?? '<none>'}.`
		);
	}

	const foreign = await probe(url, {
		origin: FOREIGN_ORIGIN,
		apiKey: session.apiKey,
		timeoutMs: 3_000
	});
	if (foreign.status !== 403) {
		throw new Error(
			`Foreign origin ${FOREIGN_ORIGIN} got HTTP ${foreign.status} (expected 403). ` +
				`The CORS allowlist is too broad.`
		);
	}

	return {
		webviewStatus: allowed.status,
		allowOriginHeader: allowOrigin,
		foreignStatus: foreign.status
	};
}

async function removeWithRetry(dir) {
	// The sidecar JVM briefly keeps the SQLite db handles after the process tree
	// is torn down; one retry clears the transient lock. The dir lives under the
	// OS temp root, so a leftover is harmless even if both attempts fail.
	for (let attempt = 0; attempt < 2; attempt++) {
		try {
			await rm(dir, { recursive: true, force: true });
			return;
		} catch {
			await wait(800);
		}
	}
	await rm(dir, { recursive: true, force: true }).catch(() => {});
}

if (!(await exists(exePath))) {
	throw new Error(
		`Sidecar executable not found: ${exePath}. Package it (backend/scripts/package-sidecar-windows.ps1) ` +
			`and sync it (npm run sidecar:sync:windows) first.`
	);
}

const dataDir = await mkdtemp(path.join(os.tmpdir(), 'voxrox-cors-smoke-'));
const sessionPath = path.join(dataDir, 'session.json');

console.log(`CORS contract smoke: launching ${exePath}`);
console.log(`Isolated data dir: ${dataDir}`);

const child = spawn(exePath, [], {
	cwd: path.dirname(exePath),
	env: { ...process.env, APP_DATA_DIR: dataDir },
	windowsHide: true
});

let stderr = '';
child.stderr?.setEncoding('utf8');
child.stderr?.on('data', (chunk) => {
	stderr += chunk;
});

try {
	const session = await waitForSession(sessionPath, child);
	const result = await assertCorsContract(session);
	console.log(
		`OK — webview origin ${WEBVIEW_ORIGIN} → ${result.webviewStatus} ` +
			`(Access-Control-Allow-Origin ${result.allowOriginHeader}); foreign origin → ${result.foreignStatus}.`
	);
} catch (error) {
	if (stderr.trim()) {
		console.error('--- sidecar stderr (tail) ---');
		console.error(stderr.split(/\r?\n/).slice(-20).join('\n'));
	}
	throw error;
} finally {
	await terminateProcessTree(child).catch(() => {});
	await waitForExit(child).catch(() => null);
	await removeWithRetry(dataDir);
}
