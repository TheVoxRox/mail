// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { get } from 'svelte/store';

/*
 * updates.ts reads VITE_ENABLE_AUTO_UPDATE_CHECK at module-load time and gates the
 * startup check on import.meta.env.PROD, so each test stubs the env and re-imports
 * a fresh module (freshModule()). Tauri's invoke + $app/environment are mocked so
 * no real IPC ever runs; update checks go through the shell commands
 * check_for_update / install_pending_update (see frontend/src-tauri/src/lib.rs).
 */
const { browserMock, isTauriMock, invokeMock } = vi.hoisted(() => ({
	browserMock: { value: true },
	isTauriMock: vi.fn<() => boolean>(),
	invokeMock: vi.fn()
}));

vi.mock('$app/environment', () => ({
	get browser() {
		return browserMock.value;
	},
	dev: false,
	building: false,
	version: 'test'
}));
vi.mock('@tauri-apps/api/core', () => ({ isTauri: isTauriMock, invoke: invokeMock }));

type UpdatesModule = typeof import('./updates.js');

async function freshModule(): Promise<UpdatesModule> {
	vi.resetModules();
	return import('./updates.js');
}

function installLocalStorageStub(): void {
	// jsdom-via-directive does not always install localStorage on Node 22+,
	// so we plant a minimal in-memory stub instead (same idiom as
	// sidecar.test.ts). A fresh stub per test doubles as isolation.
	const store = new Map<string, string>();
	const stub: Storage = {
		getItem: (key: string) => store.get(key) ?? null,
		setItem: (key: string, value: string) => {
			store.set(key, value);
		},
		removeItem: (key: string) => {
			store.delete(key);
		},
		clear: () => {
			store.clear();
		},
		get length() {
			return store.size;
		},
		key: (index: number) => Array.from(store.keys())[index] ?? null
	};
	Object.defineProperty(globalThis, 'localStorage', {
		value: stub,
		writable: true,
		configurable: true
	});
}

beforeEach(() => {
	browserMock.value = true;
	isTauriMock.mockReturnValue(true);
	invokeMock.mockReset();
	installLocalStorageStub();
	vi.stubEnv('VITE_E2E_MOCK', '');
	vi.stubEnv('VITE_ENABLE_AUTO_UPDATE_CHECK', '1');
	vi.stubEnv('PROD', true);
});

afterEach(() => {
	vi.unstubAllEnvs();
	vi.restoreAllMocks();
});

describe('checkForUpdateAndPrompt (startup, background)', () => {
	it('fails silently — no failure dialog, just a console warning', async () => {
		const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
		invokeMock.mockRejectedValue(new Error('Could not fetch a valid release JSON from the remote'));

		const mod = await freshModule();
		await mod.checkForUpdateAndPrompt();

		// The whole point of the fix: a transient/no-release startup failure must
		// NOT surface the prominent dialog (it would announce on every cold start).
		expect(get(mod.updateFailureState)).toEqual({ status: 'hidden' });
		expect(get(mod.updatePromptState)).toEqual({ status: 'hidden' });
		// warn called proves the catch branch actually ran (the PROD/flag gate passed).
		expect(warn).toHaveBeenCalledOnce();
	});

	it('still prompts when an update is available', async () => {
		invokeMock.mockResolvedValue({
			version: '9.9.9',
			currentVersion: '0.1.0'
		});

		const mod = await freshModule();
		await mod.checkForUpdateAndPrompt();

		expect(get(mod.updatePromptState).status).toBe('available');
		expect(get(mod.updateFailureState)).toEqual({ status: 'hidden' });
	});

	it('hides a stale prompt when it finds nothing', async () => {
		invokeMock
			.mockResolvedValueOnce({ version: '9.9.9', currentVersion: '0.1.0' })
			.mockResolvedValueOnce(null);

		const mod = await freshModule();
		await mod.checkForUpdateManually();
		await mod.checkForUpdateAndPrompt();

		// The second check replaced the shell's pending slot with "nothing";
		// the old prompt would offer an update the shell refuses to install.
		expect(get(mod.updatePromptState)).toEqual({ status: 'hidden' });
	});
});

describe('checkForUpdateManually (user-initiated)', () => {
	it('surfaces the failure dialog when the check fails', async () => {
		invokeMock.mockRejectedValue(new Error('network down'));

		const mod = await freshModule();
		const result = await mod.checkForUpdateManually();

		expect(result.status).toBe('failed');
		expect(get(mod.updateFailureState).status).toBe('failed');
	});

	it('hides a stale prompt when a later check finds nothing', async () => {
		invokeMock
			.mockResolvedValueOnce({ version: '9.9.9', currentVersion: '0.1.0' })
			.mockResolvedValueOnce(null);

		const mod = await freshModule();
		await mod.checkForUpdateManually();
		expect(get(mod.updatePromptState).status).toBe('available');

		const result = await mod.checkForUpdateManually();

		expect(result.status).toBe('none');
		expect(get(mod.updatePromptState)).toEqual({ status: 'hidden' });
	});

	it('does not touch an in-flight install when a later check finds nothing', async () => {
		let resolveInstall!: () => void;
		invokeMock
			.mockResolvedValueOnce({ version: '9.9.9', currentVersion: '0.1.0' })
			.mockImplementationOnce(
				() =>
					new Promise<void>((resolve) => {
						resolveInstall = resolve;
					})
			)
			.mockResolvedValueOnce(null);

		const mod = await freshModule();
		await mod.checkForUpdateManually();
		const install = mod.installPromptedUpdate();
		await mod.checkForUpdateManually();

		// The install works on its own handle; yanking the dialog away under
		// the user mid-download would be worse than the stale prompt.
		expect(get(mod.updatePromptState).status).toBe('installing');

		resolveInstall();
		await install;
		expect(get(mod.updatePromptState)).toEqual({ status: 'hidden' });
	});
});

describe('update channel routing', () => {
	it('checks on the stable channel by default', async () => {
		invokeMock.mockResolvedValue(null);

		const mod = await freshModule();
		const result = await mod.checkForUpdateManually();

		expect(result.status).toBe('none');
		expect(invokeMock).toHaveBeenCalledWith('check_for_update', { channel: 'stable' });
	});

	it('checks on the beta channel when the stored preference says so', async () => {
		window.localStorage.setItem('mail.updateChannel', 'beta');
		invokeMock.mockResolvedValue(null);

		const mod = await freshModule();
		await mod.checkForUpdateManually();

		expect(invokeMock).toHaveBeenCalledWith('check_for_update', { channel: 'beta' });
	});

	it('installs through the pending update held by the shell', async () => {
		invokeMock
			.mockResolvedValueOnce({ version: '9.9.9', currentVersion: '0.1.0' })
			.mockResolvedValueOnce(undefined);

		const mod = await freshModule();
		await mod.checkForUpdateManually();
		await mod.installPromptedUpdate();

		// expectedVersion pins the install to what the prompt showed — the
		// shell refuses to install anything else from its pending slot.
		expect(invokeMock).toHaveBeenLastCalledWith('install_pending_update', {
			expectedVersion: '9.9.9'
		});
		expect(get(mod.updatePromptState)).toEqual({ status: 'hidden' });
	});
});
