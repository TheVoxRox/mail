import { isTauri } from '@tauri-apps/api/core';
import { getCurrentWindow } from '@tauri-apps/api/window';

/**
 * Sets the **native** OS window title (taskbar / Alt+Tab, and the accessible
 * name a screen reader reads when the window gains focus). This is distinct
 * from `document.title`, which SvelteKit manages per route inside the webview —
 * the native title can only be changed through Tauri's `setTitle`.
 *
 * No-op outside Tauri (browser preview / Playwright), and a rejected `setTitle`
 * (no native window) is swallowed rather than thrown.
 *
 * Deps are injectable for unit tests.
 */
export interface NativeWindowTitleDeps {
	isTauri: () => boolean;
	getCurrentWindow: () => { setTitle: (title: string) => Promise<void> };
}

const defaultDeps: NativeWindowTitleDeps = { isTauri, getCurrentWindow };

export function setNativeWindowTitle(
	title: string,
	deps: NativeWindowTitleDeps = defaultDeps
): void {
	if (!deps.isTauri()) return;
	void deps
		.getCurrentWindow()
		.setTitle(title)
		.catch(() => {
			// Browser / e2e / preview contexts have no native Tauri window to update.
		});
}
