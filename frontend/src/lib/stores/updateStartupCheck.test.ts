// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { get } from 'svelte/store';

/*
 * The store writes the user's choice to the registry through the Tauri shell
 * (frontend/src-tauri/src/update_preference.rs), where the installer's privacy
 * page reads it. Which answer wins at startup is asserted in updates.test.ts,
 * next to the check it gates.
 */
const { isTauriMock, invokeMock } = vi.hoisted(() => ({
	isTauriMock: vi.fn<() => boolean>(),
	invokeMock: vi.fn()
}));

vi.mock('$app/environment', () => ({
	browser: true,
	dev: false,
	building: false,
	version: 'test'
}));
vi.mock('@tauri-apps/api/core', () => ({ isTauri: isTauriMock, invoke: invokeMock }));

type StoreModule = typeof import('./updateStartupCheck.js');

async function freshStore(): Promise<StoreModule> {
	vi.resetModules();
	return import('./updateStartupCheck.js');
}

beforeEach(() => {
	isTauriMock.mockReset().mockReturnValue(true);
	invokeMock.mockReset().mockResolvedValue(undefined);
	vi.stubEnv('VITE_E2E_MOCK', '');
});

afterEach(() => {
	vi.unstubAllEnvs();
	vi.restoreAllMocks();
});

describe('setUpdateStartupCheck', () => {
	it('records the choice where the installer reads it', async () => {
		const mod = await freshStore();
		mod.setUpdateStartupCheck(false);

		expect(get(mod.updateStartupCheck)).toBe('off');
		// Without it the next start would adopt the installer's older answer back,
		// and the next reinstall would offer it.
		expect(invokeMock).toHaveBeenCalledWith('set_update_startup_check', { enabled: false });
	});

	it('writes nothing to the registry outside the desktop shell', async () => {
		isTauriMock.mockReturnValue(false);
		const mod = await freshStore();
		mod.setUpdateStartupCheck(false);

		expect(get(mod.updateStartupCheck)).toBe('off');
		expect(invokeMock).not.toHaveBeenCalled();
	});

	it('writes nothing to the registry in the mocked e2e build', async () => {
		vi.stubEnv('VITE_E2E_MOCK', '1');
		const mod = await freshStore();
		mod.setUpdateStartupCheck(true);

		expect(get(mod.updateStartupCheck)).toBe('on');
		expect(invokeMock).not.toHaveBeenCalled();
	});

	it('keeps the choice in the webview when the registry write fails', async () => {
		const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
		invokeMock.mockRejectedValue(new Error('access denied'));
		const mod = await freshStore();
		mod.setUpdateStartupCheck(false);

		await vi.waitFor(() => expect(warn).toHaveBeenCalledOnce());
		expect(get(mod.updateStartupCheck)).toBe('off');
	});
});

describe('loadUpdateStartupCheck', () => {
	it('keeps the stored value when the registry cannot be read', async () => {
		const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
		invokeMock.mockRejectedValue(new Error('shell unavailable'));
		const mod = await freshStore();
		mod.updateStartupCheck.set('off');

		await mod.loadUpdateStartupCheck();

		expect(get(mod.updateStartupCheck)).toBe('off');
		expect(warn).toHaveBeenCalledOnce();
	});
});
