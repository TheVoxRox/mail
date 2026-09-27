import { page } from '$app/state';
import { httpFetch } from './http.js';
import { requireSession } from '$lib/stores/session.js';
import type { BootState, BootTimings } from '$lib/stores/boot.js';

interface ClientBootDiagnosticsPayload {
	reportedAt: string;
	phase: string;
	slowLevel: string;
	timings: BootTimings;
	userAgent: string | null;
	language: string | null;
	route: string | null;
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
		 * the report ends up in the diagnostic dump a user sends to support, and a
		 * path carries the account, the name of the folder and the message. The
		 * backend cuts anything that is not shaped like a route id as well.
		 */
		route: page.route.id
	};
}
