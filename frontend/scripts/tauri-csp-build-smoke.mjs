/**
 * CSP smoke for the PACKAGED build (the `tauri dev` half is checkable by hand
 * via the devtools console; this covers the other half the parity check cannot
 * reach — see scripts/check-csp-parity.mjs).
 *
 * Why a build-specific smoke exists at all: tauri.conf.json sets
 * `script-src 'self'` with NO `'unsafe-inline'` and NO literal hash. The
 * SvelteKit fallback `index.html` carries exactly one inline bootstrap
 * <script> (it sets `__sveltekit_<hash>` then dynamic-imports start+app). For
 * that inline script to run, Tauri must hash it at build time and inject the
 * `'sha256-…'` into the packaged CSP. dev and build use DIFFERENT hash
 * injection paths, so a green dev console does not prove the packaged app is
 * green. If the build-time injection ever breaks, the bootstrap is CSP-blocked,
 * the SPA never starts, and the window stays blank — caught here.
 *
 * How: launch the release binary with WebView2 remote debugging enabled
 * (WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS — honoured by the WebView2 runtime
 * regardless of Tauri's release `devtools` feature), attach over CDP, register
 * a `securitypolicyviolation` listener at document-start, reload so the listener
 * is in place before the bootstrap runs, then assert: (1) zero CSP violations
 * (injected listener + Log-domain security entries), and (2) the bootstrap
 * actually executed and the SPA mounted (`__sveltekit_*` global present and the
 * content div has children).
 *
 * Usage:
 *   npm run tauri:build:with-sidecar      # produce the binary first
 *   node scripts/tauri-csp-build-smoke.mjs [--exe=…] [--port=9222]
 *                                          [--timeout-ms=60000] [--settle-ms=4000]
 *                                          [--include-backend-env-crypto] [--keep-open]
 */

import { createWriteStream, existsSync } from 'node:fs';
import { mkdir } from 'node:fs/promises';
import { spawn } from 'node:child_process';
import path from 'node:path';
import process from 'node:process';
import { envForDesktopSidecar, loadBackendEnv } from './lib/dotenv.mjs';
import { terminateProcessTree, waitForExit } from './lib/process-tree.mjs';
import { wait } from './lib/run.mjs';

const rootDir = process.cwd();
const targetDir = path.join(rootDir, 'target');

const args = new Map(
	process.argv
		.slice(2)
		.filter((arg) => arg.startsWith('--') && arg.includes('='))
		.map((arg) => {
			const [key, ...valueParts] = arg.slice(2).split('=');
			return [key, valueParts.join('=')];
		})
);

const port = positiveInt(args.get('port'), 9222);
const timeoutMs = positiveInt(args.get('timeout-ms'), 60_000);
const settleMs = positiveInt(args.get('settle-ms'), 4_000);
const keepOpen = process.argv.includes('--keep-open');
const includeBackendEnvCrypto = process.argv.includes('--include-backend-env-crypto');
const exePath = path.resolve(args.get('exe') ?? resolveDefaultReleaseExe());

function positiveInt(raw, fallback) {
	const value = Number.parseInt(raw ?? '', 10);
	return Number.isFinite(value) && value > 0 ? value : fallback;
}

function resolveDefaultReleaseExe() {
	const releaseDir = path.join(rootDir, 'src-tauri', 'target', 'release');
	// app.exe is the Tauri host (~18 MB); mail.exe in the same dir is the
	// backend sidecar (~0.5 MB) — never launch that one as the app.
	const candidates = [path.join(releaseDir, 'app.exe'), path.join(releaseDir, 'Mail.exe')];
	return candidates.find((candidate) => existsSync(candidate)) ?? candidates[0];
}

/** Detects a CSP violation reported through the Log domain (console "security" sink). */
function isCspLogEntry(entry) {
	if (!entry) return false;
	if (entry.source === 'security') return true;
	const text = entry.text ?? '';
	return /content security policy|refused to (load|execute|apply|connect|frame|run)/i.test(text);
}

/** Minimal CDP client over the Node global WebSocket. */
class CdpSession {
	constructor(ws) {
		this.ws = ws;
		this.nextId = 0;
		this.pending = new Map();
		this.handlers = [];
		ws.addEventListener('message', (event) => {
			const message = JSON.parse(event.data);
			if (message.id != null && this.pending.has(message.id)) {
				const { resolve, reject } = this.pending.get(message.id);
				this.pending.delete(message.id);
				if (message.error) reject(new Error(message.error.message));
				else resolve(message.result);
			} else if (message.method) {
				for (const handler of this.handlers) handler(message);
			}
		});
	}

	send(method, params = {}) {
		const id = ++this.nextId;
		return new Promise((resolve, reject) => {
			this.pending.set(id, { resolve, reject });
			this.ws.send(JSON.stringify({ id, method, params }));
		});
	}

	on(handler) {
		this.handlers.push(handler);
	}
}

async function fetchPageTarget() {
	for (const host of ['127.0.0.1', 'localhost']) {
		try {
			const response = await fetch(`http://${host}:${port}/json/list`);
			if (!response.ok) continue;
			const targets = await response.json();
			const page = targets.find((t) => t.type === 'page' && t.webSocketDebuggerUrl);
			if (page) return page;
		} catch {
			// endpoint not up yet
		}
	}
	return null;
}

async function waitForPageTarget(child, deadline) {
	while (Date.now() < deadline) {
		if (child.exitCode !== null) {
			throw new Error(
				`Release app exited before the WebView was reachable (code ${child.exitCode}).`
			);
		}
		const target = await fetchPageTarget();
		if (target) return target;
		await wait(300);
	}
	throw new Error(
		`Timed out after ${timeoutMs} ms waiting for a CDP page target on port ${port}. ` +
			`WebView2 remote debugging may not have started.`
	);
}

function connect(wsUrl) {
	return new Promise((resolve, reject) => {
		const ws = new WebSocket(wsUrl);
		ws.addEventListener('open', () => resolve(ws), { once: true });
		ws.addEventListener('error', (event) => reject(event.error ?? new Error('WebSocket error')), {
			once: true
		});
	});
}

const INJECTED_LISTENER = `
	window.__cspViolations = window.__cspViolations || [];
	document.addEventListener('securitypolicyviolation', (e) => {
		window.__cspViolations.push({
			directive: e.effectiveDirective || e.violatedDirective,
			blockedURI: e.blockedURI,
			line: e.lineNumber,
			column: e.columnNumber,
			source: e.sourceFile,
			sample: e.sample
		});
	});
`;

const PROBE_EXPRESSION = `(() => {
	const skKeys = Object.keys(window).filter((k) => k.startsWith('__sveltekit_'));
	const contentDiv = document.querySelector('div[style="display: contents"]') || document.body;
	return {
		inlineScriptRan: skKeys.length > 0,
		skKeys,
		bodyChildren: contentDiv ? contentDiv.childElementCount : 0,
		bodyTextLength: (document.body.innerText || '').trim().length,
		cspViolations: window.__cspViolations || [],
		url: location.href,
		title: document.title
	};
})()`;

await mkdir(targetDir, { recursive: true });

if (!existsSync(exePath)) {
	throw new Error(
		`Release executable not found: ${exePath}. Run npm run tauri:build:with-sidecar first.`
	);
}

const backendEnv = envForDesktopSidecar(
	await loadBackendEnv('CSP build smoke needs the same backend env as tauri:dev.'),
	includeBackendEnvCrypto
);

const stamp = new Date().toISOString().replace(/[:.]/g, '-');
const stdoutPath = path.join(targetDir, `tauri-csp-build-smoke-${stamp}.stdout.log`);
const stderrPath = path.join(targetDir, `tauri-csp-build-smoke-${stamp}.stderr.log`);
const stdout = createWriteStream(stdoutPath, { flags: 'w' });
const stderr = createWriteStream(stderrPath, { flags: 'w' });

console.log(`CSP build smoke: ${exePath}`);
console.log(`Remote debugging port: ${port}`);
console.log(`stdout: ${stdoutPath}`);

const child = spawn(exePath, [], {
	cwd: path.dirname(exePath),
	env: {
		...process.env,
		...backendEnv,
		VITE_ENABLE_AUTO_UPDATE_CHECK: process.env.VITE_ENABLE_AUTO_UPDATE_CHECK ?? '0',
		WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS: `--remote-debugging-port=${port} ${
			process.env.WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS ?? ''
		}`.trim()
	},
	windowsHide: false
});
child.stdout?.pipe(stdout);
child.stderr?.pipe(stderr);

const logViolations = [];
const exceptions = [];

try {
	const target = await waitForPageTarget(child, Date.now() + timeoutMs);
	console.log(`Attached to page target: ${target.url}`);

	const ws = await connect(target.webSocketDebuggerUrl);
	const cdp = new CdpSession(ws);

	let loadFired = false;
	cdp.on((message) => {
		if (message.method === 'Log.entryAdded' && isCspLogEntry(message.params?.entry)) {
			logViolations.push(message.params.entry);
		} else if (message.method === 'Runtime.consoleAPICalled') {
			const text = (message.params?.args ?? [])
				.map((a) => a.value ?? a.description ?? '')
				.join(' ');
			if (message.params?.type === 'error' && isCspLogEntry({ text })) {
				logViolations.push({ source: 'console', level: 'error', text });
			}
		} else if (message.method === 'Runtime.exceptionThrown') {
			exceptions.push(message.params?.exceptionDetails?.text ?? 'exception');
		} else if (message.method === 'Page.loadEventFired') {
			loadFired = true;
		}
	});

	await cdp.send('Page.enable');
	await cdp.send('Runtime.enable');
	await cdp.send('Log.enable');
	await cdp.send('Page.addScriptToEvaluateOnNewDocument', { source: INJECTED_LISTENER });

	// Reload so the document-start listener is registered before the inline
	// bootstrap <script> runs — otherwise a violation on first paint is missed.
	loadFired = false;
	await cdp.send('Page.reload', { ignoreCache: true });

	const loadDeadline = Date.now() + 15_000;
	while (!loadFired && Date.now() < loadDeadline) {
		await wait(100);
	}
	// Let hydration + any async (connect-src/img-src) violations surface.
	await wait(settleMs);

	const probe = await cdp.send('Runtime.evaluate', {
		expression: PROBE_EXPRESSION,
		returnByValue: true
	});
	const state = probe.result?.value ?? {};

	const violations = [...(state.cspViolations ?? []), ...logViolations];
	const hydrated = Boolean(state.inlineScriptRan) && (state.bodyChildren ?? 0) > 0;

	console.log('');
	console.log(`URL:                 ${state.url ?? '(unknown)'}`);
	console.log(
		`Inline bootstrap ran: ${state.inlineScriptRan ? 'yes' : 'NO'} (${(state.skKeys ?? []).join(', ') || 'no __sveltekit_* global'})`
	);
	console.log(
		`SPA mounted:          ${(state.bodyChildren ?? 0) > 0 ? 'yes' : 'NO'} (content div children: ${state.bodyChildren ?? 0}, body text: ${state.bodyTextLength ?? 0} chars)`
	);
	console.log(`CSP violations:       ${violations.length}`);
	for (const v of violations) {
		console.log(
			`  - ${v.directive ?? v.source ?? 'security'}: ${v.blockedURI ?? v.text ?? JSON.stringify(v)}`
		);
	}
	if (exceptions.length > 0) {
		console.log(`JS exceptions (non-fatal to this check): ${exceptions.length}`);
		for (const e of exceptions) console.log(`  - ${e}`);
	}

	if (keepOpen) {
		console.log('\n--keep-open set; leaving the app running. Ctrl+C to stop.');
		await waitForExit(child);
		process.exitCode = 0;
	} else {
		await terminateProcessTree(child);
		await waitForExit(child).catch(() => null);

		if (violations.length === 0 && hydrated) {
			console.log(
				'\nPASS: packaged CSP enforced, inline bootstrap hash injected, SPA mounted clean.'
			);
			process.exitCode = 0;
		} else {
			console.error('\nFAIL: see violations / hydration status above.');
			process.exitCode = 1;
		}
	}
} catch (error) {
	await terminateProcessTree(child).catch(() => null);
	await waitForExit(child).catch(() => null);
	console.error(`\nCSP build smoke errored: ${error.message}`);
	process.exitCode = 1;
} finally {
	stdout.end();
	stderr.end();
}
