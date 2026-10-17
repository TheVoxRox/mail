import { page } from '$app/state';
import { httpFetch } from './http.js';
import { requireSession } from '#lib/stores/session.js';
import type { BootState, BootTimings } from '#lib/stores/boot.js';
import { CLIENT_VERSION } from '#lib/version.js';

interface ClientBootDiagnosticsPayload {
	reportedAt: string;
	phase: string;
	slowLevel: string;
	timings: BootTimings;
	userAgent: string | null;
	language: string | null;
	route: string | null;
	clientVersion: string;
}

let disabledUntilReload = false;

function clientBootUrl(baseUrl: string): string {
	return `${baseUrl.replace(/\/+$/, '')}/internal/client-boot`;
}

export async function reportClientBootDiagnostics(state: BootState): Promise<void> {
	if (disabledUntilReload) return;

	try {
		const session = await requireSession();
		const response = await httpFetch(clientBootUrl(session.baseUrl), {
			method: 'POST',
			headers: {
				Accept: 'application/json',
				'Content-Type': 'application/json',
				'X-API-KEY': session.apiKey
			},
			body: JSON.stringify(buildPayload(state))
		});

		if (response.status === 404 || response.status === 501) {
			disabledUntilReload = true;
		}
	} catch {
		// Boot diagnostics are support-only; never make them part of user-facing startup.
	}
}

function buildPayload(state: BootState): ClientBootDiagnosticsPayload {
	return {
		reportedAt: new Date().toISOString(),
		phase: state.phase,
		slowLevel: state.slowLevel,
		timings: state.timings,
		userAgent: typeof navigator !== 'undefined' ? navigator.userAgent : null,
		language: typeof navigator !== 'undefined' ? navigator.language : null,
		/*
		 * The route id (`/mail/[accountId]/[folderName]/[stableId]`), not the path:
		 * a boot report is about the page, and the route id names it the same way
		 * in every report. The backend cuts anything that is not shaped like a
		 * route id as well.
		 */
		route: page.route.id,
		// The dump has the backend's version from the backend itself; this one is
		// what tells a frontend from one build next to a sidecar from another.
		clientVersion: CLIENT_VERSION
	};
}
