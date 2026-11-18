import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { flushSync, mount, tick, unmount } from 'svelte';
import { setLocale } from '#lib/i18n/index.js';
import ThirdPartyNotices from './ThirdPartyNotices.svelte';

/*
 * The notices open only inside the desktop app, which the mocked e2e build
 * never is, so the disclosure is exercised here with the shell stubbed: the
 * Tauri check, the resource path and the file read. The component and bits-ui's
 * Collapsible under it are real.
 */
const { isTauriMock, readTextFileMock } = vi.hoisted(() => ({
	isTauriMock: vi.fn(() => true),
	readTextFileMock: vi.fn<(path: string) => Promise<string>>()
}));

vi.mock('@tauri-apps/api/core', () => ({ isTauri: isTauriMock }));
vi.mock('@tauri-apps/api/path', () => ({
	resolveResource: (name: string) => Promise.resolve(`/resources/${name}`)
}));
vi.mock('@tauri-apps/plugin-fs', () => ({ readTextFile: readTextFileMock }));

let component: ReturnType<typeof mount> | null = null;

beforeAll(() => {
	setLocale('en');
});

beforeEach(() => {
	isTauriMock.mockReturnValue(true);
	readTextFileMock.mockReset();
});

afterEach(() => {
	if (component) void unmount(component);
	component = null;
	document.body.innerHTML = '';
});

function render(): HTMLButtonElement {
	const target = document.createElement('div');
	document.body.append(target);
	component = mount(ThirdPartyNotices, { target });
	flushSync();
	return document.querySelector<HTMLButtonElement>('button')!;
}

/** The file read resolves on a later task; the disclosure follows it. */
async function settle(): Promise<void> {
	await new Promise((resolve) => setTimeout(resolve));
	await tick();
	flushSync();
}

describe('ThirdPartyNotices', () => {
	it('is a disclosure that names what it controls and starts shut', () => {
		const toggle = render();

		expect(toggle.getAttribute('aria-expanded')).toBe('false');
		expect(toggle.getAttribute('aria-controls')).toBe('about-licenses-content');
		expect(document.querySelector('textarea')).toBeNull();
	});

	it('opens on the text, labelled by the heading, and closes again', async () => {
		readTextFileMock.mockResolvedValue('Third-party notices');
		const toggle = render();

		toggle.click();
		await settle();

		expect(readTextFileMock).toHaveBeenCalledWith('/resources/NOTICE.txt');
		expect(toggle.getAttribute('aria-expanded')).toBe('true');
		const text = document.querySelector('textarea')!;
		expect(text.value).toBe('Third-party notices');
		expect(text.getAttribute('aria-labelledby')).toBe('about-licenses-heading');
		expect(text.closest('#about-licenses-content')).not.toBeNull();

		toggle.click();
		await settle();

		expect(toggle.getAttribute('aria-expanded')).toBe('false');
		// bits-ui keeps closed content in the tree. In Chromium it is then
		// `hidden` (display none, out of the accessibility tree; measured with
		// the shell stubbed); jsdom runs no exit animation and stops at the
		// closed state, which is what is left to check here.
		expect(document.getElementById('about-licenses-content')?.dataset.state ?? 'closed').toBe(
			'closed'
		);
	});

	it('stays shut and says so when the file cannot be read', async () => {
		readTextFileMock.mockRejectedValue(new Error('missing'));
		const toggle = render();

		toggle.click();
		await settle();

		expect(toggle.getAttribute('aria-expanded')).toBe('false');
		expect(document.querySelector('[role="alert"]')?.textContent).toBe(
			'The license file could not be loaded.'
		);
	});

	it('stays shut outside the desktop app and says why', async () => {
		isTauriMock.mockReturnValue(false);
		const toggle = render();

		toggle.click();
		await settle();

		expect(readTextFileMock).not.toHaveBeenCalled();
		expect(toggle.getAttribute('aria-expanded')).toBe('false');
		expect(document.body.textContent).toContain('available in the desktop app');
	});
});
