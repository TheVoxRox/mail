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

import { spawn, spawnSync } from 'node:child_process';
import { rm, stat } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import process from 'node:process';

const rootDir = process.cwd();
const tauriDevScript = path.join(rootDir, 'scripts', 'tauri-dev-with-env.mjs');
const passThroughArgs = process.argv.slice(2);

function resolveLocalAppData() {
	if (process.platform === 'win32') {
		const root = process.env.LOCALAPPDATA;
		if (!root) {
			throw new Error('LOCALAPPDATA env var is not available on Windows.');
		}
		return root;
	}
	if (process.platform === 'darwin') {
		return path.join(os.homedir(), 'Library', 'Application Support');
	}
	const xdg = process.env.XDG_DATA_HOME;
	return xdg && xdg.length > 0 ? xdg : path.join(os.homedir(), '.local', 'share');
}

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

	const targets = [
		// Tauri dev binary tree
		{ name: 'app.exe', condition: 'where CommandLine like "%src-tauri\\\\target\\\\debug%"' },
		// Backend sidecar (jpackage launcher + JVM)
		{ name: 'mail.exe', condition: 'where CommandLine like "%src-tauri\\\\target\\\\debug%"' }
	];

	for (const target of targets) {
		const result = spawnSync('wmic', ['process', target.condition, 'call', 'terminate'], {
			stdio: 'ignore',
			windowsHide: true,
			encoding: 'utf8'
		});
		// Return code ignored — wmic returns 0 even when it finds nothing.
		void result;
	}
	console.log('[dev:fresh] killed leftover Tauri dev processes (if any)');
}

function spawnTauriDev() {
	return new Promise((resolve, reject) => {
		const child = spawn(process.execPath, [tauriDevScript, ...passThroughArgs], {
			cwd: rootDir,
			stdio: 'inherit',
			windowsHide: true
		});

		child.on('error', (error) => {
			reject(new Error(`tauri:dev failed to start: ${error.message}`));
		});
		child.on('exit', (code, signal) => {
			if (signal) {
				reject(new Error(`tauri:dev exited with signal ${signal}`));
				return;
			}
			resolve(code ?? 0);
		});
	});
}

async function main() {
	const localAppData = resolveLocalAppData();
	const devSuffix = process.env.MAIL_DATA_SUFFIX || '.dev';
	const devDataDir = path.join(localAppData, 'VoxRox', `Mail${devSuffix}`);
	const webviewRoot = path.join(devDataDir, 'webview', 'Default');

	killWindowsTauriProcesses();

	await removeIfExists(path.join(webviewRoot, 'Local Storage'));
	await removeIfExists(path.join(webviewRoot, 'Session Storage'));
	await removeIfExists(path.join(devDataDir, 'session.json'));
	await removeIfExists(path.join(devDataDir, '.ready'));

	console.log('[dev:fresh] cache cleared, starting tauri:dev.');
	const exitCode = await spawnTauriDev();
	process.exitCode = exitCode;
}

main().catch((error) => {
	console.error(error instanceof Error ? error.message : String(error));
	process.exitCode = 1;
});
