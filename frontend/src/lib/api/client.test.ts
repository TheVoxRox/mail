// @vitest-environment jsdom
import { beforeEach, describe, expect, it, vi } from 'vitest';

const { requireSessionMock, invalidateSessionMock, httpFetchMock } = vi.hoisted(() => ({
	requireSessionMock: vi.fn(),
	invalidateSessionMock: vi.fn(),
	httpFetchMock: vi.fn()
}));

vi.mock('$lib/stores/session.js', () => ({
	requireSession: requireSessionMock,
	invalidateSession: invalidateSessionMock
}));
vi.mock('./session.js', () => ({
	apiV1Base: (s: { baseUrl: string }) => `${s.baseUrl.replace(/\/+$/, '')}/v1`
}));
vi.mock('./http.js', () => ({ httpFetch: httpFetchMock }));
vi.mock('svelte/store', async () => {
	const actual = (await vi.importActual('svelte/store')) as Record<string, unknown>;
	return { ...actual, get: () => 'en' };
});
vi.mock('$lib/i18n/index.js', () => ({ appLocale: { subscribe: vi.fn() } }));

import { ApiError, api, apiRaw, buildApiUrl } from './client.js';

const SESSION = {
	apiKey: 'test-key',
	baseUrl: 'http://localhost:8080/api',
	port: 8080
};

function jsonResponse(body: unknown, init: ResponseInit = {}) {
	return new Response(JSON.stringify(body), {
		status: 200,
		headers: { 'content-type': 'application/json' },
		...init
	});
}

beforeEach(() => {
	requireSessionMock.mockResolvedValue(SESSION);
	// mockReset drains the mockResolvedValueOnce / mockRejectedValueOnce queue,
	// which clearMocks:true (vitest.config) does NOT — auto-clear only wipes
	// the call history. Without these explicit resets a once-queue from the
	// previous test could leak into this one.
	httpFetchMock.mockReset();
	invalidateSessionMock.mockReset();
});

describe('api.get', () => {
	it('sends the X-API-KEY header derived from session', async () => {
		httpFetchMock.mockResolvedValueOnce(jsonResponse({ ok: true }));
		await api.get('/test');
		expect(httpFetchMock).toHaveBeenCalledTimes(1);
		const [, init] = httpFetchMock.mock.calls[0];
		const headers = init.headers as Headers;
		expect(headers.get('X-API-KEY')).toBe('test-key');
		expect(headers.get('Accept')).toBe('application/json');
		expect(headers.get('Accept-Language')).toBe('en');
	});

	it('appends query params to the URL', async () => {
		httpFetchMock.mockResolvedValueOnce(jsonResponse([]));
		await api.get('/messages', { params: { page: '1', size: '10' } });
		const [url] = httpFetchMock.mock.calls[0];
		expect(url).toBe('http://localhost:8080/api/v1/messages?page=1&size=10');
	});

	it('returns the parsed JSON body on 200', async () => {
		httpFetchMock.mockResolvedValueOnce(jsonResponse({ id: 1, name: 'A' }));
		const result = await api.get('/x');
		expect(result).toEqual({ id: 1, name: 'A' });
	});

	it('returns undefined on 204 No Content', async () => {
		httpFetchMock.mockResolvedValueOnce(new Response(null, { status: 204 }));
		const result = await api.get('/x');
		expect(result).toBeUndefined();
	});

	it('returns undefined when content-type is not JSON', async () => {
		httpFetchMock.mockResolvedValueOnce(
			new Response('plain text', { status: 200, headers: { 'content-type': 'text/plain' } })
		);
		const result = await api.get('/x');
		expect(result).toBeUndefined();
	});

	it('throws ApiError with localized problem.detail for 4xx', async () => {
		httpFetchMock.mockResolvedValueOnce(
			jsonResponse(
				{
					type: 'about:blank',
					status: 400,
					detail: 'Špatný požadavek',
					errorCode: 'BAD_REQUEST'
				},
				{ status: 400 }
			)
		);
		await expect(api.get('/x')).rejects.toMatchObject({
			name: 'ApiError',
			status: 400,
			message: 'Špatný požadavek'
		});
	});

	it('falls back to "API error N: statusText" when no problem.detail', async () => {
		httpFetchMock.mockResolvedValueOnce(
			new Response('', { status: 503, statusText: 'Service Unavailable' })
		);
		await expect(api.get('/x')).rejects.toMatchObject({
			status: 503,
			message: 'API error 503: Service Unavailable'
		});
	});
});

describe('api 401 retry behaviour', () => {
	it('retries once after invalidating session on bare 401', async () => {
		httpFetchMock
			.mockResolvedValueOnce(new Response('', { status: 401 }))
			.mockResolvedValueOnce(jsonResponse({ ok: true }));
		const result = await api.get('/x');
		expect(invalidateSessionMock).toHaveBeenCalledOnce();
		expect(httpFetchMock).toHaveBeenCalledTimes(2);
		expect(result).toEqual({ ok: true });
	});

	it('retries on 401 with UNAUTHORIZED errorCode', async () => {
		httpFetchMock
			.mockResolvedValueOnce(jsonResponse({ errorCode: 'UNAUTHORIZED' }, { status: 401 }))
			.mockResolvedValueOnce(jsonResponse({ ok: true }));
		await api.get('/x');
		expect(invalidateSessionMock).toHaveBeenCalledOnce();
	});

	it('retries on 401 with API_KEY_REQUIRED errorCode', async () => {
		httpFetchMock
			.mockResolvedValueOnce(jsonResponse({ errorCode: 'API_KEY_REQUIRED' }, { status: 401 }))
			.mockResolvedValueOnce(jsonResponse({ ok: true }));
		await api.get('/x');
		expect(invalidateSessionMock).toHaveBeenCalledOnce();
	});

	it('does NOT retry on 401 with a different errorCode (e.g. MAIL_ACCOUNT_REQUIRES_REAUTH)', async () => {
		httpFetchMock.mockResolvedValueOnce(
			jsonResponse({ errorCode: 'MAIL_ACCOUNT_REQUIRES_REAUTH', detail: 'reauth' }, { status: 401 })
		);
		await expect(api.get('/x')).rejects.toMatchObject({ status: 401 });
		expect(invalidateSessionMock).not.toHaveBeenCalled();
		expect(httpFetchMock).toHaveBeenCalledTimes(1);
	});

	it('does NOT retry on 403/500 etc.', async () => {
		httpFetchMock.mockResolvedValueOnce(jsonResponse({ detail: 'forbidden' }, { status: 403 }));
		await expect(api.get('/x')).rejects.toMatchObject({ status: 403 });
		expect(invalidateSessionMock).not.toHaveBeenCalled();
	});
});

describe('api.post', () => {
	it('sends a JSON body with Content-Type set', async () => {
		httpFetchMock.mockResolvedValueOnce(jsonResponse({ id: 1 }, { status: 201 }));
		await api.post('/items', { name: 'A' });
		const [, init] = httpFetchMock.mock.calls[0];
		expect(init.method).toBe('POST');
		expect(init.body).toBe(JSON.stringify({ name: 'A' }));
		const headers = init.headers as Headers;
		expect(headers.get('Content-Type')).toBe('application/json');
	});

	it('omits body when none provided', async () => {
		httpFetchMock.mockResolvedValueOnce(jsonResponse({}, { status: 201 }));
		await api.post('/items');
		const [, init] = httpFetchMock.mock.calls[0];
		expect(init.body).toBeUndefined();
	});
});

describe('apiRaw', () => {
	it('returns the raw Response for binary content', async () => {
		const body = new Uint8Array([1, 2, 3]);
		httpFetchMock.mockResolvedValueOnce(
			new Response(body, { status: 200, headers: { 'content-type': 'application/pdf' } })
		);
		const response = await apiRaw('/file');
		expect(response.status).toBe(200);
		expect(response.headers.get('content-type')).toBe('application/pdf');
	});

	it('throws ApiError on non-2xx', async () => {
		httpFetchMock.mockResolvedValueOnce(new Response('', { status: 404 }));
		await expect(apiRaw('/missing')).rejects.toBeInstanceOf(ApiError);
	});
});

describe('buildApiUrl', () => {
	it('returns the full v1 URL and the api key (for SSE EventSource)', async () => {
		const { url, apiKey } = await buildApiUrl('/notifications', { since: '0' });
		expect(url).toBe('http://localhost:8080/api/v1/notifications?since=0');
		expect(apiKey).toBe('test-key');
	});
});
