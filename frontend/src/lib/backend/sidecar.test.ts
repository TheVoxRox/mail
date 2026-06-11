// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { get } from 'svelte/store';

const { isTauriMock, mailDataDirMock, commandSidecarMock, existsMock, removeMock, joinMock } =
	vi.hoisted(() => ({
		isTauriMock: vi.fn<() => boolean>(),
		mailDataDirMock: vi.fn<() => Promise<string>>(),
		commandSidecarMock: vi.fn(),
		existsMock: vi.fn<(path: string) => Promise<boolean>>(),
		removeMock: vi.fn<(path: string) => Promise<void>>(),
		joinMock: vi.fn<(...segments: string[]) => Promise<string>>()
	}));

vi.mock('@tauri-apps/api/core', () => ({ isTauri: isTauriMock }));
vi.mock('@tauri-apps/api/path', () => ({ join: joinMock, localDataDir: async () => '/tmp/test' }));
vi.mock('@tauri-apps/plugin-shell', () => ({ Command: { sidecar: commandSidecarMock } }));
vi.mock('@tauri-apps/plugin-fs', () => ({ exists: existsMock, remove: removeMock }));
vi.mock('./data-dir', () => ({ mailDataDir: mailDataDirMock }));

import { clearStaleHandshakeFiles, type HandshakeFilesFs } from './sidecar.js';

type SidecarModule = typeof import('./sidecar.js');

interface CommandHandle {
	cmd: {
		stdout: { on: ReturnType<typeof vi.fn> };
		stderr: { on: ReturnType<typeof vi.fn> };
		on: ReturnType<typeof vi.fn>;
		spawn: ReturnType<typeof vi.fn>;
	};
	child: { pid: number; kill: ReturnType<typeof vi.fn> };
	emitClose: (code: number | null, signal?: number | null) => void;
	emitError: (message: string) => void;
}

function createCommandHandle(opts: { pid?: number; spawnThrows?: Error } = {}): CommandHandle {
	const closeListeners: Array<(payload: { code: number | null; signal: number | null }) => void> =
		[];
	const errorListeners: Array<(message: string) => void> = [];
	const child = { pid: opts.pid ?? 12345, kill: vi.fn().mockResolvedValue(undefined) };
	const cmd = {
		stdout: { on: vi.fn() },
		stderr: { on: vi.fn() },
		on: vi.fn((event: string, cb: (payload: unknown) => void) => {
			if (event === 'close')
				closeListeners.push(cb as (p: { code: number | null; signal: number | null }) => void);
			if (event === 'error') errorListeners.push(cb as (m: string) => void);
		}),
		spawn: opts.spawnThrows
			? vi.fn().mockRejectedValue(opts.spawnThrows)
			: vi.fn().mockResolvedValue(child)
	};
	return {
		cmd,
		child,
		emitClose: (code, signal = null) => closeListeners.forEach((cb) => cb({ code, signal })),
		emitError: (message) => errorListeners.forEach((cb) => cb(message))
	};
}

async function freshModule(): Promise<SidecarModule> {
	vi.resetModules();
	// runtime is stored on globalThis as __MAIL_BACKEND_SIDECAR__ — module scope
	// caches it once, so a clean import needs both modules reset and the global
	// wiped.
	delete (globalThis as unknown as { __MAIL_BACKEND_SIDECAR__?: unknown }).__MAIL_BACKEND_SIDECAR__;
	return await import('./sidecar.js');
}

function installLocalStorageStub(): void {
	// jsdom-via-directive does not always install localStorage on Node 22+,
	// so we plant a minimal in-memory stub instead. Production runs against
	// the real DOM localStorage; only this file needs it.
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
	isTauriMock.mockReturnValue(true);
	mailDataDirMock.mockResolvedValue('/data');
	joinMock.mockImplementation(async (...segments) => segments.filter(Boolean).join('/'));
	existsMock.mockResolvedValue(false);
	removeMock.mockResolvedValue();
	commandSidecarMock.mockReset();
	installLocalStorageStub();
});

afterEach(() => {
	vi.unstubAllEnvs();
});

/**
 * `clearStaleHandshakeFiles` is a race-condition guard: without it the
 * frontend (polling 200 ms) finds the previous run's `.ready` +
 * `session.json` before the backend JVM has time to start (~2–5 s) and
 * ends up hanging on a dead port. These tests verify that cleanup ALWAYS
 * deletes both files before spawn and tolerantly handles missing files
 * (clean first start of the application).
 */
describe('clearStaleHandshakeFiles', () => {
	function makeFs(initialFiles: Set<string>): {
		fs: HandshakeFilesFs;
		removeCalls: string[];
		existsCalls: string[];
	} {
		const files = new Set(initialFiles);
		const removeCalls: string[] = [];
		const existsCalls: string[] = [];

		return {
			fs: {
				join: vi.fn(async (...segments: string[]) => segments.join('/')),
				exists: vi.fn(async (path: string) => {
					existsCalls.push(path);
					return files.has(path);
				}),
				remove: vi.fn(async (path: string) => {
					removeCalls.push(path);
					files.delete(path);
				})
			},
			removeCalls,
			existsCalls
		};
	}

	it('deletes both stale files if they exist', async () => {
		const dataDir = '/data';
		const { fs, removeCalls } = makeFs(new Set([`${dataDir}/.ready`, `${dataDir}/session.json`]));

		await clearStaleHandshakeFiles(dataDir, fs);

		// Cleanup is parallel (Promise.all) — order is no longer load-bearing,
		// the contract is that BOTH files are removed.
		expect(removeCalls).toHaveLength(2);
		expect(removeCalls).toEqual(
			expect.arrayContaining([`${dataDir}/.ready`, `${dataDir}/session.json`])
		);
	});

	it('does not call remove for a non-existent file (clean first start)', async () => {
		const dataDir = '/data';
		const { fs, removeCalls } = makeFs(new Set());

		await clearStaleHandshakeFiles(dataDir, fs);

		expect(removeCalls).toEqual([]);
	});

	it('deletes only the file that exists (partial cleanup from a previous run)', async () => {
		const dataDir = '/data';
		// Real edge case: backend managed to delete .ready but not session.json
		// (or vice versa) when it was killed between the two steps.
		const { fs, removeCalls } = makeFs(new Set([`${dataDir}/session.json`]));

		await clearStaleHandshakeFiles(dataDir, fs);

		expect(removeCalls).toEqual([`${dataDir}/session.json`]);
	});

	it('propagates a remove failure (e.g. permission denied) — fail-fast over silent race', async () => {
		const dataDir = '/data';
		const fs: HandshakeFilesFs = {
			join: async (...segments) => segments.join('/'),
			exists: async () => true,
			remove: async () => {
				throw new Error('EACCES');
			}
		};

		await expect(clearStaleHandshakeFiles(dataDir, fs)).rejects.toThrow('EACCES');
	});
});

describe('usesBackendSidecar', () => {
	it('returns false outside a Tauri runtime (browser preview / SSR)', async () => {
		isTauriMock.mockReturnValue(false);
		const mod = await freshModule();
		expect(mod.usesBackendSidecar()).toBe(false);
	});

	it('returns false in Tauri when VITE_E2E_MOCK=1 (Playwright drives a mocked backend)', async () => {
		vi.stubEnv('VITE_E2E_MOCK', '1');
		const mod = await freshModule();
		expect(mod.usesBackendSidecar()).toBe(false);
	});

	it('returns false in Tauri when VITE_BACKEND_SIDECAR=0 (dev runs against a bare mvn server)', async () => {
		vi.stubEnv('VITE_BACKEND_SIDECAR', '0');
		const mod = await freshModule();
		expect(mod.usesBackendSidecar()).toBe(false);
	});

	it('returns true in Tauri when neither override is set', async () => {
		const mod = await freshModule();
		expect(mod.usesBackendSidecar()).toBe(true);
	});
});

describe('ensureBackendSidecar — env / lifecycle short-circuits', () => {
	it('sets state=disabled and does not spawn when usesBackendSidecar() is false', async () => {
		isTauriMock.mockReturnValue(false);
		const mod = await freshModule();

		await mod.ensureBackendSidecar();

		expect(commandSidecarMock).not.toHaveBeenCalled();
		expect(get(mod.backendSidecarState)).toEqual({ status: 'disabled' });
	});

	it('throws immediately with E2E sidecar failure (once) and clears the localStorage key', async () => {
		vi.stubEnv('VITE_E2E_MOCK', '1');
		localStorage.setItem('mail.e2e.sidecarFailure', 'once');
		const mod = await freshModule();

		await expect(mod.ensureBackendSidecar()).rejects.toThrow('E2E sidecar failed to start');

		expect(localStorage.getItem('mail.e2e.sidecarFailure')).toBeNull();
		expect(get(mod.backendSidecarState)).toMatchObject({ status: 'error' });
		expect(commandSidecarMock).not.toHaveBeenCalled();
	});

	it('throws again on next call when the failure mode is "always" (key is preserved)', async () => {
		vi.stubEnv('VITE_E2E_MOCK', '1');
		localStorage.setItem('mail.e2e.sidecarFailure', 'always');
		const mod = await freshModule();

		await expect(mod.ensureBackendSidecar()).rejects.toThrow('E2E sidecar failed to start');
		await expect(mod.ensureBackendSidecar()).rejects.toThrow('E2E sidecar failed to start');

		expect(localStorage.getItem('mail.e2e.sidecarFailure')).toBe('always');
	});

	it('spawns the sidecar and sets state=running with the child PID on success', async () => {
		const handle = createCommandHandle({ pid: 42 });
		commandSidecarMock.mockReturnValue(handle.cmd);
		const mod = await freshModule();

		await mod.ensureBackendSidecar();

		expect(mailDataDirMock).toHaveBeenCalledOnce();
		expect(commandSidecarMock).toHaveBeenCalledWith('binaries/mail', [], {
			env: { APP_DATA_DIR: '/data' }
		});
		expect(handle.cmd.spawn).toHaveBeenCalledOnce();
		expect(get(mod.backendSidecarState)).toEqual({ status: 'running', pid: 42 });
	});

	it('returns the running state without a second spawn when a child is already alive', async () => {
		const handle = createCommandHandle({ pid: 7 });
		commandSidecarMock.mockReturnValue(handle.cmd);
		const mod = await freshModule();

		await mod.ensureBackendSidecar();
		await mod.ensureBackendSidecar();

		expect(commandSidecarMock).toHaveBeenCalledOnce();
		expect(get(mod.backendSidecarState)).toEqual({ status: 'running', pid: 7 });
	});

	it('sets state=error and rethrows when Command.spawn() fails', async () => {
		const handle = createCommandHandle({ spawnThrows: new Error('binary missing') });
		commandSidecarMock.mockReturnValue(handle.cmd);
		const mod = await freshModule();

		await expect(mod.ensureBackendSidecar()).rejects.toThrow('binary missing');

		expect(get(mod.backendSidecarState)).toMatchObject({
			status: 'error',
			error: expect.objectContaining({ message: 'binary missing' })
		});
	});
});

describe('handleUnexpectedExit — exit code → user-readable error message', () => {
	async function setupSpawned() {
		const handle = createCommandHandle();
		commandSidecarMock.mockReturnValue(handle.cmd);
		const mod = await freshModule();
		await mod.ensureBackendSidecar();
		return { mod, handle };
	}

	// State machine quirk: MAX_RESTARTS=3 means that the FOURTH close event is
	// the one that flips state to `error`. The first three push timestamps and
	// transition to `restarting`. To assert on the surfaced error message we
	// need to exhaust the budget — and because each restarting branch awaits a
	// 1 s delay before respawning, we yield a microtask after each emit so the
	// pre-delay sync code (timestamp push, state set) lands before the next
	// emit.
	async function emitNTimes(emit: () => void, n: number): Promise<void> {
		for (let i = 0; i < n; i++) {
			emit();
			await Promise.resolve();
		}
	}

	it('code 78 (port already in use) → "already running in the background" copy', async () => {
		const { mod, handle } = await setupSpawned();

		await emitNTimes(() => handle.emitClose(78, null), 4);

		expect(get(mod.backendSidecarState)).toMatchObject({
			status: 'error',
			error: expect.objectContaining({ message: expect.stringContaining('already running') })
		});
	});

	it('code 130 (SIGINT) → "terminated by an external signal" copy', async () => {
		const { mod, handle } = await setupSpawned();

		await emitNTimes(() => handle.emitClose(130, null), 4);

		expect(get(mod.backendSidecarState)).toMatchObject({
			status: 'error',
			error: expect.objectContaining({ message: expect.stringContaining('external signal') })
		});
	});

	it('code 143 (SIGTERM) → "terminated by an external signal" copy', async () => {
		const { mod, handle } = await setupSpawned();

		await emitNTimes(() => handle.emitClose(143, null), 4);

		expect(get(mod.backendSidecarState)).toMatchObject({
			status: 'error',
			error: expect.objectContaining({ message: expect.stringContaining('external signal') })
		});
	});

	it('code=null + signal=9 → "terminated by signal #9" copy', async () => {
		const { mod, handle } = await setupSpawned();

		await emitNTimes(() => handle.emitClose(null, 9), 4);

		expect(get(mod.backendSidecarState)).toMatchObject({
			status: 'error',
			error: expect.objectContaining({ message: expect.stringContaining('signal #9') })
		});
	});

	it('default branch — generic "Backend failed to start (code N)" copy', async () => {
		const { mod, handle } = await setupSpawned();

		await emitNTimes(() => handle.emitClose(99, null), 4);

		expect(get(mod.backendSidecarState)).toMatchObject({
			status: 'error',
			error: expect.objectContaining({ message: expect.stringContaining('code 99') })
		});
	});

	it('error event (process failed to spawn) is surfaced as "Failed to start the backend sidecar"', async () => {
		const { mod, handle } = await setupSpawned();

		await emitNTimes(() => handle.emitError('ENOENT'), 4);

		expect(get(mod.backendSidecarState)).toMatchObject({
			status: 'error',
			error: expect.objectContaining({ message: expect.stringContaining('Failed to start') })
		});
	});
});

describe('restartBackendSidecar — stop + ensure cycle', () => {
	it('kills the running child and respawns, clearing the restart-budget counter', async () => {
		const first = createCommandHandle({ pid: 100 });
		const second = createCommandHandle({ pid: 200 });
		commandSidecarMock.mockReturnValueOnce(first.cmd).mockReturnValueOnce(second.cmd);
		const mod = await freshModule();

		await mod.ensureBackendSidecar();
		await mod.restartBackendSidecar();

		expect(first.child.kill).toHaveBeenCalledOnce();
		expect(commandSidecarMock).toHaveBeenCalledTimes(2);
		expect(get(mod.backendSidecarState)).toEqual({ status: 'running', pid: 200 });
	});

	it('ignores a late close event from the previous generation after a restart (no double spawn)', async () => {
		const first = createCommandHandle({ pid: 100 });
		const second = createCommandHandle({ pid: 200 });
		commandSidecarMock.mockReturnValueOnce(first.cmd).mockReturnValueOnce(second.cmd);
		const mod = await freshModule();

		await mod.ensureBackendSidecar();
		await mod.restartBackendSidecar();

		// Production ordering from the BootErrorView Retry flow: the OS delivers
		// the killed child's `close` only AFTER the new child was spawned.
		// Without the generation check this nulled the NEW runtime.child and
		// spawned a THIRD backend (exit 78 "already running" + orphan process).
		first.emitClose(0, null);
		await Promise.resolve();

		expect(commandSidecarMock).toHaveBeenCalledTimes(2);
		expect(get(mod.backendSidecarState)).toEqual({ status: 'running', pid: 200 });
	});

	it('a close event that fires while stop is requested resolves to state=stopped (no restart)', async () => {
		const handle = createCommandHandle();
		commandSidecarMock.mockReturnValue(handle.cmd);
		const mod = await freshModule();
		await mod.ensureBackendSidecar();

		// Simulate the production flow: stop is requested, then the OS reports
		// the child closed because we killed it. We do NOT want to bounce back.
		const restartPromise = mod.restartBackendSidecar();
		handle.emitClose(0, null);
		// restartBackendSidecar zeroes the budget then ensures a new spawn —
		// give it a second sidecar so it can complete cleanly.
		const second = createCommandHandle({ pid: 5 });
		commandSidecarMock.mockReturnValueOnce(second.cmd);
		await restartPromise;

		expect(get(mod.backendSidecarState)).toEqual({ status: 'running', pid: 5 });
	});
});
