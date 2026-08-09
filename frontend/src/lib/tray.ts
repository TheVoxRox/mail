/**
 * Bridge between the webview and the native tray icon.
 *
 * The tray itself lives in Rust (`src-tauri/src/tray.rs`) because the icon,
 * the menu and the window-close hook are native objects. What Rust does not
 * have is the i18n bundle or the unread count, so this module owns both: it
 * pushes localized menu labels and a tooltip down, and it handles the two menu
 * actions that are really webview concerns (open the composer, sync now).
 *
 * Every entry point is a no-op outside the desktop shell, so `npm run dev` in
 * a browser and the Playwright suites never reach an `invoke`.
 */

import { browser } from '$app/environment';
import { invoke, isTauri } from '@tauri-apps/api/core';
import { listen, type UnlistenFn } from '@tauri-apps/api/event';
import { derived, get } from 'svelte/store';
import { _ } from '$lib/i18n/index.js';
import { folders } from '$lib/stores/folders.js';
import { closeAction } from '$lib/stores/uiLayout.js';

const TRAY_ACTION_EVENT = 'tray://action';

/**
 * Structurally compatible with `MessageFormatter` from svelte-i18n (`$_` /
 * `get(_)`) — same narrowing the other pure helpers in `$lib/mail` use.
 */
type TranslateFn = (id: string, options?: { values?: Record<string, number | string> }) => string;

interface TrayLabels {
	open: string;
	compose: string;
	sync: string;
	quit: string;
	tooltip: string;
}

let started = false;
let teardown: Array<() => void> = [];
/** Menu labels last sent to Rust, so a tooltip change does not rebuild the native menu. */
let lastMenuKey: string | null = null;
let lastTooltip: string | null = null;

/**
 * Mirrors the guard in `$lib/updates.ts`: the Tauri API is importable in every
 * build, so availability — not the import — is what decides whether an
 * `invoke` happens.
 */
function trayAvailable(): boolean {
	return browser && isTauri() && import.meta.env.VITE_E2E_MOCK !== '1';
}

/**
 * Unread messages shown in the tooltip.
 *
 * Deliberately the active account's Inbox only: the folder store holds the
 * active account, so any "all accounts" number would either be invented or
 * need a fan-out of requests on every sync. The tooltip string names the
 * mailbox it counts, so the number is not read as more than it is.
 */
function inboxUnread(): number {
	return get(folders).find((folder) => folder.role === 'INBOX')?.unreadCount ?? 0;
}

function buildLabels(t: TranslateFn): TrayLabels {
	const unread = inboxUnread();
	return {
		open: t('tray.menuOpen'),
		compose: t('tray.menuCompose'),
		sync: t('tray.menuSync'),
		quit: t('tray.menuQuit'),
		tooltip: unread > 0 ? t('tray.tooltipUnread', { values: { count: unread } }) : t('tray.tooltip')
	};
}

async function applyLabels(labels: TrayLabels): Promise<void> {
	// JSON rather than a joined string: a separator character can also occur
	// inside a translated label, and two different label sets that happen to
	// join to the same text would skip a menu rebuild the locale switch needed.
	const menuKey = JSON.stringify([labels.open, labels.compose, labels.sync, labels.quit]);

	if (menuKey !== lastMenuKey) {
		lastMenuKey = menuKey;
		lastTooltip = labels.tooltip;
		await invoke('configure_tray', { labels });
		return;
	}
	if (labels.tooltip !== lastTooltip) {
		lastTooltip = labels.tooltip;
		await invoke('set_tray_tooltip', { tooltip: labels.tooltip });
	}
}

async function runTrayAction(action: string): Promise<void> {
	// Imported lazily: these pull in the router and the mail stores, which the
	// boot path should not have to load just to register a tray listener.
	const { goToCompose, syncCurrentAccount } = await import('$lib/mail/actions.js');
	if (action === 'compose') {
		await goToCompose();
	} else if (action === 'sync') {
		await syncCurrentAccount();
	}
}

/**
 * Wires the tray up once the app has booted. Safe to call repeatedly; only the
 * first call subscribes.
 */
export function startTray(): void {
	if (started || !trayAvailable()) return;
	started = true;

	teardown.push(
		closeAction.subscribe((action) => {
			void invoke('set_close_behavior', { hideToTray: action === 'tray' }).catch((err) =>
				console.warn('[tray] failed to push the close behaviour', err)
			);
		})
	);

	// `_` re-emits on locale change and `folders` on every sync, so this single
	// subscription covers both the menu and the unread tooltip.
	teardown.push(
		derived([_, folders], ([$t]) => buildLabels($t)).subscribe((labels) => {
			void applyLabels(labels).catch((err) => console.warn('[tray] failed to update', err));
		})
	);

	void listen<string>(TRAY_ACTION_EVENT, (event) => {
		void runTrayAction(event.payload).catch((err) =>
			console.warn('[tray] action failed', event.payload, err)
		);
	})
		.then((unlisten: UnlistenFn) => {
			teardown.push(unlisten);
		})
		.catch((err) => console.warn('[tray] failed to listen for menu actions', err));
}

/**
 * Test seam. Production never tears the tray down — the app owns it for its
 * whole life — but the unit tests re-import the module per case, and a
 * subscription left on the (module-independent) folder store would keep
 * invoking from an earlier test's instance.
 */
export function stopTrayForTests(): void {
	for (const off of teardown) off();
	teardown = [];
	started = false;
	lastMenuKey = null;
	lastTooltip = null;
}
