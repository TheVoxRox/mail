/**
 * Whether the application checks for updates on its own when it starts.
 *
 * Persisted in `localStorage` under the key `mail.updateStartupCheck`, and
 * mirrored in the registry value the installer's privacy page records
 * (frontend/src-tauri/src/update_preference.rs). The installer can reach only
 * the registry, so an answer found there is adopted over the stored one before
 * the startup check, and the checkbox in Settings → About writes both. On by
 * default; turning it off leaves only the manual check in Settings → About, so
 * the application then contacts GitHub only when the user asks it to.
 * PRIVACY.md describes both states.
 */

import { browser } from '$app/environment';
import { invoke, isTauri } from '@tauri-apps/api/core';
import { persistedStore } from './persisted.js';

const STARTUP_CHECK_STATES = ['on', 'off'] as const;
type StartupCheckState = (typeof STARTUP_CHECK_STATES)[number];

export const updateStartupCheck = persistedStore<StartupCheckState>(
	'mail.updateStartupCheck',
	STARTUP_CHECK_STATES,
	'on'
);

/** The mocked e2e build runs in a browser with no shell and no registry behind it. */
function hasShell(): boolean {
	return browser && isTauri() && import.meta.env.VITE_E2E_MOCK !== '1';
}

export function setUpdateStartupCheck(enabled: boolean): void {
	updateStartupCheck.set(enabled ? 'on' : 'off');
	if (!hasShell()) return;
	void invoke('set_update_startup_check', { enabled }).catch((err: unknown) =>
		console.warn('[mail] failed to record the startup update check in the registry', err)
	);
}

/**
 * Adopts the answer recorded in the registry, if there is one. With nothing
 * recorded — a silent install, or an installation older than the installer's
 * question — the stored value, and so the default, stays in charge.
 */
export async function loadUpdateStartupCheck(): Promise<void> {
	if (!hasShell()) return;
	try {
		const recorded = await invoke<boolean | null>('get_update_startup_check');
		if (typeof recorded === 'boolean') {
			updateStartupCheck.set(recorded ? 'on' : 'off');
		}
	} catch (err) {
		console.warn('[mail] failed to read the startup update check from the registry', err);
	}
}
