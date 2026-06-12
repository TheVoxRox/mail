import os from 'node:os';
import path from 'node:path';
import process from 'node:process';

function resolvePlatformDataRoot() {
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

/**
 * App data root shared by the Tauri app and the backend sidecar
 * (`%LOCALAPPDATA%\VoxRox\Mail` on Windows). `suffix` selects a parallel
 * root — tauri:dev isolates itself from production via '.dev' (see
 * tauri-dev-with-env.mjs).
 */
export function resolveMailDataDir(suffix = '') {
	return path.join(resolvePlatformDataRoot(), 'VoxRox', `Mail${suffix}`);
}
