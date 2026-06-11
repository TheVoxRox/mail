/**
 * HTTP client for talking to the Spring Boot API.
 *
 * Base URL and API key are lazily loaded from the session store (session.json).
 * On a 401 we perform one retry after invalidating the session (the port /
 * key may have changed because of a backend restart).
 */

import { apiV1Base } from './session.js';
import { httpFetch } from './http.js';
import { appLocale } from '$lib/i18n/index.js';
import { invalidateSession, requireSession } from '$lib/stores/session.js';
import { get } from 'svelte/store';
import type { ProblemDetail } from './generated.js';

export type { ProblemDetail };

export class ApiError extends Error {
	constructor(
		public status: number,
		public statusText: string,
		public body: unknown,
		public problem?: ProblemDetail
	) {
		/*
		 * Backend sends an RFC 9457 ProblemDetail with `detail` already
		 * localized according to Accept-Language (see
		 * GlobalExceptionHandler#resolveMessage). Prefer it for any status so
		 * the user does not see a raw "API error 503: Service Unavailable"
		 * where the backend already sent "Connection to the mail server
		 * failed: ...". Fall back to statusText only when detail is missing
		 * or empty (e.g. body-less response from a reverse proxy / sidecar
		 * startup).
		 */
		const detail = typeof problem?.detail === 'string' ? problem.detail.trim() : '';
		super(detail.length > 0 ? detail : `API error ${status}: ${statusText}`);
		this.name = 'ApiError';
	}
}

type RequestOptions = Omit<RequestInit, 'method' | 'body'> & {
	params?: Record<string, string>;
};

function isProblemDetail(value: unknown): value is ProblemDetail {
	return (
		typeof value === 'object' &&
		value !== null &&
		('status' in value || 'detail' in value || 'type' in value)
	);
}

function problemErrorCode(value: unknown): string | null {
	if (typeof value !== 'object' || value === null) return null;
	const code = (value as { errorCode?: unknown }).errorCode;
	return typeof code === 'string' ? code : null;
}

async function shouldRetryWithFreshSession(response: Response): Promise<boolean> {
	if (response.status !== 401) return false;
	try {
		const body: unknown = await response.clone().json();
		const code = problemErrorCode(body);
		return code === null || code === 'UNAUTHORIZED' || code === 'API_KEY_REQUIRED';
	} catch {
		return true;
	}
}

async function handleResponse<T>(response: Response): Promise<T> {
	if (!response.ok) {
		let body: unknown;
		try {
			const text = await response.text();
			try {
				body = text ? JSON.parse(text) : null;
			} catch {
				body = text;
			}
		} catch {
			body = null;
		}
		throw new ApiError(
			response.status,
			response.statusText,
			body,
			isProblemDetail(body) ? body : undefined
		);
	}

	if (response.status === 204) {
		return undefined as T;
	}

	const contentType = response.headers.get('content-type') ?? '';
	if (!contentType.includes('application/json')) {
		return undefined as T;
	}

	return response.json() as Promise<T>;
}

function buildUrl(base: string, path: string, params?: Record<string, string>): string {
	const url = new URL(`${base}${path}`);
	if (params) {
		for (const [key, value] of Object.entries(params)) {
			url.searchParams.set(key, value);
		}
	}
	return url.toString();
}

function getHeaders(
	apiKey: string,
	custom?: HeadersInit,
	options: { accept?: string; contentType?: string | null } = {}
): Headers {
	const { accept = 'application/json', contentType = 'application/json' } = options;
	const headers = new Headers(custom);
	if (contentType && !headers.has('Content-Type')) {
		headers.set('Content-Type', contentType);
	}
	if (accept && !headers.has('Accept')) {
		headers.set('Accept', accept);
	}
	if (!headers.has('Accept-Language')) {
		headers.set('Accept-Language', get(appLocale) ?? 'cs');
	}
	headers.set('X-API-KEY', apiKey);
	return headers;
}

type HttpMethod = 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE';

async function sendAuthorizedRequest(
	method: HttpMethod,
	path: string,
	body: unknown,
	options: RequestOptions & {
		accept?: string;
		contentType?: string | null;
	}
): Promise<Response> {
	const { params, accept = 'application/json', contentType, ...init } = options;

	const send = async (): Promise<Response> => {
		const session = await requireSession();
		const base = apiV1Base(session);
		return httpFetch(buildUrl(base, path, params), {
			...init,
			method,
			headers: getHeaders(session.apiKey, init.headers, {
				accept,
				contentType: contentType ?? (body != null ? 'application/json' : null)
			}),
			body: body != null ? JSON.stringify(body) : undefined
		});
	};

	let response = await send();

	if (await shouldRetryWithFreshSession(response)) {
		invalidateSession();
		response = await send();
	}

	return response;
}

async function request<T>(
	method: HttpMethod,
	path: string,
	body: unknown,
	options: RequestOptions
): Promise<T> {
	const response = await sendAuthorizedRequest(method, path, body, options);
	return handleResponse<T>(response);
}

export const api = {
	get<T>(path: string, options: RequestOptions = {}): Promise<T> {
		return request<T>('GET', path, undefined, options);
	},
	post<T>(path: string, body?: unknown, options: RequestOptions = {}): Promise<T> {
		return request<T>('POST', path, body, options);
	},
	put<T>(path: string, body?: unknown, options: RequestOptions = {}): Promise<T> {
		return request<T>('PUT', path, body, options);
	},
	patch<T>(path: string, body?: unknown, options: RequestOptions = {}): Promise<T> {
		return request<T>('PATCH', path, body, options);
	},
	delete<T>(path: string, body?: unknown, options: RequestOptions = {}): Promise<T> {
		return request<T>('DELETE', path, body, options);
	}
};

/**
 * Returns the raw Response from an API endpoint (for binary data / streams).
 * The 401 retry behaves the same as for the JSON client.
 */
export async function apiRaw(path: string, options: RequestOptions = {}): Promise<Response> {
	const response = await sendAuthorizedRequest('GET', path, undefined, {
		...options,
		accept: '*/*',
		contentType: null
	});
	if (!response.ok) {
		throw new ApiError(response.status, response.statusText, null);
	}
	return response;
}

/**
 * Returns the full URL and the API key for an API-v1 path, as two separate
 * values, for callers that issue a request outside the `api`/`apiRaw` helpers
 * (the SSE `SseClient`, which streams over `httpFetch`). The key is returned
 * separately so the caller sends it in the `X-API-KEY` header — it is never
 * embedded in the URL.
 */
export async function buildApiUrl(
	path: string,
	params?: Record<string, string>
): Promise<{ url: string; apiKey: string }> {
	const session = await requireSession();
	const base = apiV1Base(session);
	return { url: buildUrl(base, path, params), apiKey: session.apiKey };
}
