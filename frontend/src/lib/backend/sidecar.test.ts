// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { get } from 'svelte/store';

const {
	isTauriMock,
	invokeMock,
	mailDataDirMock,
	commandSidecarMock,
	existsMock,
	removeMock,
	joinMock
} = vi.hoisted(() => ({
	isTauriMock: vi.fn<() => boolean>(),
	invokeMock: vi.fn<(command: string, args?: unknown) => Promise<unknown>>(),
	mailDataDirMock: vi.fn<() => Promise<string>>(),
	commandSidecarMock: vi.fn(),
	existsMock: vi.fn<(path: string) => Promise<boolean>>(),
	removeMock: vi.fn<(path: string) => Promise<void>>(),
	joinMock: vi.fn<(...segments: string[]) => Promise<string>>()
}));

vi.mock('@tauri-apps/api/core', () => ({ isTauri: isTauriMock, invoke: invokeMock }));
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
	invokeMock.mockReset();
	invokeMock.mockResolvedValue(undefined);
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

	it('fails once as a backend exit when the failure mode names an exit code', async () => {
		vi.stubEnv('VITE_E2E_MOCK', '1');
		localStorage.setItem('mail.e2e.sidecarFailure', 'exit:65');
		const mod = await freshModule();

		await expect(mod.ensureBackendSidecar()).rejects.toMatchObject({
			messageKey: 'app.backendExit.databaseDamaged'
		});

		expect(localStorage.getItem('mail.e2e.sidecarFailure')).toBeNull();
		expect(commandSidecarMock).not.toHaveBeenCalled();
	});

	it('spawns the sidecar and sets state=running with the child PID on success', async () => {
		const handle = createCommandHandle({ pid: 42 });
		commandSidecarMock.mockReturnValue(handle.cmd);
		const mod = await freshModule();

		await mod.ensureBackendSidecar();

		expect(mailDataDirMock).toHaveBeenCalledOnce();
		expect(commandSidecarMock).toHaveBeenCalledWith('binaries/voxrox-mail-backend', [], {
			encoding: 'utf-8',
			env: { APP_DATA_DIR: '/data', MAIL_SIDECAR_WATCH_PARENT: '1' }
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

	it('code -1 (Stop-Process on Windows) reads as terminated, not as a failed start', async () => {
		const { mod, handle } = await setupSpawned();

		await emitNTimes(() => handle.emitClose(-1, null), 4);

		expect(get(mod.backendSidecarState)).toMatchObject({
			status: 'error',
			error: { messageKey: 'app.backendExit.terminated', values: { code: -1 } }
		});
	});

	it('code 1 stays a failed start: a JVM that cannot be created ends with it, as does taskkill /F', async () => {
		const { mod, handle } = await setupSpawned();

		await emitNTimes(() => handle.emitClose(1, null), 4);

		expect(get(mod.backendSidecarState)).toMatchObject({
			status: 'error',
			error: { messageKey: 'app.backendExit.failed', values: { code: '1' } }
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

	it.each([
		[65, 'app.backendExit.databaseDamaged'],
		[70, 'app.backendExit.schemaMismatch']
	])(
		'code %i is permanent: the error shows at once, with no restart spent on it',
		async (code, messageKey) => {
			const { mod, handle } = await setupSpawned();

			handle.emitClose(code, null);
			await Promise.resolve();

			const state = get(mod.backendSidecarState);
			expect(state).toMatchObject({ status: 'error', error: { messageKey } });
			// One spawn: the one setupSpawned made. A restart would have spawned again.
			expect(commandSidecarMock).toHaveBeenCalledTimes(1);
		}
	);

	it('writes what the sidecar puts on stderr and how it exits into the shell log', async () => {
		const { handle } = await setupSpawned();
		const onStderr = handle.cmd.stderr.on.mock.calls.find(([event]) => event === 'data')?.[1] as (
			line: string
		) => void;

		onStderr('Error: Could not find or load main class');
		handle.emitClose(1, null);
		await Promise.resolve();

		expect(invokeMock).toHaveBeenCalledWith('log_sidecar_stderr', {
			line: 'Error: Could not find or load main class'
		});
		expect(invokeMock).toHaveBeenCalledWith('log_sidecar_exit', {
			code: 1,
			signal: null,
			requested: false
		});
	});

	it('a shell log that refuses the line does not stop the restart', async () => {
		invokeMock.mockRejectedValue(new Error('not allowed'));
		const { mod, handle } = await setupSpawned();

		handle.emitClose(1, null);
		await Promise.resolve();

		expect(get(mod.backendSidecarState)).toMatchObject({ status: 'restarting' });
	});

	it('code 74 (storage unavailable) may clear up, so it goes through the restart budget first', async () => {
		const { mod, handle } = await setupSpawned();

		handle.emitClose(74, null);
		await Promise.resolve();
		expect(get(mod.backendSidecarState)).toMatchObject({ status: 'restarting' });

		await emitNTimes(() => handle.emitClose(74, null), 3);
		expect(get(mod.backendSidecarState)).toMatchObject({
			status: 'error',
			error: { messageKey: 'app.backendExit.storageUnavailable' }
		});
	});

	it('code 3 (the JVM exit on OutOfMemoryError) restarts first, then reads as out of memory', async () => {
		const { mod, handle } = await setupSpawned();

		// The packaged launcher's -XX:+ExitOnOutOfMemoryError. Restarting is the
		// whole point of exiting, so it must not be treated as permanent — and the
		// error the user eventually reads must not say the backend failed to start.
		handle.emitClose(3, null);
		await Promise.resolve();
		expect(get(mod.backendSidecarState)).toMatchObject({ status: 'restarting' });

		await emitNTimes(() => handle.emitClose(3, null), 3);
		expect(get(mod.backendSidecarState)).toMatchObject({
			status: 'error',
			error: { messageKey: 'app.backendExit.outOfMemory' }
		});
	});

	it('the generic exit carries its code into the translated message', async () => {
		const { mod, handle } = await setupSpawned();

		await emitNTimes(() => handle.emitClose(99, null), 4);

		const state = get(mod.backendSidecarState);
		expect(state.status).toBe('error');
		if (state.status !== 'error') return;
		expect(mod.isSidecarExitError(state.error)).toBe(true);
		expect(state.error).toMatchObject({
			messageKey: 'app.backendExit.failed',
			values: { code: '99' }
		});
	});

	it('isSidecarExitError tells a keyed exit from any other error', async () => {
		const mod = await freshModule();

		expect(mod.isSidecarExitError(new mod.SidecarExitError('x', 'app.backendExit.failed'))).toBe(
			true
		);
		expect(mod.isSidecarExitError(new Error('x'))).toBe(false);
	});

	it('error events that exhaust the restart budget surface as "lost track of the backend"', async () => {
		const { mod, handle } = await setupSpawned();

		await emitNTimes(() => handle.emitError('broken pipe'), 4);

		expect(get(mod.backendSidecarState)).toMatchObject({
			status: 'error',
			error: expect.objectContaining({
				message: expect.stringContaining('lost track of the backend sidecar'),
				messageKey: 'app.backendExit.spawnFailed'
			})
		});
	});

	it('an error event kills the process it came from before the restart replaces it', async () => {
		const { mod, handle } = await setupSpawned();

		// What the plugin sent for a stdout line that was not UTF-8. The JVM was
		// running all along, and every restart left one more backend behind.
		handle.emitError('invalid utf-8 sequence of 1 bytes from index 98');
		await Promise.resolve();

		expect(handle.child.kill).toHaveBeenCalledOnce();
		expect(invokeMock).toHaveBeenCalledWith('log_sidecar_stderr', {
			line: 'Shell plugin error: invalid utf-8 sequence of 1 bytes from index 98'
		});
		expect(get(mod.backendSidecarState)).toEqual({ status: 'restarting', attempt: 1 });
	});

	it('the close that kill produces is logged as asked for and not counted as a second exit', async () => {
		const { mod, handle } = await setupSpawned();

		handle.emitError('broken pipe');
		await Promise.resolve();
		handle.emitClose(1, null);
		await Promise.resolve();

		expect(invokeMock).toHaveBeenCalledWith('log_sidecar_exit', {
			code: 1,
			signal: null,
			requested: true
		});
		expect(get(mod.backendSidecarState)).toEqual({ status: 'restarting', attempt: 1 });
	});
});

/*
 * The backend's crash guard (SyncCrashGuard.java) counts a sync the previous
 * process died in only when the shell says it restarted the backend after an
 * exit nobody asked for. Quitting and reloading kill the backend too, so a
 * variable set on every spawn would pause an account whose long first sync the
 * user merely closed the app on.
 */
describe('the previous exit handed to the next backend', () => {
	const envOf = (call: number) =>
		(commandSidecarMock.mock.calls[call]?.[2] as { env: Record<string, string> }).env;

	afterEach(() => {
		vi.useRealTimers();
	});

	it('an exit nobody asked for reaches the restarted backend, once', async () => {
		vi.useFakeTimers();
		const first = createCommandHandle({ pid: 1 });
		const second = createCommandHandle({ pid: 2 });
		const third = createCommandHandle({ pid: 3 });
		commandSidecarMock
			.mockReturnValueOnce(first.cmd)
			.mockReturnValueOnce(second.cmd)
			.mockReturnValueOnce(third.cmd);
		const mod = await freshModule();
		await mod.ensureBackendSidecar();

		first.emitClose(3, null);
		await vi.advanceTimersByTimeAsync(1_000);

		expect(envOf(0)).not.toHaveProperty('MAIL_SIDECAR_PREVIOUS_EXIT');
		expect(envOf(1)).toMatchObject({ MAIL_SIDECAR_PREVIOUS_EXIT: '3' });

		// A restart the user asks for afterwards describes nothing new.
		await mod.restartBackendSidecar();
		expect(envOf(2)).not.toHaveProperty('MAIL_SIDECAR_PREVIOUS_EXIT');
	});

	it('a stop the shell asked for hands nothing over', async () => {
		const first = createCommandHandle({ pid: 1 });
		const second = createCommandHandle({ pid: 2 });
		commandSidecarMock.mockReturnValueOnce(first.cmd).mockReturnValueOnce(second.cmd);
		const mod = await freshModule();
		await mod.ensureBackendSidecar();

		await mod.stopBackendSidecar();
		first.emitClose(1, null);
		await mod.ensureBackendSidecar();

		expect(envOf(1)).not.toHaveProperty('MAIL_SIDECAR_PREVIOUS_EXIT');
	});

	it('a Retry after the restart budget is spent still carries the last exit', async () => {
		vi.useFakeTimers();
		const handles = Array.from({ length: 5 }, (_, i) => createCommandHandle({ pid: i + 1 }));
		for (const handle of handles) commandSidecarMock.mockReturnValueOnce(handle.cmd);
		const mod = await freshModule();
		await mod.ensureBackendSidecar();

		for (let i = 0; i < 4; i++) {
			handles[i]!.emitClose(3, null);
			await vi.advanceTimersByTimeAsync(1_000);
		}
		expect(get(mod.backendSidecarState)).toMatchObject({ status: 'error' });

		await mod.restartBackendSidecar();
		expect(envOf(4)).toMatchObject({ MAIL_SIDECAR_PREVIOUS_EXIT: '3' });
	});

	it('an error event hands nothing over: the kill it makes is the shell’s own', async () => {
		vi.useFakeTimers();
		const first = createCommandHandle({ pid: 1 });
		const second = createCommandHandle({ pid: 2 });
		commandSidecarMock.mockReturnValueOnce(first.cmd).mockReturnValueOnce(second.cmd);
		const mod = await freshModule();
		await mod.ensureBackendSidecar();

		first.emitError('broken pipe');
		await vi.advanceTimersByTimeAsync(1_000);
		first.emitClose(1, null);

		expect(first.child.kill).toHaveBeenCalledOnce();
		expect(envOf(1)).not.toHaveProperty('MAIL_SIDECAR_PREVIOUS_EXIT');
	});

	// 65 and 70 are the two a restart cannot fix, so only a Retry reaches the
	// next backend after them.
	it.each([65, 70])(
		'a start refused with %i passes on the exit it was handed, for the Retry after it',
		async (code) => {
			vi.useFakeTimers();
			const handles = Array.from({ length: 3 }, (_, i) => createCommandHandle({ pid: i + 1 }));
			for (const handle of handles) commandSidecarMock.mockReturnValueOnce(handle.cmd);
			const mod = await freshModule();
			await mod.ensureBackendSidecar();

			handles[0]!.emitClose(3, null);
			await vi.advanceTimersByTimeAsync(1_000);
			expect(envOf(1)).toMatchObject({ MAIL_SIDECAR_PREVIOUS_EXIT: '3' });

			// Refused before its crash guard ran (a damaged database, a schema
			// mismatch), so it counted nothing.
			handles[1]!.emitClose(code, null);
			await Promise.resolve();
			expect(get(mod.backendSidecarState)).toMatchObject({ status: 'error' });

			await mod.restartBackendSidecar();
			expect(envOf(2)).toMatchObject({ MAIL_SIDECAR_PREVIOUS_EXIT: '3' });
		}
	);

	// 74 and 78 are restarted automatically, unlike 65 and 70, but are decided
	// before the crash guard runs all the same (B1-23).
	it.each([74, 78])(
		'a start refused with %i passes on the exit it was handed, not its own',
		async (code) => {
			vi.useFakeTimers();
			const handles = Array.from({ length: 3 }, (_, i) => createCommandHandle({ pid: i + 1 }));
			for (const handle of handles) commandSidecarMock.mockReturnValueOnce(handle.cmd);
			const mod = await freshModule();
			await mod.ensureBackendSidecar();

			handles[0]!.emitClose(3, null);
			await vi.advanceTimersByTimeAsync(1_000);
			handles[1]!.emitClose(code, null);
			await vi.advanceTimersByTimeAsync(1_000);

			expect(envOf(2)).toMatchObject({ MAIL_SIDECAR_PREVIOUS_EXIT: '3' });
		}
	);

	it('a start refused with 74 after a Restart hands nothing over', async () => {
		vi.useFakeTimers();
		const handles = Array.from({ length: 3 }, (_, i) => createCommandHandle({ pid: i + 1 }));
		for (const handle of handles) commandSidecarMock.mockReturnValueOnce(handle.cmd);
		const mod = await freshModule();
		await mod.ensureBackendSidecar();

		await mod.restartBackendSidecar();
		handles[1]!.emitClose(74, null);
		await vi.advanceTimersByTimeAsync(1_000);

		expect(envOf(2)).not.toHaveProperty('MAIL_SIDECAR_PREVIOUS_EXIT');
	});

	/*
	 * The kill a Restart makes returns once the process has exited, so its
	 * `close` arrives while the next spawn is still on its way to spawning.
	 * B1-23: taken there for an exit nobody asked for, it reached the next
	 * backend as a crash, and three Restarts during a sync paused the account.
	 * Held at each of the spawn's awaits in turn, the first one included: the
	 * generation has to move before it, not only before the handshake cleanup.
	 */
	it.each(['resolving the data directory', 'clearing the handshake files'] as const)(
		'the kill a Restart makes is not handed over when its close arrives while the next spawn is %s',
		async (step) => {
			const first = createCommandHandle({ pid: 1 });
			const second = createCommandHandle({ pid: 2 });
			commandSidecarMock.mockReturnValueOnce(first.cmd).mockReturnValueOnce(second.cmd);
			const mod = await freshModule();
			await mod.ensureBackendSidecar();

			let release!: () => void;
			const held = step === 'resolving the data directory' ? mailDataDirMock : existsMock;
			if (held === mailDataDirMock) {
				const dataDir = new Promise<string>((resolve) => {
					release = () => resolve('/data');
				});
				mailDataDirMock.mockClear().mockReturnValueOnce(dataDir);
			} else {
				const cleanup = new Promise<boolean>((resolve) => {
					release = () => resolve(false);
				});
				existsMock.mockClear().mockReturnValue(cleanup);
			}

			const restart = mod.restartBackendSidecar();
			await vi.waitFor(() => expect(held).toHaveBeenCalled());
			first.emitClose(1, null);
			release();
			await restart;

			expect(envOf(1)).not.toHaveProperty('MAIL_SIDECAR_PREVIOUS_EXIT');
			expect(invokeMock).toHaveBeenCalledWith('log_sidecar_exit', {
				code: 1,
				signal: null,
				requested: true
			});
			expect(commandSidecarMock).toHaveBeenCalledTimes(2);
			expect(get(mod.backendSidecarState)).toEqual({ status: 'running', pid: 2 });
		}
	);

	/*
	 * A Restart pressed while the automatic restart is still spawning finds no
	 * child to kill, so its stop only sets `stopRequested`, and its ensure joins
	 * the spawn under way. Left set, the flag had the backend that spawn starts
	 * read as stopped on its next exit: not restarted, and the exit not handed on.
	 */
	it('a Restart during the automatic restart’s spawn leaves the backend it starts supervised', async () => {
		vi.useFakeTimers();
		const handles = Array.from({ length: 3 }, (_, i) => createCommandHandle({ pid: i + 1 }));
		for (const handle of handles) commandSidecarMock.mockReturnValueOnce(handle.cmd);
		const mod = await freshModule();
		await mod.ensureBackendSidecar();

		let releaseCleanup!: () => void;
		const cleanup = new Promise<boolean>((resolve) => {
			releaseCleanup = () => resolve(false);
		});
		existsMock.mockClear().mockReturnValue(cleanup);

		handles[0]!.emitClose(3, null);
		await vi.advanceTimersByTimeAsync(1_000);
		expect(existsMock).toHaveBeenCalled();
		expect(get(mod.backendSidecarState)).toEqual({ status: 'starting' });

		const restart = mod.restartBackendSidecar();
		releaseCleanup();
		await restart;
		expect(get(mod.backendSidecarState)).toEqual({ status: 'running', pid: 2 });
		// The spawn the Restart joined still carries the crash before it.
		expect(envOf(1)).toMatchObject({ MAIL_SIDECAR_PREVIOUS_EXIT: '3' });

		handles[1]!.emitClose(1, null);
		await vi.advanceTimersByTimeAsync(1_000);

		expect(invokeMock).toHaveBeenCalledWith('log_sidecar_exit', {
			code: 1,
			signal: null,
			requested: false
		});
		expect(commandSidecarMock).toHaveBeenCalledTimes(3);
		expect(envOf(2)).toMatchObject({ MAIL_SIDECAR_PREVIOUS_EXIT: '1' });
		expect(get(mod.backendSidecarState)).toEqual({ status: 'running', pid: 3 });
	});

	// The same window by a second Restart pressed before the first one's spawn
	// is through: bootstrap({ force: true }) does not wait for the attempt it
	// replaces.
	it('a second Restart during the first one’s spawn leaves the backend it starts supervised', async () => {
		vi.useFakeTimers();
		const handles = Array.from({ length: 3 }, (_, i) => createCommandHandle({ pid: i + 1 }));
		for (const handle of handles) commandSidecarMock.mockReturnValueOnce(handle.cmd);
		const mod = await freshModule();
		await mod.ensureBackendSidecar();

		let releaseCleanup!: () => void;
		const cleanup = new Promise<boolean>((resolve) => {
			releaseCleanup = () => resolve(false);
		});
		existsMock.mockClear().mockReturnValue(cleanup);

		const firstRestart = mod.restartBackendSidecar();
		await vi.waitFor(() => expect(existsMock).toHaveBeenCalled());
		const secondRestart = mod.restartBackendSidecar();
		releaseCleanup();
		await Promise.all([firstRestart, secondRestart]);
		expect(get(mod.backendSidecarState)).toEqual({ status: 'running', pid: 2 });

		handles[1]!.emitClose(1, null);
		await vi.advanceTimersByTimeAsync(1_000);

		expect(invokeMock).toHaveBeenCalledWith('log_sidecar_exit', {
			code: 1,
			signal: null,
			requested: false
		});
		expect(commandSidecarMock).toHaveBeenCalledTimes(3);
		expect(envOf(2)).toMatchObject({ MAIL_SIDECAR_PREVIOUS_EXIT: '1' });
		expect(get(mod.backendSidecarState)).toEqual({ status: 'running', pid: 3 });
	});
});

describe('unload hook — only a leave that goes ahead stops the backend', () => {
	afterEach(() => {
		const global = globalThis as { __MAIL_BACKEND_SIDECAR_UNLOAD__?: () => void };
		global.__MAIL_BACKEND_SIDECAR_UNLOAD__?.();
		delete global.__MAIL_BACKEND_SIDECAR_UNLOAD__;
	});

	/** jsdom builds no PageTransitionEvent, and the hook reads only `persisted`. */
	function dispatchPageHide(persisted = false): void {
		const event = new Event('pagehide');
		Object.defineProperty(event, 'persisted', { value: persisted });
		window.dispatchEvent(event);
	}

	async function spawned(pid: number) {
		const handle = createCommandHandle({ pid });
		commandSidecarMock.mockReturnValue(handle.cmd);
		const mod = await freshModule();
		await mod.ensureBackendSidecar();
		return { mod, handle };
	}

	it('keeps the backend running while a leave is only being attempted', async () => {
		const { mod, handle } = await spawned(301);

		// The event a leave guard cancels in. Whether it was cancelled or not is
		// not this hook's business any more — nothing stops here either way.
		window.dispatchEvent(new Event('beforeunload', { cancelable: true }));
		await Promise.resolve();

		expect(handle.child.kill).not.toHaveBeenCalled();
		expect(get(mod.backendSidecarState)).toEqual({ status: 'running', pid: 301 });
	});

	it('stops the backend once the page is really unloaded', async () => {
		const { handle } = await spawned(302);

		dispatchPageHide();

		expect(handle.child.kill).toHaveBeenCalledOnce();
	});

	it('stays armed through a cancelled leave, so the next real one still stops it', async () => {
		const { handle } = await spawned(303);

		window.dispatchEvent(new Event('beforeunload', { cancelable: true }));
		expect(handle.child.kill).not.toHaveBeenCalled();

		dispatchPageHide();

		expect(handle.child.kill).toHaveBeenCalledOnce();
	});

	it('keeps the backend when the page is only frozen into the back/forward cache', async () => {
		const { mod, handle } = await spawned(304);

		dispatchPageHide(true);
		await Promise.resolve();

		expect(handle.child.kill).not.toHaveBeenCalled();
		expect(get(mod.backendSidecarState)).toEqual({ status: 'running', pid: 304 });
	});

	it('takes its listener with it when a reloaded module replaces it', async () => {
		const first = await spawned(305);
		const second = await spawned(306);

		dispatchPageHide();

		expect(first.handle.child.kill).not.toHaveBeenCalled();
		expect(second.handle.child.kill).toHaveBeenCalledOnce();
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
