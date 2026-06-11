/** Client-side error reporting baseline for the future backend endpoint. */

import { ApiError } from './client.js';
import { toErrorMessage } from './errors.js';
import { httpFetch } from './http.js';
import { requireSession } from '$lib/stores/session.js';

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

type ClientErrorReportResult = 'sent' | 'disabled' | 'failed';

const MAX_TEXT_LENGTH = 4000;
const CLIENT_ERRORS_PATH = '/internal/client-errors';

let disabledUntilReload = false;

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
	const route =
		typeof window !== 'undefined'
			? `${window.location.pathname}${window.location.search}${window.location.hash}`
			: null;

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
		route,
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
		// Reporting must never become another user-facing failure path.
		if (typeof window !== 'undefined' && window.localStorage.getItem('mail.e2e') === '1') {
			window.__MAIL_LAST_CLIENT_ERROR_REPORT_FAILURE__ = toErrorMessage(err);
		}
		return 'failed';
	}
}

export function resetClientErrorReportingForTests(): void {
	disabledUntilReload = false;
}
