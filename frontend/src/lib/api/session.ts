/**
 * Handshake with the backend via session.json.
 *
 * On startup the backend (HandshakeService) writes `session.json` to
 * `mailDataDir()` (via the `APP_DATA_DIR` env set by the sidecar) with the
 * port, API key and baseUrl, followed by the `.ready` sentinel. The frontend
 * waits for both files so it does not connect to a stale session from a
 * previous run.
 */

import { browser } from '$app/environment';
import { exists, readTextFile, watchImmediate, type UnwatchFn } from '@tauri-apps/plugin-fs';
import { join } from '@tauri-apps/api/path';
import { mailDataDir } from '$lib/backend/data-dir';
import { delayWithAbort } from '$lib/delay.js';

export interface SessionPayload {
	appName: string;
	appVersion: string;
	apiVersion: string;
	minClientVersion: string;
	dbSchemaVersion: string;
	port: number;
	apiKey: string;
	/** Base URL written by the backend, ending in `/api` (without /v1). */
	baseUrl: string;
}

export class SessionLoadError extends Error {
	constructor(
		message: string,
		public readonly cause?: unknown
	) {
		super(message);
		this.name = 'SessionLoadError';
	}
}

const SESSION_FILE_NAME = 'session.json';
const READY_FILE_NAME = '.ready';
const LOOPBACK_HOSTS = new Set(['localhost', '127.0.0.1', '[::1]']);

async function resolveSessionPath(): Promise<string> {
	const dataDir = await mailDataDir();
	return join(dataDir, SESSION_FILE_NAME);
}

async function resolveReadyPath(): Promise<string> {
	const dataDir = await mailDataDir();
	return join(dataDir, READY_FILE_NAME);
}

export function parseSession(raw: string): SessionPayload {
	let parsed: unknown;
	try {
		parsed = JSON.parse(raw);
	} catch (err) {
		throw new SessionLoadError('session.json is not valid JSON', err);
	}

	if (typeof parsed !== 'object' || parsed === null) {
		throw new SessionLoadError('session.json is not an object');
	}

	const payload = parsed as Record<string, unknown>;
	const {
		appName,
		appVersion,
		apiVersion,
		minClientVersion,
		dbSchemaVersion,
		port,
		apiKey,
		baseUrl
	} = payload;

	if (typeof appName !== 'string' || appName.length === 0) {
		throw new SessionLoadError('session.json: missing / invalid `appName`');
	}
	if (typeof appVersion !== 'string' || appVersion.length === 0) {
		throw new SessionLoadError('session.json: missing / invalid `appVersion`');
	}
	if (typeof apiVersion !== 'string' || apiVersion.length === 0) {
		throw new SessionLoadError('session.json: missing / invalid `apiVersion`');
	}
	if (typeof minClientVersion !== 'string' || minClientVersion.length === 0) {
		throw new SessionLoadError('session.json: missing / invalid `minClientVersion`');
	}
	if (typeof dbSchemaVersion !== 'string' || dbSchemaVersion.length === 0) {
		throw new SessionLoadError('session.json: missing / invalid `dbSchemaVersion`');
	}
	if (typeof port !== 'number' || !Number.isInteger(port) || port <= 0 || port > 65535) {
		throw new SessionLoadError('session.json: missing / invalid `port`');
	}
	if (typeof apiKey !== 'string' || apiKey.length === 0) {
		throw new SessionLoadError('session.json: missing / invalid `apiKey`');
	}
	if (typeof baseUrl !== 'string' || !baseUrl.startsWith('http')) {
		throw new SessionLoadError('session.json: missing / invalid `baseUrl`');
	}

	validateBackendBaseUrl(baseUrl, port);

	return {
		appName,
		appVersion,
		apiVersion,
		minClientVersion,
		dbSchemaVersion,
		port,
		apiKey,
		baseUrl
	};
}

export function validateBackendBaseUrl(baseUrl: string, port: number): void {
	let url: URL;
	try {
		url = new URL(baseUrl);
	} catch (err) {
		throw new SessionLoadError('session.json: `baseUrl` is not a valid URL', err);
	}

	if (url.protocol !== 'http:') {
		throw new SessionLoadError('session.json: `baseUrl` must use http on loopback');
	}
	if (!LOOPBACK_HOSTS.has(url.hostname)) {
		throw new SessionLoadError('session.json: `baseUrl` must point to a loopback host');
	}
	if (Number(url.port) !== port) {
		throw new SessionLoadError('session.json: `baseUrl` port does not match the `port` field');
	}
	if (url.pathname.replace(/\/+$/, '') !== '/api') {
		throw new SessionLoadError('session.json: `baseUrl` must end with `/api`');
	}
}

function readSessionOverride(): SessionPayload | null {
	if (!browser) return null;
	// E2E backdoor — guarded by a build-time flag, so the production Tauri
	// bundle NEVER sees the override (Vite tree-shakes the whole branch). The
	// value lives in memory on window.__MAIL_TEST_SESSION__ rather than
	// localStorage, so it does not survive reload or a Tauri restart and
	// cannot cause stale-state bugs across backend rebuilds (see "Bug
	// 2026-05-17" in memory).
	if (import.meta.env.VITE_E2E_MOCK !== '1') return null;

	const raw = window.__MAIL_TEST_SESSION__;
	if (!raw) return null;

	try {
		return parseSession(JSON.stringify(raw));
	} catch (err) {
		throw new SessionLoadError('window.__MAIL_TEST_SESSION__ is not a valid session payload', err);
	}
}

export interface LoadSessionOptions {
	/** Number of attempts before giving up. Default 150 (≈ 30 s at 200 ms delay). */
	maxAttempts?: number;
	/** Delay between attempts in ms. Default 200. */
	delayMs?: number;
	/** Abort signal for interrupting the wait. */
	signal?: AbortSignal;
}

/**
 * Waits until the backend writes both `.ready` and session.json, then returns
 * the session contents. Throws SessionLoadError if neither file appears
 * within `maxAttempts * delayMs`.
 *
 * Uses an fs watcher on the data directory to react to backend writes
 * instantly; falls back to plain polling on platforms / capability
 * configurations where watch is unavailable. The polling loop also remains the
 * safety net for the watch-but-no-event case (e.g. watcher attached after the
 * file already existed but before our existence check landed).
 */
export async function loadSession(options: LoadSessionOptions = {}): Promise<SessionPayload> {
	const { maxAttempts = 150, delayMs = 200, signal } = options;
	const override = readSessionOverride();
	if (override) {
		return override;
	}

	const sessionPath = await resolveSessionPath();
	const readyPath = await resolveReadyPath();
	const dataDir = await mailDataDir();

	/*
	 * Wake signal — `wake()` wakes the polling loop instantly. Each await on
	 * `waitForWake()` resolves the next time `wake()` fires. The pattern
	 * collapses the polling cadence to ~0 ms on the happy path (backend writes
	 * the file → watch event → instant re-check) while preserving the
	 * `delayMs` ceiling as a safety net.
	 */
	let resolveWake: () => void = () => {};
	let wakePromise = new Promise<void>((r) => (resolveWake = r));
	const wake = () => {
		const r = resolveWake;
		wakePromise = new Promise<void>((next) => (resolveWake = next));
		r();
	};
	const waitForWake = () => wakePromise;

	let unwatch: UnwatchFn | null = null;
	try {
		unwatch = await watchImmediate(
			dataDir,
			(event) => {
				if (event.paths.some(isHandshakeFile)) wake();
			},
			{ recursive: false }
		);
	} catch {
		/*
		 * Watch unavailable — capability missing, dataDir does not exist yet
		 * (first launch), or a platform-specific limitation. Polling alone
		 * still meets the contract; the user just pays up to one delayMs more
		 * of latency on cold start.
		 */
	}

	try {
		for (let attempt = 0; attempt < maxAttempts; attempt++) {
			if (signal?.aborted) {
				throw new SessionLoadError('Loading session.json was aborted');
			}

			if ((await exists(readyPath)) && (await exists(sessionPath))) {
				const raw = await readTextFile(sessionPath);
				return parseSession(raw);
			}

			await Promise.race([delayWithAbort(delayMs, signal), waitForWake()]);
		}

		throw new SessionLoadError(
			`.ready/session.json not found at ${sessionPath} after ${maxAttempts} attempts – is the backend running?`
		);
	} finally {
		try {
			unwatch?.();
		} catch {
			// best-effort cleanup; the watcher dies with the page anyway
		}
	}
}

function isHandshakeFile(path: string): boolean {
	/*
	 * Compare on basename — watcher emits absolute paths and we only care
	 * about the two handshake files (.ready / session.json), never about
	 * other files the backend may write to the data dir (db/, logs/,
	 * attachments/) which would otherwise wake the loop for no reason.
	 */
	const slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
	const name = slash >= 0 ? path.slice(slash + 1) : path;
	return name === SESSION_FILE_NAME || name === READY_FILE_NAME;
}

/** Builds the base URL for `/api/v1/*` endpoints from session.json baseUrl. */
export function apiV1Base(session: SessionPayload): string {
	return `${session.baseUrl.replace(/\/+$/, '')}/v1`;
}
