import { join, localDataDir } from '@tauri-apps/api/path';

/*
 * The suffix must stay in sync with `MAIL_DATA_SUFFIX` in src-tauri/src/lib.rs.
 * During dev runs scripts/tauri-dev-with-env.mjs sets the value to `.dev` so
 * that the frontend and backend both point at the parallel root directory
 * `VoxRox/Mail.dev`, separate from the production install `VoxRox/Mail`. The
 * production build does not set the env var → suffix is empty.
 */
const SUFFIX = import.meta.env.VITE_MAIL_DATA_SUFFIX ?? '';

/*
 * Cached across the session — the data root is fixed for the process lifetime
 * (Tauri install dir does not move at runtime), and bootstrap.ts calls this 3×
 * during a single boot (spawn sidecar, resolve session.json path, resolve
 * .ready path). Memoizing eliminates two redundant IPC roundtrips
 * (localDataDir + join) on the boot critical path.
 */
let cached: Promise<string> | null = null;

export function mailDataDir(): Promise<string> {
	if (cached !== null) return cached;
	cached = (async () => {
		try {
			return await join(await localDataDir(), 'VoxRox', `Mail${SUFFIX}`);
		} catch (err) {
			cached = null;
			throw err;
		}
	})();
	return cached;
}
