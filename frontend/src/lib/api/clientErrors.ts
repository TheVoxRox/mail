/**
 * Reports an uncaught client error to the backend, which writes it into
 * `mail.log` (`POST /api/internal/client-errors`). The webview's console is
 * kept nowhere in a release build, so this is the only way such an error
 * reaches the diagnostic bundle.
 */

import { page } from '$app/state';
import { ApiError } from './client.js';
import { toErrorMessage } from './errors.js';
import { httpFetch } from './http.js';
import { requireSession } from '#lib/stores/session.js';

type ClientErrorKind = 'window_error' | 'unhandled_rejection' | 'manual';

interface ClientErrorReportInput {
	kind: ClientErrorKind;
	error?: unknown;
	message?: string;
	source?: string;
	line?: number;
	column?: number;
	context?: Record<string, unknown>;
}

interface ClientErrorReportPayload {
	eventId: string;
	occurredAt: string;
	kind: ClientErrorKind;
	message: string;
	name: string | null;
	stack: string | null;
	source: string | null;
	line: number | null;
	column: number | null;
	route: string | null;
	userAgent: string | null;
	language: string | null;
	backend: {
		appName: string;
		appVersion: string;
		apiVersion: string;
		minClientVersion: string;
		dbSchemaVersion: string;
	} | null;
	context?: Record<string, unknown>;
}

type ClientErrorReportResult = 'sent' | 'disabled' | 'repeated' | 'failed';

const MAX_TEXT_LENGTH = 4000;
/*
 * An error thrown on every frame or every retry would otherwise post itself
 * hundreds of times a minute. The backend writes at most twenty a minute
 * anyway; these two keep the client from sending what it would drop, and keep
 * one noisy error from crowding out a different one.
 */
const MAX_REPORTS_PER_LOAD = 20;
const CLIENT_ERRORS_PATH = '/internal/client-errors';

let disabledUntilReload = false;
const reportedSignatures = new Set<string>();

function clientErrorsUrl(baseUrl: string): string {
	return `${baseUrl.replace(/\/+$/, '')}${CLIENT_ERRORS_PATH}`;
}

function truncate(value: string): string {
	return value.length > MAX_TEXT_LENGTH ? `${value.slice(0, MAX_TEXT_LENGTH)}…` : value;
}

function safeMessage(value: unknown): string {
	if (value instanceof Error) return value.message || value.name;
	if (typeof value === 'string') return value;
	if (value == null) return 'Unknown client error';
	try {
		return JSON.stringify(value);
	} catch {
		return String(value);
	}
}

function errorName(value: unknown): string | null {
	return value instanceof Error ? value.name : null;
}

function errorStack(value: unknown): string | null {
	return value instanceof Error && value.stack ? truncate(value.stack) : null;
}

function buildPayload(input: ClientErrorReportInput): ClientErrorReportPayload {
	return {
		eventId: crypto.randomUUID(),
		occurredAt: new Date().toISOString(),
		kind: input.kind,
		message: truncate(input.message ?? safeMessage(input.error)),
		name: errorName(input.error),
		stack: errorStack(input.error),
		source: input.source ? truncate(input.source) : null,
		line: Number.isFinite(input.line) ? (input.line ?? null) : null,
		column: Number.isFinite(input.column) ? (input.column ?? null) : null,
		/*
		 * The route id (`/mail/[accountId]/[folderName]`), not the path, as in
		 * clientBootDiagnostics.ts: the same error on the same page then reads as
		 * the same line in the log, whichever folder or message was open.
		 */
		route: page.route.id,
		userAgent: typeof navigator !== 'undefined' ? truncate(navigator.userAgent) : null,
		language: typeof navigator !== 'undefined' ? navigator.language : null,
		backend: null,
		context: input.context
	};
}

export async function reportClientError(
	input: ClientErrorReportInput
): Promise<ClientErrorReportResult> {
	if (disabledUntilReload) return 'disabled';

	let payload = buildPayload(input);
	const signature = `${payload.kind}\n${payload.message}\n${payload.stack ?? ''}`;
	if (reportedSignatures.has(signature) || reportedSignatures.size >= MAX_REPORTS_PER_LOAD) {
		return 'repeated';
	}
	// Taken before the send, so the copies thrown while it is in flight stay
	// home; given back if it fails, so the next copy tries again: an error first
	// thrown before the backend is up must not be silenced for the whole load.
	reportedSignatures.add(signature);

	try {
		const session = await requireSession();
		payload = {
			...payload,
			backend: {
				appName: session.appName,
				appVersion: session.appVersion,
				apiVersion: session.apiVersion,
				minClientVersion: session.minClientVersion,
				dbSchemaVersion: session.dbSchemaVersion
			}
		};

		const response = await httpFetch(clientErrorsUrl(session.baseUrl), {
			method: 'POST',
			headers: {
				Accept: 'application/json',
				'Content-Type': 'application/json',
				'X-API-KEY': session.apiKey
			},
			body: JSON.stringify(payload)
		});

		if (response.status === 404 || response.status === 501) {
			disabledUntilReload = true;
			return 'disabled';
		}

		if (!response.ok) {
			throw new ApiError(response.status, response.statusText, null);
		}
		return 'sent';
	} catch (err) {
		reportedSignatures.delete(signature);
		// Reporting must never become another user-facing failure path.
		if (typeof window !== 'undefined' && window.localStorage.getItem('mail.e2e') === '1') {
			window.__MAIL_LAST_CLIENT_ERROR_REPORT_FAILURE__ = toErrorMessage(err);
		}
		return 'failed';
	}
}

export function resetClientErrorReportingForTests(): void {
	disabledUntilReload = false;
	reportedSignatures.clear();
}
