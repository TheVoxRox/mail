/**
 * Update channel preference (stable / beta).
 *
 * Persisted in `localStorage` under the key `mail.updateChannel`. The value is
 * passed to the Tauri `check_for_update` command, which maps it onto the
 * matching updater endpoint (see frontend/src-tauri/src/lib.rs) — the webview
 * never handles endpoint URLs itself.
 */

import { persistedStore } from './persisted.js';

/**
 * Single source of truth for the channel set: the type, the persisted-value
 * validation, and the Settings dropdown all derive from this list. The Rust
 * side (beta_endpoint_override in lib.rs) still validates independently —
 * that match is the IPC trust boundary and must not widen just because the
 * webview learned a new name.
 */
export const UPDATE_CHANNELS = ['stable', 'beta'] as const;
export type UpdateChannel = (typeof UPDATE_CHANNELS)[number];

export const updateChannel = persistedStore<UpdateChannel>(
	'mail.updateChannel',
	UPDATE_CHANNELS,
	'stable'
);

export function setUpdateChannel(next: UpdateChannel): void {
	updateChannel.set(next);
}
