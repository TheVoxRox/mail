import path from 'node:path';
import process from 'node:process';
import { envForDesktopSidecar, loadBackendEnv } from './lib/dotenv.mjs';
import { run } from './lib/run.mjs';
import { requireUsableSidecar } from './lib/sidecar-staleness.mjs';

/*
 * Spelled with an upper-case drive letter whatever the shell handed over. A
 * shell can sit in `c:\...`, and tauri passes that on to the Vite dev server it
 * starts. Vite then resolves its root to the real `C:/...` while SvelteKit keeps
 * the lower-case working directory, the two serve SvelteKit's client under two
 * URLs, and the copy the app imports `goto` from was never started: every
 * button that navigates throws and does nothing (found as a dead Add account
 * on the accountless start screen). The check in vite.config.ts stops a dev
 * server started that way by any other route.
 */
const rootDir = process.cwd().replace(/^[a-z]:/, (drive) => drive.toUpperCase());
const tauriCliPath = path.join(rootDir, 'node_modules', '@tauri-apps', 'cli', 'tauri.js');
const includeBackendEnvCrypto =
	process.argv.includes('--include-backend-env-crypto') ||
	process.env.MAIL_TAURI_INCLUDE_BACKEND_ENV_CRYPTO === '1';
const passThroughArgs = process.argv
	.slice(2)
	.filter((arg) => arg !== '--include-backend-env-crypto');
/*
 * The dev run is isolated from production via a parallel data root
 * `%LOCALAPPDATA%\VoxRox\Mail.dev`, not by rewriting the bundle identifier.
 * `MAIL_DATA_SUFFIX` is read by src-tauri/src/lib.rs (Rust side);
 * `VITE_MAIL_DATA_SUFFIX` is read by the frontend via import.meta.env (see
 * src/lib/backend/data-dir.ts). The two values must match.
 */
const devDataSuffix = process.env.MAIL_DATA_SUFFIX || '.dev';

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

async function main() {
	// The policy, its opt-out and why are in lib/sidecar-staleness.mjs, shared
	// with tauri-build.mjs.
	await requireUsableSidecar(path.resolve(rootDir, '..'), 'tauri-dev');
	const backendEnv = await loadBackendEnv(
		'Copy backend/.env.example to backend/.env and fill in the values.'
	);
	const sidecarEnv = envForDesktopSidecar(backendEnv, includeBackendEnvCrypto);
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

	process.exitCode = await run(process.execPath, [tauriCliPath, 'dev', ...passThroughArgs], {
		label: 'tauri dev',
		cwd: rootDir,
		env
	});
}

main().catch((error) => {
	console.error(error instanceof Error ? error.message : String(error));
	process.exitCode = 1;
});
