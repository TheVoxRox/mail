// @vitest-environment jsdom
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { get } from 'svelte/store';

/*
 * bootstrap() against mocked phases. What is under test is the sequence, not
 * the phases: every module the boot calls into is a stub, and the boot store
 * is the real one so a test can read what the error view would show. Each test
 * imports a fresh copy, because bootstrap.ts keeps the running attempt in
 * module state.
 */
const {
	ensureSidecarMock,
	initSessionMock,
	readinessMock,
	loadAccountsMock,
	loadClientConfigMock,
	updateCheckMock
} = vi.hoisted(() => ({
	ensureSidecarMock: vi.fn<() => Promise<void>>(),
	initSessionMock: vi.fn<(options: { signal: AbortSignal }) => Promise<unknown>>(),
	readinessMock: vi.fn(async () => {}),
	loadAccountsMock: vi.fn(async () => {}),
	loadClientConfigMock: vi.fn(async () => {}),
	updateCheckMock: vi.fn(async () => {})
}));

vi.mock('#lib/backend/sidecar.js', () => ({
	ensureBackendSidecar: ensureSidecarMock,
	restartBackendSidecar: vi.fn(async () => {}),
	usesBackendSidecar: () => true
}));
vi.mock('#lib/stores/session.js', async () => {
	const { writable } = await import('svelte/store');
	return {
		sessionState: writable({ status: 'idle' }),
		initSession: initSessionMock,
		invalidateSession: vi.fn()
	};
});
vi.mock('#lib/stores/accounts.js', async () => {
	const { writable } = await import('svelte/store');
	return { accountsState: writable({ status: 'idle' }), loadAccounts: loadAccountsMock };
});
vi.mock('#lib/stores/clientConfig.js', () => ({ loadClientConfig: loadClientConfigMock }));
vi.mock('#lib/api/readiness.js', () => ({ waitForBackendReadiness: readinessMock }));
vi.mock('#lib/api/clientBootDiagnostics.js', () => ({
	reportClientBootDiagnostics: vi.fn(async () => {})
}));
vi.mock('#lib/stores/commands.js', () => ({ registerDefaultCommands: vi.fn() }));
vi.mock('#lib/stores/notifications.js', () => ({ startNotifications: vi.fn() }));
vi.mock('#lib/tray.js', () => ({ startTray: vi.fn() }));
vi.mock('#lib/updates.js', () => ({ checkForUpdateAndPrompt: updateCheckMock }));
vi.mock('#lib/stores/toasts.js', () => ({ announcePolite: vi.fn() }));
vi.mock('#lib/i18n/index.js', async () => {
	const { readable } = await import('svelte/store');
	return { _: readable((key: string) => key) };
});

async function freshModules() {
	vi.resetModules();
	const bootstrapModule = await import('./bootstrap.js');
	const bootModule = await import('#lib/stores/boot.js');
	return { bootstrap: bootstrapModule.bootstrap, bootState: bootModule.bootState };
}

function installLocalStorageStub(entries: Record<string, string> = {}): void {
	// Same idiom as updates.test.ts: jsdom-via-directive does not always
	// install localStorage on Node 22+, and the boot reads its e2e overrides
	// from there.
	const store = new Map(Object.entries(entries));
	Object.defineProperty(globalThis, 'localStorage', {
		value: {
			getItem: (key: string) => store.get(key) ?? null,
			setItem: (key: string, value: string) => store.set(key, value),
			removeItem: (key: string) => store.delete(key),
			clear: () => store.clear(),
			key: (index: number) => Array.from(store.keys())[index] ?? null,
			get length() {
				return store.size;
			}
		},
		writable: true,
		configurable: true
	});
}

beforeEach(() => {
	installLocalStorageStub();
	ensureSidecarMock.mockReset().mockResolvedValue(undefined);
	initSessionMock.mockReset().mockResolvedValue({ baseUrl: 'http://127.0.0.1:1/api' });
	readinessMock.mockClear();
	loadAccountsMock.mockClear();
	loadClientConfigMock.mockClear();
	updateCheckMock.mockClear();
});

describe('bootstrap — the startup update check', () => {
	it('runs after a boot that succeeds', async () => {
		const { bootstrap, bootState } = await freshModules();

		await bootstrap();

		expect(get(bootState).phase).toBe('ready');
		expect(updateCheckMock).toHaveBeenCalledOnce();
	});

	it('runs after a boot that fails, so a build whose backend does not start can still be replaced', async () => {
		ensureSidecarMock.mockRejectedValue(new Error('sidecar exited with code 70'));
		const { bootstrap, bootState } = await freshModules();

		await expect(bootstrap()).rejects.toThrow('sidecar exited with code 70');

		expect(get(bootState).phase).toBe('failed');
		expect(get(bootState).error?.message).toBe('sidecar exited with code 70');
		expect(updateCheckMock).toHaveBeenCalledOnce();
	});

	it('runs after a boot that times out, which still reports the timeout', async () => {
		installLocalStorageStub({ 'mail.e2e': '1', 'mail.e2e.bootTimeoutMs': '20' });
		// The way a real phase ends when the boot gives up on it: its own error,
		// raised by the abort.
		initSessionMock.mockImplementation(
			({ signal }) =>
				new Promise((_resolve, reject) => {
					signal.addEventListener('abort', () => reject(new Error('session load aborted')));
				})
		);
		const { bootstrap, bootState } = await freshModules();

		await expect(bootstrap()).rejects.toThrow('did not start within');

		expect(get(bootState).error?.name).toBe('BootTimeoutError');
		expect(updateCheckMock).toHaveBeenCalledOnce();
	});

	it('is left to the newer attempt when a forced retry supersedes a failing one', async () => {
		let failFirst: (err: Error) => void = () => {};
		ensureSidecarMock
			.mockImplementationOnce(
				() =>
					new Promise<void>((_resolve, reject) => {
						failFirst = reject;
					})
			)
			.mockResolvedValue(undefined);
		const { bootstrap } = await freshModules();

		const first = bootstrap();
		await bootstrap({ force: true });
		failFirst(new Error('stale attempt'));
		await expect(first).rejects.toThrow('stale attempt');

		// One call, from the attempt that won; the stale one throws past it.
		expect(updateCheckMock).toHaveBeenCalledOnce();
	});
});
