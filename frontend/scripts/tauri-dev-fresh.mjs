/**
 * Pre-clean Tauri dev WebView2 cache + backend session, then run tauri:dev.
 *
 * The dev run uses a parallel data root `%LOCALAPPDATA%\VoxRox\Mail.dev` (see
 * tauri-dev-with-env.mjs, MAIL_DATA_SUFFIX=.dev), separated from the production
 * `%LOCALAPPDATA%\VoxRox\Mail`. WebView2 storage lives in `Mail.dev\webview`,
 * the backend session.json/.ready in the root of `Mail.dev`. The dev workflow
 * can accumulate stale state across backend rebuilds (e.g. when a test or a
 * manual tweak writes something to localStorage). This script wipes it
 * proactively before startup.
 *
 * Safe to delete (only in the dev root):
 *   - WebView2 Local Storage (UI preferences fall back to defaults)
 *   - session.json and .ready (the backend rewrites them on startup)
 *
 * DO NOT DELETE (user data):
 *   - db/ (SQLite — accounts, messages, drafts)
 *   - attachments/
 *   - crypto.bin (deterministic key)
 *   - logs/
 */

import { spawnSync } from 'node:child_process';
import { rm, stat } from 'node:fs/promises';
import path from 'node:path';
import process from 'node:process';
import { resolveMailDataDir } from './lib/data-dirs.mjs';
import { run } from './lib/run.mjs';

const rootDir = process.cwd();
const tauriDevScript = path.join(rootDir, 'scripts', 'tauri-dev-with-env.mjs');
const passThroughArgs = process.argv.slice(2);

async function pathExists(target) {
	try {
		await stat(target);
		return true;
	} catch {
		return false;
	}
}

async function removeIfExists(target) {
	if (!(await pathExists(target))) {
		console.log(`[dev:fresh] skipped (does not exist): ${target}`);
		return;
	}
	await rm(target, { recursive: true, force: true });
	console.log(`[dev:fresh] removed: ${target}`);
}

function killWindowsTauriProcesses() {
	if (process.platform !== 'win32') return;

	/*
	 * Kill leftover dev processes (Tauri app.exe + jpackage sidecar mail.exe)
	 * matched by command line, so only binaries started from
	 * src-tauri\target\debug are hit — never a production install. wmic was
	 * removed from current Windows 11 builds, so query Win32_Process through
	 * PowerShell CIM instead; prefer pwsh and fall back to Windows PowerShell
	 * on machines without PowerShell 7.
	 */
	// WQL: backslash is the escape character, so \\ matches one literal backslash.
	const filter = 'CommandLine LIKE "%src-tauri\\\\target\\\\debug%"';
	const psCommand = `Get-CimInstance Win32_Process -Filter '${filter}' | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }`;

	for (const shell of ['pwsh', 'powershell']) {
		const result = spawnSync(shell, ['-NoProfile', '-NonInteractive', '-Command', psCommand], {
			stdio: 'ignore',
			windowsHide: true
		});
		if (!result.error) {
			console.log('[dev:fresh] killed leftover Tauri dev processes (if any)');
			return;
		}
	}
	console.log(
		'[dev:fresh] WARNING: pwsh/powershell not found; leftover dev processes were not killed.'
	);
}

async function main() {
	const devSuffix = process.env.MAIL_DATA_SUFFIX || '.dev';
	const devDataDir = resolveMailDataDir(devSuffix);
	const webviewRoot = path.join(devDataDir, 'webview', 'Default');

	killWindowsTauriProcesses();

	await removeIfExists(path.join(webviewRoot, 'Local Storage'));
	await removeIfExists(path.join(webviewRoot, 'Session Storage'));
	await removeIfExists(path.join(devDataDir, 'session.json'));
	await removeIfExists(path.join(devDataDir, '.ready'));

	console.log('[dev:fresh] cache cleared, starting tauri:dev.');
	process.exitCode = await run(process.execPath, [tauriDevScript, ...passThroughArgs], {
		label: 'tauri:dev',
		cwd: rootDir
	});
}

main().catch((error) => {
	console.error(error instanceof Error ? error.message : String(error));
	process.exitCode = 1;
});
