/**
 * Headless release gate for the claims only the packaged sidecar can settle.
 *
 * Everything here is a thing that is true of the shipped artifact or of
 * nothing: `mvn verify` and the GreenMail ITs run on the full system JDK and
 * the full compile classpath, and `tauri dev` runs the plain JIT jar, so the
 * configuration that reaches a user is the one no other check exercises. Each
 * assertion lands in this one script rather than a sibling, because booting the
 * sidecar is the expensive part and it is already paid for here.
 *
 * The first was CORS, which is where the file got its original name. The
 * packaged Windows WebView2 talks to the loopback backend with
 * `Origin: http://tauri.localhost`. `tauri dev` instead serves the frontend
 * from `http://localhost:<port>`, so the webview origin only ever appears in a
 * real bundle — both a `tauri dev` run and a backend-only probe (no Origin
 * header) miss a CORS allowlist regression, which is exactly why the original
 * 403 shipped. This smoke launches the EXACT packaged sidecar binary and
 * asserts:
 *   - the webview origin is accepted (2xx) and echoed back in
 *     Access-Control-Allow-Origin;
 *   - a foreign web origin is rejected (403), so the allowlist cannot silently
 *     widen to "*";
 *   - the boot endpoints the client actually calls (client-config, accounts)
 *     answer 2xx with JSON through the webview origin. These exercise the
 *     AOT-compiled controller + JPA + Jackson paths that only the packaged,
 *     AOT-cached sidecar runs (dev uses the plain JIT jar) and that a
 *     readiness-only probe never touches;
 *   - an account can be created and mapped. This is the half the boot probes
 *     cannot reach: over the empty table of a fresh install they map no row, so
 *     everything AccountMapper pulls in stays unloaded. #393 shipped straight
 *     through that gap. See assertMappedAccount.
 *
 * It deliberately does NOT launch the full Tauri app: the sidecar is what
 * enforces CORS, and a headless check is deterministic on a CI runner (no
 * WebView2 / desktop session needed). It needs no backend/.env either — the
 * packaged sidecar has its OAuth client ids baked in by
 * package-sidecar-windows.ps1 and self-bootstraps its crypto key from the
 * (isolated, temporary) data dir.
 *
 * Usage: node scripts/tauri-sidecar-smoke.mjs [--exe=<path>] [--timeout-ms=<n>]
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

/**
 * Resolves with { status, headers, body } for ANY response — a 403 is an
 * expected outcome here, so a non-2xx is data, not an error.
 */
function send(url, { method = 'GET', origin, apiKey, timeoutMs, body }) {
	return new Promise((resolve, reject) => {
		const headers = {};
		if (apiKey) headers['X-API-KEY'] = apiKey;
		if (origin) headers.Origin = origin;
		const payload = body === undefined ? null : Buffer.from(JSON.stringify(body), 'utf8');
		if (payload) {
			headers['Content-Type'] = 'application/json';
			headers['Content-Length'] = String(payload.length);
		}
		const request = http.request(url, { method, headers, timeout: timeoutMs }, (response) => {
			let text = '';
			response.setEncoding('utf8');
			response.on('data', (chunk) => {
				text += chunk;
			});
			response.on('end', () =>
				resolve({ status: response.statusCode ?? 0, headers: response.headers, body: text })
			);
		});
		request.on('timeout', () =>
			request.destroy(new Error(`${method} ${url} timed out after ${timeoutMs} ms`))
		);
		request.on('error', reject);
		if (payload) request.write(payload);
		request.end();
	});
}

const probe = (url, options) => send(url, { ...options, method: 'GET' });

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

/*
 * Endpoints the desktop client fetches during boot (see frontend bootstrap.ts:
 * loadClientConfig + loadAccounts). Probing them through the real webview
 * origin proves the packaged, AOT-cached sidecar can actually serve data — not
 * just answer the static readiness probe.
 *
 * On the fresh isolated DB these are the first-run shape: client-config is
 * static and accounts is an empty list. That empty list used to be written here
 * as the reason the probe is safe. It is also the reason it was weak — see
 * assertMappedAccount, which runs after these and supplies the row they cannot.
 */
const BOOT_ENDPOINTS = ['/v1/client-config', '/v1/accounts'];

async function assertBootEndpoints(session) {
	const results = [];
	for (const endpoint of BOOT_ENDPOINTS) {
		const url = `${session.baseUrl}${endpoint}`;
		const response = await probe(url, {
			origin: WEBVIEW_ORIGIN,
			apiKey: session.apiKey,
			timeoutMs: 5_000
		});
		if (response.status < 200 || response.status >= 300) {
			throw new Error(
				`${endpoint} returned HTTP ${response.status} (expected 2xx) through the packaged sidecar.`
			);
		}
		try {
			JSON.parse(response.body);
		} catch {
			throw new Error(
				`${endpoint} returned a non-JSON body through the packaged sidecar ` +
					`(AOT serialization / controller regression?).`
			);
		}
		results.push(`${endpoint} → ${response.status}`);
	}
	return results;
}

/*
 * The one shape a fresh install does not have: an account to map.
 *
 * AccountMapper.toResponse calls AccountLastErrorJson.read unconditionally, and
 * that class holds a static ObjectMapper and TypeReference — so mapping a
 * single account is what class-initialises it, whether or not last_error is
 * set. Over an empty table listAllAccounts maps nothing, the class is never
 * loaded, and a dependency missing behind it has nowhere to show.
 *
 * That is not hypothetical. It is how #393 shipped: the packaging check did
 * probe /v1/accounts and did get 200 — over an empty list — while the packaged
 * sidecar died with NoClassDefFoundError on the first account a real user had.
 * ArchitectureTest.productionCodeDoesNotUseJackson2 now guards that particular
 * import; this guards the general case, which is any dependency the fat jar
 * excludes and production code still reaches.
 *
 * Offline by construction: createAccount saves, encrypts the credentials and
 * maps — it opens no connection (that is /test-connection), nothing here
 * triggers a sync, and the hosts are .invalid, which RFC 2606 reserves as
 * guaranteed not to resolve. The row dies with the temporary data dir.
 */
const SMOKE_ACCOUNT = {
	accountName: 'Packaging smoke',
	email: 'smoke@voxrox.invalid',
	username: 'smoke@voxrox.invalid',
	password: 'not-a-real-password',
	imap: { host: 'imap.voxrox.invalid', port: 993, useSsl: true },
	smtp: { host: 'smtp.voxrox.invalid', port: 465, useSsl: true }
};

async function assertMappedAccount(session) {
	const created = await send(`${session.baseUrl}/v1/accounts`, {
		method: 'POST',
		origin: WEBVIEW_ORIGIN,
		apiKey: session.apiKey,
		timeoutMs: 10_000,
		body: SMOKE_ACCOUNT
	});
	if (created.status !== 201) {
		throw new Error(
			`POST /v1/accounts returned HTTP ${created.status} (expected 201) through the packaged ` +
				`sidecar. Body: ${created.body.slice(0, 400)}`
		);
	}

	let account;
	try {
		account = JSON.parse(created.body);
	} catch {
		throw new Error(
			`POST /v1/accounts returned a non-JSON body through the packaged sidecar ` +
				`(AccountMapper / AOT serialization regression?): ${created.body.slice(0, 400)}`
		);
	}
	if (account.email !== SMOKE_ACCOUNT.email) {
		throw new Error(
			`POST /v1/accounts mapped the wrong account: expected email ${SMOKE_ACCOUNT.email}, ` +
				`got ${account.email ?? '<none>'}.`
		);
	}

	// Again through the list path, which is the one a user's first sync hits and
	// the one that mapped nothing while the table was empty.
	const listed = await probe(`${session.baseUrl}/v1/accounts`, {
		origin: WEBVIEW_ORIGIN,
		apiKey: session.apiKey,
		timeoutMs: 5_000
	});
	if (listed.status < 200 || listed.status >= 300) {
		throw new Error(
			`GET /v1/accounts returned HTTP ${listed.status} (expected 2xx) once an account existed.`
		);
	}
	const accounts = JSON.parse(listed.body);
	if (!Array.isArray(accounts) || accounts.length !== 1) {
		throw new Error(
			`GET /v1/accounts returned ${JSON.stringify(accounts).slice(0, 200)}; expected exactly ` +
				`the one account this smoke created.`
		);
	}

	return `POST → 201, GET → ${listed.status} over ${accounts.length} account`;
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

const dataDir = await mkdtemp(path.join(os.tmpdir(), 'voxrox-sidecar-smoke-'));
const sessionPath = path.join(dataDir, 'session.json');

console.log(`Packaged sidecar smoke: launching ${exePath}`);
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
	const endpoints = await assertBootEndpoints(session);
	const mapped = await assertMappedAccount(session);
	console.log(
		`OK — webview origin ${WEBVIEW_ORIGIN} → ${result.webviewStatus} ` +
			`(Access-Control-Allow-Origin ${result.allowOriginHeader}); foreign origin → ${result.foreignStatus}.`
	);
	console.log(`OK - boot endpoints through ${WEBVIEW_ORIGIN}: ${endpoints.join(', ')}.`);
	console.log(`OK - account mapped through the packaged sidecar: ${mapped}.`);
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
