/**
 * Recovery for an *automatic* backend-sidecar restart.
 *
 * When the sidecar crashes, `handleUnexpectedExit` (sidecar.ts) respawns it.
 * The new backend comes up on a fresh ephemeral port (Tomcat `port=0`) with a
 * freshly rotated handshake API key (HandshakeService generates a new key per
 * process), but the frontend still holds the boot-time session — so every
 * request, and the live SSE stream, keeps hitting the dead old port. These are
 * connection failures, not 401s, so the client's 401 re-handshake (client.ts)
 * never fires and the app is left silently broken until a full restart.
 *
 * We watch the sidecar state machine for the automatic-restart signature
 * ('restarting' → 'starting' → 'running') and, on the new 'running',
 * invalidate the stale session and re-run bootstrap. bootstrap waits for the
 * new `.ready`/`session.json`, re-checks readiness and reloads
 * port/key-dependent state; the SSE client self-heals on its next reconnect
 * (it re-reads `buildApiUrl` on every attempt) once the session is invalidated.
 *
 * The manual BootErrorView Restart/Retry path re-handshakes on its own and
 * emits 'stopped' → 'starting' (never 'restarting'), so it is not matched here.
 */
import { invalidateSession } from '$lib/stores/session.js';
import { backendSidecarState } from './sidecar.js';

async function recoverAfterSidecarRestart(): Promise<void> {
	invalidateSession();
	try {
		/*
		 * Dynamic import keeps the static module graph free of a sidecar ⇆
		 * bootstrap cycle (bootstrap already imports this layer) and lets the
		 * watcher live next to the sidecar state it observes. Inside the try so a
		 * (near-impossible) import failure cannot escape as an unhandled rejection
		 * from the watcher's `void recover()`.
		 */
		const { bootstrap } = await import('$lib/bootstrap.js');
		await bootstrap({ force: true });
	} catch {
		/*
		 * Failure surfaces via the boot / session / accounts stores; the already
		 * invalidated session still forces a lazy re-handshake on the next request.
		 */
	}
}

/**
 * Subscribes to the sidecar state machine and triggers `recover` once each
 * time an automatic restart completes. Returns an unsubscribe function.
 * `recover` is injectable for tests.
 */
export function watchSidecarAutoRestart(
	recover: () => void | Promise<void> = recoverAfterSidecarRestart
): () => void {
	let armed = false;
	return backendSidecarState.subscribe((state) => {
		switch (state.status) {
			case 'restarting':
				armed = true;
				break;
			case 'running':
				if (armed) {
					armed = false;
					void recover();
				}
				break;
			case 'error':
			case 'stopped':
			case 'disabled':
				armed = false;
				break;
			// 'starting' / 'idle': the automatic restart passes through 'starting'
			// on its way back to 'running', so `armed` must survive it.
		}
	});
}
