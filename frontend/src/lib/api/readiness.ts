import { httpFetch } from './http.js';
import { apiV1Base, type SessionPayload } from './session.js';
import { toErrorMessage } from './errors.js';
import { CLIENT_VERSION, SUPPORTED_API_MAJOR } from '$lib/version.js';
import { delayWithAbort } from '$lib/delay.js';

type ReadinessPhase = 'READY' | string;

export interface SystemReadinessResponse {
	ready: boolean;
	phase: ReadinessPhase;
	appName: string;
	appVersion: string;
	apiVersion: string;
	minClientVersion: string;
	dbSchemaVersion: string;
	reason?: string | null;
}

export class BackendReadinessError extends Error {
	constructor(message: string) {
		super(message);
		this.name = 'BackendReadinessError';
	}
}

export class BackendCompatibilityError extends Error {
	constructor(message: string) {
		super(message);
		this.name = 'BackendCompatibilityError';
	}
}

interface Semver {
	major: number;
	minor: number;
	patch: number;
}

interface WaitForBackendReadinessOptions {
	maxAttempts?: number;
	delayMs?: number;
	signal?: AbortSignal;
}

/**
 * Sends the readiness GET to the backend and parses + validates the response
 * (semver compatibility, session vs readiness drift).
 *
 * By the time this is called, session.json + .ready are already on disk —
 * `HandshakeService` writes them from a HIGHEST_PRECEDENCE
 * `ApplicationReadyEvent` listener, so once the frontend can read them the
 * backend has fully started. The few retries below cover only narrow
 * transient cases:
 *   - cold JIT making the first /system/readiness response slow enough to
 *     race the HTTP client timeout;
 *   - port not yet bound at the kernel level between session.json write and
 *     our request landing (effectively impossible but cheap to handle).
 *
 * Old default: 75 attempts with adaptive backoff (up to ~50 s polling on
 * failure). New default: 5 attempts × 200 ms = ~800 ms worst case before we
 * fail fast and let the outer boot timeout / BootErrorView take over.
 */
export async function waitForBackendReadiness(
	session: SessionPayload,
	options: WaitForBackendReadinessOptions = {}
): Promise<SystemReadinessResponse> {
	const { maxAttempts = 5, delayMs = 200, signal } = options;
	let lastError: unknown;

	for (let attempt = 0; attempt < maxAttempts; attempt++) {
		if (signal?.aborted) {
			throw new BackendReadinessError('Backend readiness check was aborted');
		}

		try {
			const response = await httpFetch(readinessUrl(session), {
				signal,
				headers: {
					Accept: 'application/json',
					'X-API-KEY': session.apiKey
				}
			});

			if (!response.ok) {
				const error = new BackendReadinessError(
					`Backend readiness failed: ${response.status} ${response.statusText}`
				);
				if (!isRetryableReadinessStatus(response.status) || attempt === maxAttempts - 1) {
					throw error;
				}
				lastError = error;
			} else {
				const readiness = parseReadiness(await response.json());
				assertCompatible(readiness);
				assertSessionMatchesReadiness(session, readiness);
				if (readiness.ready) {
					return readiness;
				}
				lastError = new BackendReadinessError(
					readiness.reason ?? `Backend is not ready (phase=${readiness.phase})`
				);
			}
		} catch (err) {
			if (!isRetryableReadinessError(err) || attempt === maxAttempts - 1) {
				throw err;
			}
			lastError = err;
		}

		await delayWithAbort(delayMs, signal);
	}

	// delayWithAbort resolves (never rejects) on abort — when the final delay
	// was cut short by the signal, report the abort, not a fake exhaustion.
	if (signal?.aborted) {
		throw new BackendReadinessError('Backend readiness check was aborted');
	}
	throw new BackendReadinessError(
		`Backend readiness unavailable after ${maxAttempts} attempts: ${toErrorMessage(lastError)}`
	);
}

function readinessUrl(session: SessionPayload): string {
	return `${apiV1Base(session)}/system/readiness`;
}

function isRetryableReadinessStatus(status: number): boolean {
	return status === 404 || status === 503 || status >= 500;
}

function isRetryableReadinessError(error: unknown): boolean {
	// Compatibility errors (semver mismatch, session vs readiness drift) cannot
	// recover between attempts on their own — fail fast with a clear message.
	return !(error instanceof BackendReadinessError) && !(error instanceof BackendCompatibilityError);
}

export function parseReadiness(value: unknown): SystemReadinessResponse {
	if (typeof value !== 'object' || value === null) {
		throw new BackendReadinessError('Backend readiness is not an object');
	}

	const payload = value as Record<string, unknown>;
	const {
		ready,
		phase,
		appName,
		appVersion,
		apiVersion,
		minClientVersion,
		dbSchemaVersion,
		reason
	} = payload;

	if (typeof ready !== 'boolean') {
		throw new BackendReadinessError('Backend readiness: missing / invalid `ready`');
	}
	if (typeof phase !== 'string' || phase.length === 0) {
		throw new BackendReadinessError('Backend readiness: missing / invalid `phase`');
	}
	if (typeof appName !== 'string' || appName.length === 0) {
		/*
		 * Common diagnostic: backend is running an old jar (from before
		 * /handshake was folded into /system/readiness, when
		 * SystemReadinessResponse did not yet include appName). Give the user
		 * a concrete action instead of an unhelpful "missing field" message
		 * so they know to rebuild the backend.
		 */
		throw new BackendReadinessError(
			'Backend returned readiness without an `appName` field. Probably an old sidecar version. Repackage the backend (mvn package), sync the sidecar (npm run sidecar:sync:windows), and restart the application.'
		);
	}
	if (typeof appVersion !== 'string' || appVersion.length === 0) {
		throw new BackendReadinessError('Backend readiness: missing / invalid `appVersion`');
	}
	if (typeof apiVersion !== 'string' || apiVersion.length === 0) {
		throw new BackendReadinessError('Backend readiness: missing / invalid `apiVersion`');
	}
	if (typeof minClientVersion !== 'string' || minClientVersion.length === 0) {
		throw new BackendReadinessError('Backend readiness: missing / invalid `minClientVersion`');
	}
	if (typeof dbSchemaVersion !== 'string' || dbSchemaVersion.length === 0) {
		throw new BackendReadinessError('Backend readiness: missing / invalid `dbSchemaVersion`');
	}
	if (reason != null && typeof reason !== 'string') {
		throw new BackendReadinessError('Backend readiness: invalid `reason`');
	}

	return {
		ready,
		phase,
		appName,
		appVersion,
		apiVersion,
		minClientVersion,
		dbSchemaVersion,
		reason
	};
}

export function assertCompatible(readiness: SystemReadinessResponse): void {
	const apiVersion = parseSemver(readiness.apiVersion, 'apiVersion');
	if (apiVersion.major !== SUPPORTED_API_MAJOR) {
		throw new BackendCompatibilityError(
			`Backend API ${readiness.apiVersion} is not compatible with client ${CLIENT_VERSION}. Supported API major is ${SUPPORTED_API_MAJOR}. Restart the application or reinstall from GitHub Releases.`
		);
	}

	if (
		compareSemver(
			parseSemver(CLIENT_VERSION, 'clientVersion'),
			parseSemver(readiness.minClientVersion, 'minClientVersion')
		) < 0
	) {
		throw new BackendCompatibilityError(
			`Client ${CLIENT_VERSION} is too old. Backend requires at least ${readiness.minClientVersion}. Restart the application or reinstall from GitHub Releases.`
		);
	}
}

export function assertSessionMatchesReadiness(
	session: SessionPayload,
	readiness: SystemReadinessResponse
): void {
	if (
		session.appName !== readiness.appName ||
		session.appVersion !== readiness.appVersion ||
		session.apiVersion !== readiness.apiVersion ||
		session.minClientVersion !== readiness.minClientVersion ||
		session.dbSchemaVersion !== readiness.dbSchemaVersion
	) {
		throw new BackendReadinessError(
			'session.json does not match the current backend readiness response. Restart the application.'
		);
	}
}

export function parseSemver(version: string, field: string): Semver {
	const match = /^(\d+)\.(\d+)\.(\d+)(?:[-+].*)?$/.exec(version);
	if (!match) {
		throw new BackendCompatibilityError(`Backend readiness: invalid semver in field \`${field}\``);
	}
	return {
		major: Number(match[1]),
		minor: Number(match[2]),
		patch: Number(match[3])
	};
}

export function compareSemver(a: Semver, b: Semver): number {
	if (a.major !== b.major) return a.major - b.major;
	if (a.minor !== b.minor) return a.minor - b.minor;
	return a.patch - b.patch;
}
