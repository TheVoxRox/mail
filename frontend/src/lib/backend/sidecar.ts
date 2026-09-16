import { isTauri } from '@tauri-apps/api/core';
import { join } from '@tauri-apps/api/path';
import { Command, type Child } from '@tauri-apps/plugin-shell';
import { exists, remove } from '@tauri-apps/plugin-fs';
import { writable } from 'svelte/store';
import { toError } from '$lib/api/errors.js';
import { delayWithAbort } from '$lib/delay.js';
import { mailDataDir } from './data-dir';

const SIDECAR_PROGRAM = 'binaries/voxrox-mail-backend';
const MAX_RESTARTS = 3;
const RESTART_WINDOW_MS = 60_000;
const RESTART_DELAY_MS = 1_000;
const E2E_SIDECAR_FAILURE_KEY = 'mail.e2e.sidecarFailure';

/*
 * Mirrors the file names from backend StorageContextInitializer and
 * frontend/src/lib/api/session.ts. Kept locally so we can wipe stale handshake
 * state independently — without this, the frontend reads the previous run's
 * files BEFORE the backend JVM finishes starting (Spring takes 2–5 s, but
 * frontend polling runs every 200 ms — a race condition where the client
 * connects to the port of a dead backend from the previous run).
 */
const SESSION_FILE_NAME = 'session.json';
const READY_FILE_NAME = '.ready';

export interface HandshakeFilesFs {
	exists: (path: string) => Promise<boolean>;
	remove: (path: string) => Promise<void>;
	join: (...segments: string[]) => Promise<string>;
}

const defaultHandshakeFs: HandshakeFilesFs = { exists, remove, join };

/**
 * Removes stale `.ready` and `session.json` from dataDir before spawning the
 * backend.
 *
 * The backend wipes only `.ready` at boot (`StorageContextInitializer`
 * deletes it; `session.json` stays and is overwritten later by
 * `HandshakeService` — harmless, because the readiness gate is `.ready`),
 * and even that runs only after the JVM has started (~2–5 s). Meanwhile the
 * frontend (200 ms polling in `loadSession`) would find the previous run's
 * files and start talking to a port nobody is listening on — the result is a
 * `BootTimeoutError` after 60 s.
 *
 * FS dependencies are injected only for unit tests; production code uses the
 * default via `@tauri-apps/plugin-fs`.
 *
 * Tauri capabilities (src-tauri/capabilities/default.json) must allow
 * `fs:allow-exists`, `fs:allow-read-text-file` (for loadSession), and
 * `fs:allow-remove` (for this cleanup). `fs:scope` whitelists the specific
 * paths to `.ready` + `session.json`, so remove() cannot delete anything else.
 */
export async function clearStaleHandshakeFiles(
	dataDir: string,
	fs: HandshakeFilesFs = defaultHandshakeFs
): Promise<void> {
	await Promise.all(
		[READY_FILE_NAME, SESSION_FILE_NAME].map(async (name) => {
			const filePath = await fs.join(dataDir, name);
			if (await fs.exists(filePath)) {
				await fs.remove(filePath);
			}
		})
	);
}

type BackendSidecarState =
	| { status: 'idle' }
	| { status: 'disabled' }
	| { status: 'starting' }
	| { status: 'running'; pid: number }
	| { status: 'restarting'; attempt: number }
	| { status: 'stopped' }
	| { status: 'error'; error: Error };

export const backendSidecarState = writable<BackendSidecarState>({ status: 'idle' });

interface BackendSidecarRuntime {
	child: Child | null;
	startPromise: Promise<void> | null;
	stopRequested: boolean;
	restartTimestamps: number[];
	shutdownHookRegistered: boolean;
	/**
	 * Monotonic spawn counter. Each spawn bumps it and bakes the new value into
	 * that process's close/error handler closures, so a late event from an
	 * already-replaced process can be recognized and ignored — see
	 * `handleUnexpectedExit`.
	 */
	generation: number;
}

type BackendSidecarGlobal = typeof globalThis & {
	__MAIL_BACKEND_SIDECAR__?: BackendSidecarRuntime;
	/** Removes the unload hook of whichever module instance installed it. */
	__MAIL_BACKEND_SIDECAR_UNLOAD__?: () => void;
};

const runtime = resolveRuntime();

function resolveRuntime(): BackendSidecarRuntime {
	const global = globalThis as BackendSidecarGlobal;
	global.__MAIL_BACKEND_SIDECAR__ ??= {
		child: null,
		startPromise: null,
		stopRequested: false,
		restartTimestamps: [],
		shutdownHookRegistered: false,
		generation: 0
	};
	// A runtime created before the generation field existed can survive an HMR
	// reload on the global — backfill so `++` never produces NaN.
	global.__MAIL_BACKEND_SIDECAR__.generation ??= 0;
	return global.__MAIL_BACKEND_SIDECAR__;
}

export function usesBackendSidecar(): boolean {
	return (
		isTauri() &&
		import.meta.env.VITE_E2E_MOCK !== '1' &&
		import.meta.env.VITE_BACKEND_SIDECAR !== '0'
	);
}

export async function ensureBackendSidecar(): Promise<void> {
	const e2eFailure = consumeE2ESidecarFailure();
	if (e2eFailure) {
		backendSidecarState.set({ status: 'error', error: e2eFailure });
		throw e2eFailure;
	}

	if (!usesBackendSidecar()) {
		backendSidecarState.set({ status: 'disabled' });
		return;
	}

	if (runtime.child) {
		backendSidecarState.set({ status: 'running', pid: runtime.child.pid });
		return;
	}
	if (runtime.startPromise) return runtime.startPromise;

	runtime.stopRequested = false;
	runtime.startPromise = spawnBackendSidecar().finally(() => {
		runtime.startPromise = null;
	});
	return runtime.startPromise;
}

export async function restartBackendSidecar(): Promise<void> {
	runtime.restartTimestamps = [];
	await stopBackendSidecar();
	await ensureBackendSidecar();
}

/**
 * Kills the sidecar and marks the stop as intentional, so `handleUnexpectedExit`
 * reports `stopped` instead of restarting it.
 *
 * Exported for the update path, which has to free the install directory before
 * the installer runs (see installPromptedUpdate in lib/updates.ts). Everywhere
 * else it is reached through `restartBackendSidecar` or the unload hook
 * (`registerSidecarShutdownHook`).
 */
export async function stopBackendSidecar(): Promise<void> {
	runtime.stopRequested = true;
	const runningChild = runtime.child;
	runtime.child = null;

	if (!runningChild) {
		backendSidecarState.set({ status: 'stopped' });
		return;
	}

	try {
		await runningChild.kill();
	} finally {
		backendSidecarState.set({ status: 'stopped' });
	}
}

/**
 * Stops the sidecar when the page really goes away — a reload, or a quit that
 * unloads the webview — and not when leaving is merely attempted.
 *
 * `pagehide` is the whole hook, and `beforeunload` is deliberately not part of
 * it. Stopping on `beforeunload` was too early: that is the same event in which
 * a leave guard (`installLeaveGuard`, used by the compose and contact forms)
 * cancels the unload, and the webview then asks whether to leave. Choosing to
 * stay kept the page and the unsaved form with the backend already killed and
 * `stopRequested` set, so nothing restarted it and every later request failed
 * without a word.
 *
 * Reading `defaultPrevented` on that event repairs the case but not the design:
 * it holds only while SvelteKit's own `beforeunload` listener — the one that
 * runs the leave guard and cancels — keeps being registered before this one,
 * which is true today by an accident of bootstrap timing and which no gate and
 * no test can pin, since a unit test can only hand-register the order it wants
 * to see. `pagehide` needs no order at all: it fires on every real unload,
 * after the prompt has been answered, and never on a leave that was cancelled.
 *
 * `persisted` is the one case it fires on a page that can still come back: a
 * document frozen into the back/forward cache, which Alt+Left and the browser
 * back key — both deliberately still live, see src-tauri/src/webview_defaults.rs
 * — can restore. Killing the backend there is the same bug by another route.
 *
 * The remover is parked on the global rather than the module so that a module
 * instance being replaced takes its listener with it. `runtime` cannot guard
 * that: it lives on the global too, and both HMR and the unit tests
 * (`vi.resetModules()` plus a wipe of `__MAIL_BACKEND_SIDECAR__`) hand the next
 * instance a fresh one, leaving the old listener bound to a runtime nobody owns.
 */
function registerSidecarShutdownHook(): void {
	if (typeof window === 'undefined') return;
	const global = globalThis as BackendSidecarGlobal;
	global.__MAIL_BACKEND_SIDECAR_UNLOAD__?.();

	const onPageHide = (event: PageTransitionEvent) => {
		if (event.persisted) return;
		void stopBackendSidecar();
	};
	window.addEventListener('pagehide', onPageHide);
	global.__MAIL_BACKEND_SIDECAR_UNLOAD__ = () => {
		window.removeEventListener('pagehide', onPageHide);
	};
}

async function spawnBackendSidecar(): Promise<void> {
	backendSidecarState.set({ status: 'starting' });

	/*
	 * APP_DATA_DIR aligns the backend storage with the frontend handshake
	 * path. The backend reads it via the Spring property `app.data-dir` in
	 * application.properties.
	 */
	const dataDir = await mailDataDir();

	/*
	 * Stale handshake file cleanup MUST run before spawn — see docstring on
	 * `clearStaleHandshakeFiles`. Without it the frontend reads
	 * `.ready`/`session.json` from the previous run and hangs on a dead port.
	 */
	await clearStaleHandshakeFiles(dataDir);

	/*
	 * Captured by the close/error handlers below. After restartBackendSidecar()
	 * kills the old child and spawns a new one, the OLD child's `close` event
	 * can arrive late — without the generation check it would null out the NEW
	 * `runtime.child` and spawn a second backend (exit 78 "already running"
	 * plus an orphaned-but-working process).
	 */
	const generation = ++runtime.generation;

	const command = Command.sidecar(SIDECAR_PROGRAM, [], {
		env: {
			APP_DATA_DIR: dataDir,
			/*
			 * Arms the backend's parent-death watchdog. When this frontend process
			 * goes away — including a force-kill (Task Manager, kill -9, a crash)
			 * that skips stopBackendSidecar() and the unload hook — the OS closes the
			 * sidecar's stdin pipe and the backend self-terminates instead of
			 * lingering as an orphaned JVM holding the ephemeral port and the DB.
			 * See backend core/lifecycle/ParentProcessWatchdog.java.
			 */
			MAIL_SIDECAR_WATCH_PARENT: '1'
		}
	});
	command.stdout.on('data', (line) => console.info(`[mail] ${line}`));
	command.stderr.on('data', (line) => console.warn(`[mail] ${line}`));
	command.on('close', (payload) => {
		void handleUnexpectedExit(
			generation,
			new Error(formatSidecarExitMessage(payload.code, payload.signal))
		);
	});
	command.on('error', (message) => {
		void handleUnexpectedExit(
			generation,
			new Error(`Failed to start the backend sidecar: ${message}`)
		);
	});

	try {
		runtime.child = await command.spawn();
	} catch (err) {
		const error = toError(err);
		backendSidecarState.set({ status: 'error', error });
		throw error;
	}

	if (!runtime.shutdownHookRegistered) {
		registerSidecarShutdownHook();
		runtime.shutdownHookRegistered = true;
	}

	backendSidecarState.set({ status: 'running', pid: runtime.child.pid });
}

async function handleUnexpectedExit(generation: number, error: Error): Promise<void> {
	if (generation !== runtime.generation) {
		// Stale event from a previous process generation — the child it belongs
		// to was already replaced, so there is nothing to clean up or restart.
		return;
	}

	runtime.child = null;

	if (runtime.stopRequested) {
		backendSidecarState.set({ status: 'stopped' });
		return;
	}

	const now = Date.now();
	runtime.restartTimestamps = runtime.restartTimestamps.filter(
		(timestamp) => now - timestamp < RESTART_WINDOW_MS
	);

	if (runtime.restartTimestamps.length >= MAX_RESTARTS) {
		backendSidecarState.set({ status: 'error', error });
		return;
	}

	runtime.restartTimestamps.push(now);
	backendSidecarState.set({ status: 'restarting', attempt: runtime.restartTimestamps.length });
	await delayWithAbort(RESTART_DELAY_MS);

	try {
		await ensureBackendSidecar();
	} catch (err) {
		backendSidecarState.set({
			status: 'error',
			error: toError(err)
		});
	}
}

function consumeE2ESidecarFailure(): Error | null {
	if (import.meta.env.VITE_E2E_MOCK !== '1' || typeof localStorage === 'undefined') {
		return null;
	}

	const mode = localStorage.getItem(E2E_SIDECAR_FAILURE_KEY);
	if (mode === 'once') {
		localStorage.removeItem(E2E_SIDECAR_FAILURE_KEY);
		return new Error('E2E sidecar failed to start');
	}
	if (mode === 'always') {
		return new Error('E2E sidecar failed to start');
	}
	return null;
}

/**
 * Maps a backend sidecar exit code to a user-readable message. Stays
 * consistent with `MailBackendApplication.EXIT_CONFIG = 78` (configuration /
 * startup error — typically an explicitly chosen port that was already
 * occupied).
 *
 * Deliberately does not log the raw `code=X, signal=Y` text — that belongs
 * in the dev console only (stdout/stderr handlers above), not in front of
 * the user.
 *
 * Note: this runs in the pre-i18n boot path (BootErrorView is shown before
 * svelte-i18n initialises), so the copy is in English by design — matches
 * the backend GlobalExceptionHandler English fallback.
 */
function formatSidecarExitMessage(code: number | null, signal: number | null): string {
	if (code === 78) {
		return 'The application is already running in the background. Check the taskbar or Task Manager (process "voxrox-mail-backend"), or wait about 30 seconds and try again.';
	}
	if (code === 130 || code === 143) {
		return `Backend was terminated by an external signal (code ${code}). Try restarting the application.`;
	}
	if (code === null && signal !== null) {
		return `Backend was terminated by signal #${signal}. Try restarting the application.`;
	}
	const codeText = code === null ? 'unknown' : String(code);
	return `Backend failed to start (code ${codeText}). Try restarting the application; if the problem persists, check the logs.`;
}
