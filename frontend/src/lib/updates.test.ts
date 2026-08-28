// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { get } from 'svelte/store';

/*
 * updates.ts reads VITE_ENABLE_AUTO_UPDATE_CHECK at module-load time and gates the
 * startup check on import.meta.env.PROD, so each test stubs the env and re-imports
 * a fresh module (freshModule()). Tauri's invoke + $app/environment are mocked so
 * no real IPC ever runs; updates go through the shell commands check_for_update
 * / download_pending_update / install_pending_update (see
 * frontend/src-tauri/src/lib.rs). The sidecar and bootstrap modules are mocked
 * too — the install path stops the backend between the download and the
 * install, and putting it back is part of the failure path.
 */
const {
	browserMock,
	isTauriMock,
	invokeMock,
	usesSidecarMock,
	stopSidecarMock,
	bootstrapMock,
	listenMock,
	unlistenMock,
	emitProgress
} = vi.hoisted(() => {
	const handlers = new Map<string, (event: { payload: unknown }) => void>();
	const unlisten = vi.fn();
	return {
		browserMock: { value: true },
		isTauriMock: vi.fn<() => boolean>(),
		invokeMock: vi.fn(),
		usesSidecarMock: vi.fn<() => boolean>(),
		stopSidecarMock: vi.fn(async () => {}),
		bootstrapMock: vi.fn(async () => {}),
		unlistenMock: unlisten,
		listenMock: vi.fn(async (event: string, handler: (e: { payload: unknown }) => void) => {
			handlers.set(event, handler);
			return unlisten;
		}),
		emitProgress: (payload: { downloaded: number; total: number | null }) =>
			handlers.get('update://download-progress')?.({ payload })
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
vi.mock('$lib/backend/sidecar.js', () => ({
	usesBackendSidecar: usesSidecarMock,
	stopBackendSidecar: stopSidecarMock
}));
vi.mock('$lib/bootstrap.js', () => ({ bootstrap: bootstrapMock }));

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
	usesSidecarMock.mockReset().mockReturnValue(true);
	stopSidecarMock.mockReset();
	bootstrapMock.mockReset();
	listenMock.mockClear();
	unlistenMock.mockClear();
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

describe('postponing versus skipping a version', () => {
	const STORAGE_KEY = 'mail.update.dismissedVersion';

	async function promptedModule() {
		invokeMock.mockResolvedValue(null);
		invokeMock.mockResolvedValueOnce({ version: '9.9.9', currentVersion: '0.1.0' });
		const mod = await freshModule();
		await mod.checkForUpdateManually();
		return mod;
	}

	it('closing the prompt decides nothing', async () => {
		const mod = await promptedModule();

		mod.postponePromptedUpdate();

		// "Later" used to persist the version, so it meant "never" — and so did
		// Escape and a click outside, which reach the same function through the
		// dialog's onOpenChange with no label saying so.
		expect(get(mod.updatePromptState)).toEqual({ status: 'hidden' });
		expect(localStorage.getItem(STORAGE_KEY)).toBeNull();
	});

	it('skipping records the version and the startup check honours it', async () => {
		const mod = await promptedModule();

		mod.skipPromptedUpdateVersion();
		expect(localStorage.getItem(STORAGE_KEY)).toBe('9.9.9');

		invokeMock.mockResolvedValueOnce({ version: '9.9.9', currentVersion: '0.1.0' });
		await mod.checkForUpdateAndPrompt();

		expect(get(mod.updatePromptState)).toEqual({ status: 'hidden' });
	});

	it('a skipped version is still offered by the manual check', async () => {
		const mod = await promptedModule();
		mod.skipPromptedUpdateVersion();

		invokeMock.mockResolvedValueOnce({ version: '9.9.9', currentVersion: '0.1.0' });
		const result = await mod.checkForUpdateManually();

		// Skipping silences the startup prompt, not the update. Asking for it
		// explicitly has to answer, or the record becomes a trap with no way out.
		expect(result.status).toBe('available');
		expect(get(mod.updatePromptState).status).toBe('available');
	});

	it('skipping one version does not silence the next', async () => {
		const mod = await promptedModule();
		mod.skipPromptedUpdateVersion();

		invokeMock.mockResolvedValueOnce({ version: '10.0.0', currentVersion: '0.1.0' });
		await mod.checkForUpdateAndPrompt();

		expect(get(mod.updatePromptState).status).toBe('available');
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
			.mockResolvedValueOnce(undefined)
			.mockResolvedValueOnce(undefined);

		const mod = await freshModule();
		await mod.checkForUpdateManually();
		await mod.installPromptedUpdate();

		// expectedVersion pins both halves to what the prompt showed — the
		// shell refuses to act on anything else in its pending slot.
		expect(invokeMock).toHaveBeenCalledWith('download_pending_update', {
			expectedVersion: '9.9.9'
		});
		expect(invokeMock).toHaveBeenLastCalledWith('install_pending_update', {
			expectedVersion: '9.9.9'
		});
		expect(get(mod.updatePromptState)).toEqual({ status: 'hidden' });
	});
});

/*
 * The install path frees the install directory before handing over to the
 * installer. What matters is the ORDER — the installer overwrites the sidecar
 * launcher and its bundled JRE, and Windows will not overwrite a file a live
 * process holds open — so these assert the sequence, not just that the calls
 * happened.
 */
describe('installPromptedUpdate (backend handoff)', () => {
	async function primedModule() {
		invokeMock.mockResolvedValue(null);
		invokeMock.mockResolvedValueOnce({ version: '9.9.9', currentVersion: '0.1.0' });
		const mod = await freshModule();
		await mod.checkForUpdateManually();
		return mod;
	}

	/** Position of one call in the global invocation sequence, so the order of
	 * two different mocks can be compared. */
	function orderOf(mock: { mock: { invocationCallOrder: number[] } }, index = 0): number {
		return mock.mock.invocationCallOrder[index];
	}

	it('stops the backend after the download and before the install', async () => {
		const mod = await primedModule();
		await mod.installPromptedUpdate();

		expect(invokeMock.mock.calls.map(([name]) => name)).toEqual([
			'check_for_update',
			'download_pending_update',
			'install_pending_update'
		]);
		// Downloading with the backend already down would leave the app dead
		// for the length of the download; installing with it still up is the
		// bug this replaced. Only the middle position is correct.
		expect(orderOf(invokeMock, 1)).toBeLessThan(orderOf(stopSidecarMock));
		expect(orderOf(stopSidecarMock)).toBeLessThan(orderOf(invokeMock, 2));
	});

	it('names each phase as it runs, in order', async () => {
		const mod = await primedModule();
		const seen: string[] = [];
		const record = () => {
			const state = get(mod.updatePromptState);
			seen.push(state.status === 'installing' ? state.phase : state.status);
		};
		invokeMock.mockImplementation(async (name: string) => {
			if (name !== 'check_for_update') record();
			return undefined;
		});
		stopSidecarMock.mockImplementation(async () => record());

		await mod.installPromptedUpdate();

		// The three steps are not interchangeable to someone waiting: the
		// download is long, stopping the backend is brief, and the install ends
		// the app. A single "installing" label for all three is what this
		// replaced.
		expect(seen).toEqual(['downloading', 'stoppingBackend', 'installing']);
	});

	it('records download progress while downloading, then stops listening', async () => {
		const mod = await primedModule();
		let duringDownload: unknown = null;
		invokeMock.mockImplementation(async (name: string) => {
			if (name === 'download_pending_update') {
				emitProgress({ downloaded: 512, total: 1024 });
				duringDownload = get(mod.updatePromptState);
			}
			return undefined;
		});

		await mod.installPromptedUpdate();

		expect(duringDownload).toMatchObject({
			status: 'installing',
			phase: 'downloading',
			progress: { downloaded: 512, total: 1024 }
		});
		expect(unlistenMock).toHaveBeenCalledTimes(1);
	});

	it('ignores a progress event that arrives after the download phase', async () => {
		const mod = await primedModule();
		let duringInstall: unknown = null;
		invokeMock.mockImplementation(async (name: string) => {
			if (name === 'install_pending_update') {
				emitProgress({ downloaded: 999, total: 1024 });
				duringInstall = get(mod.updatePromptState);
			}
			return undefined;
		});

		await mod.installPromptedUpdate();

		// A late event must not put a download bar back on a dialog that has
		// moved on to the step which ends the app.
		expect(duringInstall).toMatchObject({ phase: 'installing', progress: null });
	});

	it('leaves the backend alone when the download fails', async () => {
		const mod = await primedModule();
		invokeMock.mockRejectedValueOnce(new Error('download failed'));

		await mod.installPromptedUpdate();

		expect(stopSidecarMock).not.toHaveBeenCalled();
		expect(bootstrapMock).not.toHaveBeenCalled();
		expect(get(mod.updateFailureState).status).toBe('failed');
		// The prompt closes instead of reverting to 'available': the failure
		// dialog would otherwise stand on top of a prompt still offering the
		// update that just failed, two modals deep.
		expect(get(mod.updatePromptState).status).toBe('hidden');
	});

	it('puts the backend back when the install fails', async () => {
		const mod = await primedModule();
		invokeMock.mockResolvedValueOnce(undefined).mockRejectedValueOnce(new Error('install failed'));

		await mod.installPromptedUpdate();

		// A respawn alone is not enough — the new sidecar comes up on a fresh
		// port with a fresh handshake key, so the boot has to be redone.
		expect(bootstrapMock).toHaveBeenCalledWith({ restartSidecar: true });
		expect(get(mod.updateFailureState).status).toBe('failed');
	});

	it('does not touch the backend when this build has no sidecar', async () => {
		usesSidecarMock.mockReturnValue(false);
		const mod = await primedModule();

		await mod.installPromptedUpdate();

		expect(stopSidecarMock).not.toHaveBeenCalled();
		expect(get(mod.updatePromptState)).toEqual({ status: 'hidden' });
	});

	it('refuses a second run while one is in flight', async () => {
		const mod = await primedModule();

		const first = mod.installPromptedUpdate();
		await mod.installPromptedUpdate();
		await first;

		// The prompt disables its buttons while installing, so this is a
		// guard, not a path — but the old one admitted the 'installing' state
		// and would have started a second download over the first.
		expect(
			invokeMock.mock.calls.filter(([name]) => name === 'download_pending_update')
		).toHaveLength(1);
	});
});
