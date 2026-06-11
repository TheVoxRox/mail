/** `/api/internal/diagnostic-dump` — local support diagnostic ZIP. */

import { ApiError } from './client.js';
import { httpFetch } from './http.js';
import { invalidateSession, requireSession } from '$lib/stores/session.js';

interface DiagnosticDumpResult {
	blob: Blob;
	filename: string;
}

function diagnosticDumpUrl(baseUrl: string): string {
	return `${baseUrl.replace(/\/+$/, '')}/internal/diagnostic-dump`;
}

function filenameFromDisposition(disposition: string | null): string {
	const match = /filename="([^"]+)"/.exec(disposition ?? '');
	return match?.[1] ?? 'mail-diagnostic.zip';
}

async function fetchDiagnosticDump(): Promise<Response> {
	const session = await requireSession();
	return httpFetch(diagnosticDumpUrl(session.baseUrl), {
		headers: {
			Accept: 'application/zip',
			'X-API-KEY': session.apiKey
		}
	});
}

export async function downloadDiagnosticDump(): Promise<DiagnosticDumpResult> {
	let response = await fetchDiagnosticDump();
	if (response.status === 401) {
		invalidateSession();
		response = await fetchDiagnosticDump();
	}

	if (!response.ok) {
		throw new ApiError(response.status, response.statusText, null);
	}

	return {
		blob: await response.blob(),
		filename: filenameFromDisposition(response.headers.get('content-disposition'))
	};
}
