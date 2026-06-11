/**
 * HTTP wrapper: under Tauri uses `@tauri-apps/plugin-http` (bypasses CORS
 * for the webview origin `http://tauri.localhost`); in a regular browser
 * falls back to `window.fetch` (for `vite dev` on `http://localhost:5173`).
 *
 * plugin-http exposes the same API as `fetch`, so callers do not change.
 */

import { fetch as tauriFetch } from '@tauri-apps/plugin-http';

function isTauri(): boolean {
	// Under Tauri v2 `window.__TAURI_INTERNALS__` is injected.
	return typeof window !== 'undefined' && '__TAURI_INTERNALS__' in window;
}

export const httpFetch: typeof fetch = (input, init) => {
	if (isTauri()) {
		return tauriFetch(input as RequestInfo, init as RequestInit);
	}
	return window.fetch(input, init);
};
