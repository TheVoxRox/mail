import { spawn } from 'node:child_process';
import { readFile } from 'node:fs/promises';
import path from 'node:path';
import process from 'node:process';

const rootDir = process.cwd();
const backendEnvPath = path.resolve(rootDir, '..', 'backend', '.env');
const tauriCliPath = path.join(rootDir, 'node_modules', '@tauri-apps', 'cli', 'tauri.js');
const includeBackendEnvCrypto =
	process.argv.includes('--include-backend-env-crypto') ||
	process.env.MAIL_TAURI_INCLUDE_BACKEND_ENV_CRYPTO === '1';
const passThroughArgs = process.argv
	.slice(2)
	.filter((arg) => arg !== '--include-backend-env-crypto');
const desktopFilteredEnvNames = new Set(['MAIL_CRYPTO_KEY', 'MAIL_CRYPTO_SALT']);
/*
 * The dev run is isolated from production via a parallel data root
 * `%LOCALAPPDATA%\VoxRox\Mail.dev`, not by rewriting the bundle identifier.
 * `MAIL_DATA_SUFFIX` is read by src-tauri/src/lib.rs (Rust side);
 * `VITE_MAIL_DATA_SUFFIX` is read by the frontend via import.meta.env (see
 * src/lib/backend/data-dir.ts). The two values must match.
 */
const devDataSuffix = process.env.MAIL_DATA_SUFFIX || '.dev';

function parseDotEnv(raw) {
	const values = {};

	for (const rawLine of raw.split(/\r?\n/)) {
		const line = rawLine.trim();
		if (!line || line.startsWith('#')) continue;

		const match = line.match(/^([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)$/);
		if (!match) continue;

		const [, name, rawValue] = match;
		let value = rawValue.trim();
		if (
			(value.startsWith('"') && value.endsWith('"')) ||
			(value.startsWith("'") && value.endsWith("'"))
		) {
			value = value.slice(1, -1);
		}
		values[name] = value;
	}

	return values;
}

async function loadBackendEnv() {
	try {
		const raw = await readFile(backendEnvPath, 'utf8');
		return parseDotEnv(raw);
	} catch (error) {
		throw new Error(
			`Failed to read backend/.env from ${backendEnvPath}. Copy backend/.env.example to backend/.env and fill in the values.`,
			{ cause: error }
		);
	}
}

function envForDesktopSidecar(values) {
	if (includeBackendEnvCrypto) {
		return values;
	}

	return Object.fromEntries(
		Object.entries(values).filter(([name]) => !desktopFilteredEnvNames.has(name))
	);
}

function withDevTauriConfig(env) {
	const baseConfig = env.TAURI_CONFIG ? JSON.parse(env.TAURI_CONFIG) : {};
	return {
		...env,
		MAIL_DATA_SUFFIX: devDataSuffix,
		VITE_MAIL_DATA_SUFFIX: devDataSuffix,
		TAURI_CONFIG: JSON.stringify({
			...baseConfig,
			productName: baseConfig.productName ?? 'Mail Dev'
		})
	};
}

function spawnTauri(env) {
	return new Promise((resolve, reject) => {
		const child = spawn(process.execPath, [tauriCliPath, 'dev', ...passThroughArgs], {
			cwd: rootDir,
			env,
			stdio: 'inherit',
			windowsHide: true
		});

		child.on('error', (error) => {
			reject(new Error(`tauri dev failed to start: ${error.message}`));
		});
		child.on('exit', (code, signal) => {
			if (signal) {
				reject(new Error(`tauri dev exited with signal ${signal}`));
				return;
			}
			resolve(code ?? 0);
		});
	});
}

async function main() {
	const backendEnv = await loadBackendEnv();
	const sidecarEnv = envForDesktopSidecar(backendEnv);
	const env = withDevTauriConfig({ ...process.env, ...sidecarEnv });
	const loadedNames = Object.keys(sidecarEnv).sort();
	const skippedNames = Object.keys(backendEnv)
		.filter((name) => !Object.hasOwn(sidecarEnv, name))
		.sort();

	console.log(`[tauri-dev] Loaded ${loadedNames.length} variables from backend/.env.`);
	if (skippedNames.length > 0) {
		console.log(
			`[tauri-dev] Desktop crypto uses app data bootstrap; not passing through: ${skippedNames.join(', ')}.`
		);
	}
	console.log(
		`[tauri-dev] Dev data root suffix: ${env.MAIL_DATA_SUFFIX} (VoxRox/Mail${env.MAIL_DATA_SUFFIX}).`
	);
	console.log('[tauri-dev] Starting tauri dev with backend env for the sidecar.');

	const exitCode = await spawnTauri(env);
	process.exitCode = exitCode;
}

main().catch((error) => {
	console.error(error instanceof Error ? error.message : String(error));
	process.exitCode = 1;
});
