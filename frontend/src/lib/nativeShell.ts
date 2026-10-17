import { browser } from '$app/env';
import { isTauri } from '@tauri-apps/api/core';

/**
 * Whether a Tauri shell stands behind the page, so its commands can be
 * invoked. The mocked e2e build runs in a browser with no shell, no tray, no
 * updater and no registry behind it, and says so through VITE_E2E_MOCK.
 */
export function hasNativeShell(): boolean {
	return browser && isTauri() && import.meta.env.VITE_E2E_MOCK !== '1';
}
