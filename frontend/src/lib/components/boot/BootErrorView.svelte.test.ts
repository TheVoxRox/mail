import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { flushSync, mount, unmount } from 'svelte';
import { setLocale } from '#lib/i18n/index.js';
import BootErrorView from './BootErrorView.svelte';

/*
 * Save logs is rendered only inside the native shell, which the mocked e2e
 * build never has, so this is the one place that sees it on the error view.
 * The shell and the updater are stubbed; the view and its action row are real.
 */
const { saveLogsMock } = vi.hoisted(() => ({ saveLogsMock: vi.fn<() => Promise<string>>() }));

vi.mock('#lib/nativeShell.js', () => ({ hasNativeShell: () => true }));
vi.mock('#lib/logs.js', () => ({ saveLogs: saveLogsMock }));
vi.mock('#lib/updates.js', () => ({ checkForUpdateManually: vi.fn() }));

let component: ReturnType<typeof mount> | null = null;

beforeAll(() => {
	setLocale('en');
});

afterEach(() => {
	if (component) void unmount(component);
	component = null;
	document.body.innerHTML = '';
});

/** As the layout renders it for a backend that stopped before it was up. */
function mountForStoppedBackend(): HTMLElement {
	const target = document.createElement('div');
	document.body.append(target);
	component = mount(BootErrorView, {
		target,
		props: {
			errorMessage: 'The mail database on this computer is damaged.',
			onRetry: () => {},
			onRestart: () => {},
			onDownloadDiagnostic: () => {},
			diagnosticBusy: false,
			diagnosticDisabled: true,
			diagnosticError: null,
			diagnosticUnavailable: true
		}
	});
	flushSync();
	return target;
}

function button(root: HTMLElement, name: string): HTMLButtonElement {
	const found = [...root.querySelectorAll('button')].find((b) => b.textContent?.trim() === name);
	if (!found) throw new Error(`No button named "${name}"`);
	return found;
}

describe('BootErrorView for a backend that never started', () => {
	it('offers Save logs while the dump says it is unavailable', () => {
		const root = mountForStoppedBackend();

		expect(button(root, 'Save logs').disabled).toBe(false);
		expect(button(root, 'Download diagnostics').disabled).toBe(true);
		expect(root.textContent).toContain(
			'Diagnostics will be available after the secure backend connection is loaded.'
		);
	});

	it('saves the logs with no backend behind it and says where they went', async () => {
		saveLogsMock.mockResolvedValue('C:\\Users\\tester\\Downloads\\voxrox-mail-logs.zip');
		const root = mountForStoppedBackend();

		button(root, 'Save logs').click();

		expect(saveLogsMock).toHaveBeenCalledOnce();
		await vi.waitFor(() => {
			flushSync();
			expect(root.querySelector('[role="status"]:not(:empty)')?.textContent?.trim()).toBe(
				'The logs are saved in C:\\Users\\tester\\Downloads\\voxrox-mail-logs.zip.'
			);
		});
	});
});
