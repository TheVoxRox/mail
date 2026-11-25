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
function mountForStoppedBackend(errorDetail: string | null = null): HTMLElement {
	const target = document.createElement('div');
	document.body.append(target);
	component = mount(BootErrorView, {
		target,
		props: {
			errorMessage: 'The mail database on this computer is damaged.',
			errorDetail,
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
			'Diagnostics can be downloaded once the app connects to the mail service.'
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

/*
 * The developer's text is for support, not for the person reading the screen:
 * hidden until asked for, and marked as the English it is once shown (3.1.2),
 * whatever the interface language.
 */
describe('BootErrorView technical details', () => {
	const detail = 'Application did not start within 60 s.';

	it('hides the error’s own text until the disclosure opens, then marks it English', async () => {
		const root = mountForStoppedBackend(detail);
		const trigger = button(root, 'Technical details');
		const text = () => [...root.querySelectorAll('p')].find((p) => p.textContent === detail);

		// bits-ui renders the closed content with `hidden`, which keeps it out of
		// both the view and the accessibility tree.
		expect(trigger.getAttribute('aria-expanded')).toBe('false');
		expect(text()?.closest('[hidden]')).not.toBeNull();

		trigger.click();

		await vi.waitFor(() => {
			flushSync();
			expect(trigger.getAttribute('aria-expanded')).toBe('true');
			expect(text()?.closest('[hidden]')).toBeNull();
			expect(text()?.getAttribute('lang')).toBe('en');
		});
	});

	it('offers no disclosure when the message already says it all', () => {
		const root = mountForStoppedBackend(null);

		expect(() => button(root, 'Technical details')).toThrow();
	});
});
