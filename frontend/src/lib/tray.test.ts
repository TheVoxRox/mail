// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

/*
 * tray.ts talks to the Rust side over invoke and to the shell over listen, so
 * both are mocked — no IPC ever runs here. The Rust commands it calls are
 * defined in frontend/src-tauri/src/tray.rs.
 *
 * What is worth testing on this side is exactly what Rust cannot check: that
 * nothing is invoked outside the desktop shell, that the close preference
 * reaches Rust as a boolean, and that a changing unread count updates the
 * tooltip without rebuilding the native menu.
 */
const { browserMock, isTauriMock, invokeMock, listenMock, foldersStore } = vi.hoisted(() => {
	// eslint-disable-next-line @typescript-eslint/no-require-imports
	const { writable } = require('svelte/store');
	return {
		browserMock: { value: true },
		isTauriMock: vi.fn<() => boolean>(),
		invokeMock: vi.fn(),
		listenMock: vi.fn(),
		foldersStore: writable([])
	};
});

vi.mock('$app/environment', () => ({
	get browser() {
		return browserMock.value;
	},
	dev: false,
	building: false,
	version: 'test'
}));
vi.mock('@tauri-apps/api/core', () => ({ isTauri: isTauriMock, invoke: invokeMock }));
vi.mock('@tauri-apps/api/event', () => ({ listen: listenMock }));
vi.mock('$lib/stores/folders.js', () => ({ folders: foldersStore }));

type TrayModule = typeof import('./tray.js');

/**
 * The folder store is hoisted, so it outlives `vi.resetModules()`. Without
 * tearing the previous module instance down, its subscription stays on that
 * store and keeps invoking — which is how this suite first went red.
 */
let current: TrayModule | null = null;

async function freshModule(): Promise<TrayModule> {
	current?.stopTrayForTests();
	vi.resetModules();
	current = await import('./tray.js');
	return current;
}

/** Tray updates are chained through a promise queue; let it drain. */
async function flush(): Promise<void> {
	await new Promise((resolve) => setTimeout(resolve, 0));
}

function calls(command: string): unknown[] {
	return invokeMock.mock.calls.filter(([name]) => name === command).map(([, args]) => args);
}

beforeEach(() => {
	browserMock.value = true;
	isTauriMock.mockReturnValue(true);
	invokeMock.mockReset();
	invokeMock.mockResolvedValue(undefined);
	listenMock.mockReset();
	listenMock.mockResolvedValue(() => {});
	foldersStore.set([]);
});

afterEach(() => {
	current?.stopTrayForTests();
	current = null;
	vi.unstubAllEnvs();
});

describe('startTray', () => {
	it('does nothing outside the desktop shell', async () => {
		isTauriMock.mockReturnValue(false);
		const tray = await freshModule();

		tray.startTray();

		expect(invokeMock).not.toHaveBeenCalled();
		expect(listenMock).not.toHaveBeenCalled();
	});

	it('does nothing in the mocked-API e2e build, where no Rust side exists', async () => {
		vi.stubEnv('VITE_E2E_MOCK', '1');
		const tray = await freshModule();

		tray.startTray();

		expect(invokeMock).not.toHaveBeenCalled();
	});

	it('pushes the close preference to Rust as a boolean', async () => {
		const tray = await freshModule();
		const { setCloseAction } = await import('$lib/stores/uiLayout.js');

		tray.startTray();
		expect(calls('set_close_behavior')).toEqual([{ hideToTray: false }]);

		setCloseAction('tray');
		expect(calls('set_close_behavior')).toEqual([{ hideToTray: false }, { hideToTray: true }]);
	});

	it('builds the menu once and then only refreshes the tooltip', async () => {
		const tray = await freshModule();

		tray.startTray();
		await flush();
		expect(calls('configure_tray')).toHaveLength(1);

		foldersStore.set([{ role: 'INBOX', unreadCount: 3, displayName: 'x', folderRef: 'INBOX' }]);
		foldersStore.set([{ role: 'INBOX', unreadCount: 5, displayName: 'x', folderRef: 'INBOX' }]);
		await flush();

		// The labels never changed, so the native menu is built exactly once —
		// recreating menu items on every sync is what this guards against.
		expect(calls('configure_tray')).toHaveLength(1);
		expect(calls('set_tray_tooltip')).toHaveLength(2);
	});

	it('counts unread from the Inbox only, ignoring other folders', async () => {
		foldersStore.set([
			{ role: 'JUNK', unreadCount: 40, displayName: 'x', folderRef: 'Junk' },
			{ role: 'INBOX', unreadCount: 2, displayName: 'x', folderRef: 'INBOX' }
		]);
		const tray = await freshModule();

		tray.startTray();
		await flush();

		const [labels] = calls('configure_tray') as [{ labels: { tooltip: string } }];
		expect(labels.labels.tooltip).toContain('2');
		expect(labels.labels.tooltip).not.toContain('40');
	});

	it('leaves the tooltip countless when the Inbox is fully read', async () => {
		foldersStore.set([{ role: 'INBOX', unreadCount: 0, displayName: 'x', folderRef: 'INBOX' }]);
		const tray = await freshModule();

		tray.startTray();
		await flush();

		const [labels] = calls('configure_tray') as [{ labels: { tooltip: string } }];
		expect(labels.labels.tooltip).toBe('VoxRox Mail');
	});

	/*
	 * The cache used to be written before the invoke resolved, so a rejected
	 * configure_tray still counted as applied: the labels never looked
	 * different again and the tray kept a menu-less icon for the whole session.
	 */
	it('retries the menu after a failed configure_tray instead of caching the failure', async () => {
		// Fail the first configure_tray specifically — startTray's first invoke
		// is set_close_behavior, so a plain mockRejectedValueOnce would land on
		// the wrong call.
		let menuBuilds = 0;
		invokeMock.mockImplementation((command: string) => {
			if (command === 'configure_tray' && ++menuBuilds === 1) {
				return Promise.reject(new Error('tray icon is not available'));
			}
			return Promise.resolve();
		});
		const tray = await freshModule();

		tray.startTray();
		await flush();
		expect(calls('configure_tray')).toHaveLength(1);

		foldersStore.set([{ role: 'INBOX', unreadCount: 1, displayName: 'x', folderRef: 'INBOX' }]);
		await flush();

		// The menu was never actually installed, so the next change has to try
		// again rather than fall through to a tooltip-only update.
		expect(calls('configure_tray')).toHaveLength(2);
		expect(calls('set_tray_tooltip')).toHaveLength(0);
	});

	it('subscribes only once even if called again', async () => {
		const tray = await freshModule();

		tray.startTray();
		tray.startTray();

		expect(calls('set_close_behavior')).toHaveLength(1);
		expect(listenMock).toHaveBeenCalledTimes(1);
	});
});
