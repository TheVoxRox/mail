/**
 * Update channel preference (stable / beta).
 *
 * Persisted in `localStorage` under the key `mail.updateChannel`. The value is
 * passed to the Tauri `check_for_update` command, which maps it onto the
 * matching updater endpoint (see frontend/src-tauri/src/lib.rs) — the webview
 * never handles endpoint URLs itself.
 */

import { browser } from '$app/environment';
import { writable } from 'svelte/store';

export type UpdateChannel = 'stable' | 'beta';

const STORAGE_KEY = 'mail.updateChannel';
const DEFAULT_CHANNEL: UpdateChannel = 'stable';

function readInitial(): UpdateChannel {
	if (!browser) return DEFAULT_CHANNEL;
	try {
		const stored = window.localStorage.getItem(STORAGE_KEY);
		if (stored === 'stable' || stored === 'beta') return stored;
	} catch {
		// localStorage unavailable – private mode etc.
	}
	return DEFAULT_CHANNEL;
}

export const updateChannel = writable<UpdateChannel>(readInitial());

export function setUpdateChannel(next: UpdateChannel): void {
	updateChannel.set(next);
	if (browser) {
		try {
			window.localStorage.setItem(STORAGE_KEY, next);
		} catch {
			// ignore
		}
	}
}
