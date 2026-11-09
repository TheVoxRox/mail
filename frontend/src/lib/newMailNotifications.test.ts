// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { notifyArrivedMail } from './newMailNotifications.js';
import { setNewMailNotifications } from '#lib/stores/uiLayout.js';

/*
 * The Windows notification itself is Rust's (src-tauri/src/new_mail.rs), and so
 * is the question of whether the window has the focus — its tests cover that.
 * What this side owns, and what is tested here, is whether to ask at all and
 * what the request carries.
 */
const { browserMock, isTauriMock, invokeMock } = vi.hoisted(() => ({
	browserMock: { value: true },
	isTauriMock: vi.fn<() => boolean>(),
	invokeMock: vi.fn()
}));

vi.mock('$app/env', () => ({
	get browser() {
		return browserMock.value;
	},
	dev: false,
	building: false,
	version: 'test'
}));
vi.mock('@tauri-apps/api/core', () => ({ isTauri: isTauriMock, invoke: invokeMock }));

/** Echoes the key and its values, so the assertions read the wiring, not a translation. */
function t(id: string, options?: { values?: Record<string, number | string> }): string {
	return options?.values ? `${id} ${JSON.stringify(options.values)}` : id;
}

const mail = { count: 2, account: 'jan@example.com', folder: 'Inbox' };

beforeEach(() => {
	browserMock.value = true;
	isTauriMock.mockReturnValue(true);
	invokeMock.mockReset();
	invokeMock.mockResolvedValue(true);
});

afterEach(() => {
	setNewMailNotifications('on');
	vi.unstubAllEnvs();
});

describe('notifyArrivedMail', () => {
	it('asks Rust to announce the count, the account and the folder', async () => {
		await expect(notifyArrivedMail(mail, t)).resolves.toBe(true);

		expect(invokeMock).toHaveBeenCalledOnce();
		expect(invokeMock).toHaveBeenCalledWith('notify_new_mail', {
			title: 'newMailNotification.title',
			body: 'newMailNotification.body {"count":2,"account":"jan@example.com","folder":"Inbox"}'
		});
	});

	it('reports what Rust answered — false when the window had the focus', async () => {
		invokeMock.mockResolvedValue(false);

		await expect(notifyArrivedMail(mail, t)).resolves.toBe(false);
	});

	it('stays quiet when the user turned these notifications off', async () => {
		setNewMailNotifications('off');

		await expect(notifyArrivedMail(mail, t)).resolves.toBe(false);
		expect(invokeMock).not.toHaveBeenCalled();
	});

	it('stays quiet when nothing arrived — a first sync downloads, it does not deliver', async () => {
		await expect(notifyArrivedMail({ ...mail, count: 0 }, t)).resolves.toBe(false);
		expect(invokeMock).not.toHaveBeenCalled();
	});

	it('reads a missing count from a sidecar that predates it as nothing arrived', async () => {
		const fromOlderSidecar = { ...mail, count: undefined as unknown as number };

		await expect(notifyArrivedMail(fromOlderSidecar, t)).resolves.toBe(false);
		expect(invokeMock).not.toHaveBeenCalled();
	});

	it('does nothing outside the desktop shell', async () => {
		isTauriMock.mockReturnValue(false);

		await expect(notifyArrivedMail(mail, t)).resolves.toBe(false);
		expect(invokeMock).not.toHaveBeenCalled();
	});

	it('does nothing in the mocked-API e2e build, where no Rust side exists', async () => {
		vi.stubEnv('VITE_E2E_MOCK', '1');

		await expect(notifyArrivedMail(mail, t)).resolves.toBe(false);
		expect(invokeMock).not.toHaveBeenCalled();
	});
});
