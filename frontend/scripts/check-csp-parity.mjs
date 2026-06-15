/**
 * Guards against silent dev/prod CSP drift.
 *
 * tauri.conf.json carries two Content-Security-Policy blocks:
 * `app.security.csp` (packaged build) and `app.security.devCsp` (`tauri dev`).
 * They are kept IDENTICAL on purpose so a `tauri dev` run enforces exactly the
 * production CSP and surfaces any violation before release — the same reasoning
 * behind the SecurityConfig CORS allowlist + the sidecar CORS smoke. If they
 * drift, a resource allowed in dev can be blocked only in the installed app
 * (the dev/prod divergence class this guards against).
 *
 * If a future change MUST make them differ (e.g. an HMR websocket host needed
 * only in dev), update this check deliberately so the divergence is a reviewed
 * decision, not an accident.
 */

import { readFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const scriptDir = path.dirname(fileURLToPath(import.meta.url));
const configPath = path.resolve(scriptDir, '..', 'src-tauri', 'tauri.conf.json');

/** Order-independent serialization so directive-map key order never matters. */
function canonical(value) {
	if (value === null || typeof value !== 'object') {
		return JSON.stringify(value ?? null);
	}
	if (Array.isArray(value)) {
		return `[${value.map(canonical).join(',')}]`;
	}
	const keys = Object.keys(value).sort();
	return `{${keys.map((key) => `${JSON.stringify(key)}:${canonical(value[key])}`).join(',')}}`;
}

function describeDiff(a, b) {
	if (typeof a !== 'object' || typeof b !== 'object' || a === null || b === null) {
		return `  csp:    ${JSON.stringify(a ?? null)}\n  devCsp: ${JSON.stringify(b ?? null)}`;
	}
	const keys = [...new Set([...Object.keys(a), ...Object.keys(b)])].sort();
	return keys
		.filter((key) => canonical(a[key]) !== canonical(b[key]))
		.map(
			(key) =>
				`  ${key}: csp=${JSON.stringify(a[key] ?? null)} devCsp=${JSON.stringify(b[key] ?? null)}`
		)
		.join('\n');
}

const config = JSON.parse(await readFile(configPath, 'utf8'));
const security = config?.app?.security ?? {};
const { csp, devCsp } = security;

if (csp == null || devCsp == null) {
	throw new Error(
		`tauri.conf.json must define both app.security.csp and app.security.devCsp ` +
			`(csp=${csp == null ? 'missing' : 'present'}, devCsp=${devCsp == null ? 'missing' : 'present'}).`
	);
}

if (canonical(csp) !== canonical(devCsp)) {
	throw new Error(
		`tauri.conf.json: app.security.csp and devCsp have drifted. They are kept identical so ` +
			`\`tauri dev\` enforces the production CSP and catches violations before release.\n` +
			`${describeDiff(csp, devCsp)}\n` +
			`If the divergence is intentional, update scripts/check-csp-parity.mjs.`
	);
}

console.log('CSP parity OK: app.security.csp === app.security.devCsp');
