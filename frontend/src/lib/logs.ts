import { invoke } from '@tauri-apps/api/core';

/**
 * Packs the logs folder into a ZIP in the Downloads folder and resolves to the
 * ZIP's path. The shell does it (src-tauri/src/logs.rs), not the backend, so it
 * works when the backend does not start: the one case the diagnostic dump
 * cannot cover. Only callable where `hasNativeShell()` holds.
 */
export function saveLogs(): Promise<string> {
	return invoke<string>('export_logs');
}
