/**
 * Windows notifications for mail that arrives while nobody is looking at the
 * window.
 *
 * The in-app toast and its live region reach no one while the window is in
 * the tray or behind another application — a screen reader reads the live
 * regions of the foreground window only. Rust (`src-tauri/src/new_mail.rs`)
 * shows the Windows notification and decides whether the window is being
 * looked at, since only it can ask the window; this side decides what the
 * notification says, because the i18n bundles live here, and whether the
 * user wants one at all.
 *
 * What it says is deliberately thin: how many messages, which account, which
 * folder. Windows keeps a notification in its notification centre and may
 * show it on the lock screen, where sender and subject would be read by
 * whoever stands at the machine (PRIVACY.md).
 *
 * A no-op outside the desktop shell, like every native bridge here.
 */

import { invoke } from '@tauri-apps/api/core';
import { get } from 'svelte/store';
import { hasNativeShell } from '#lib/nativeShell.js';
import { newMailNotifications } from '#lib/stores/uiLayout.js';

/**
 * Structurally compatible with `MessageFormatter` from svelte-i18n (`$_` /
 * `get(_)`) — same narrowing the other pure helpers use.
 */
type TranslateFn = (id: string, options?: { values?: Record<string, number | string> }) => string;

export interface ArrivedMail {
	/** Messages that arrived — `SyncNotification.arrivedMessagesCount`, not the download count. */
	count: number;
	account: string;
	folder: string;
}

/**
 * Asks Rust to announce arrived mail outside the window.
 *
 * @returns whether a Windows notification was shown. False when the shell is
 *   absent, the user turned these off, or the window has the focus.
 */
export async function notifyArrivedMail(mail: ArrivedMail, t: TranslateFn): Promise<boolean> {
	// Negated rather than `<= 0`: a sidecar that predates the field sends no
	// count at all, and undefined must read as "nothing arrived".
	if (!(mail.count > 0) || !hasNativeShell() || get(newMailNotifications) === 'off') return false;
	return invoke<boolean>('notify_new_mail', {
		title: t('newMailNotification.title'),
		body: t('newMailNotification.body', {
			values: { count: mail.count, account: mail.account, folder: mail.folder }
		})
	});
}
