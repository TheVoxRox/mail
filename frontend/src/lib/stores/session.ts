import { writable, get } from 'svelte/store';
import { loadSession, type LoadSessionOptions, type SessionPayload } from '$lib/api/session.js';
import { toError } from '$lib/api/errors.js';

type SessionState =
	| { status: 'idle' }
	| { status: 'loading' }
	| { status: 'ready'; session: SessionPayload }
	| { status: 'error'; error: Error };

export const sessionState = writable<SessionState>({ status: 'idle' });

/**
 * Loads session.json and saves it into the store. Repeated calls retry the
 * load (useful after a 401 / backend restart). Version compatibility checks
 * (semver, session vs backend drift) are performed by
 * `waitForBackendReadiness`, which calls a single endpoint instead of the
 * former handshake + readiness pair.
 */
export async function initSession(options: LoadSessionOptions = {}): Promise<SessionPayload> {
	sessionState.set({ status: 'loading' });
	try {
		const session = await loadSession(options);
		sessionState.set({ status: 'ready', session });
		return session;
	} catch (err) {
		const error = toError(err);
		sessionState.set({ status: 'error', error });
		throw error;
	}
}

/** Invalidates the session (e.g. after a 401) so the next call reloads it. */
export function invalidateSession(): void {
	sessionState.set({ status: 'idle' });
}

/**
 * Returns the currently loaded session, or starts the handshake if it has
 * not run yet. Parallel calls wait for the first in-flight load.
 */
let inFlight: Promise<SessionPayload> | null = null;

export async function requireSession(): Promise<SessionPayload> {
	const state = get(sessionState);
	if (state.status === 'ready') {
		return state.session;
	}
	if (inFlight) {
		return inFlight;
	}
	inFlight = initSession().finally(() => {
		inFlight = null;
	});
	return inFlight;
}
