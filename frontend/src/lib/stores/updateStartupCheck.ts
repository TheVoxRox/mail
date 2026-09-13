/**
 * Whether the application checks for updates on its own when it starts.
 *
 * Persisted in `localStorage` under the key `mail.updateStartupCheck`. On by
 * default; turning it off leaves only the manual check in Settings → About,
 * so the application then contacts GitHub only when the user asks it to.
 * PRIVACY.md describes both states.
 */

import { persistedStore } from './persisted.js';

const STARTUP_CHECK_STATES = ['on', 'off'] as const;
type StartupCheckState = (typeof STARTUP_CHECK_STATES)[number];

export const updateStartupCheck = persistedStore<StartupCheckState>(
	'mail.updateStartupCheck',
	STARTUP_CHECK_STATES,
	'on'
);

export function setUpdateStartupCheck(enabled: boolean): void {
	updateStartupCheck.set(enabled ? 'on' : 'off');
}
