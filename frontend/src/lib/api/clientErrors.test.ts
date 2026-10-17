// @vitest-environment jsdom
import { beforeEach, describe, expect, it, vi } from 'vitest';

const { requireSessionMock, httpFetchMock, pageMock } = vi.hoisted(() => ({
	requireSessionMock: vi.fn(),
	httpFetchMock: vi.fn(),
	pageMock: { route: { id: null as string | null } }
}));

vi.mock('$app/state', () => ({ page: pageMock }));
vi.mock('#lib/stores/session.js', () => ({ requireSession: requireSessionMock }));
vi.mock('./http.js', () => ({ httpFetch: httpFetchMock }));

import { reportClientError, resetClientErrorReportingForTests } from './clientErrors.js';

const SESSION = {
	apiKey: 'k',
	baseUrl: 'http://127.0.0.1:51234/api',
	appName: 'mail',
	appVersion: '0.1.0',
	apiVersion: '1.0.0',
	minClientVersion: '0.0.1',
	dbSchemaVersion: '1'
};

beforeEach(() => {
	resetClientErrorReportingForTests();
	httpFetchMock.mockReset();
	requireSessionMock.mockResolvedValue(SESSION);
	httpFetchMock.mockImplementation(async () => new Response(null, { status: 202 }));
});

describe('reportClientError', () => {
	it('reports the route id, never the path with the folder and message in it', async () => {
		pageMock.route.id = '/mail/[accountId]/[folderName]/[stableId]';
		window.history.replaceState(null, '', '/mail/7/Canary%20Lawyer%204417/0123abcd?q=x#top');

		const result = await reportClientError({ kind: 'window_error', error: new Error('boom') });

		expect(result).toBe('sent');
		const [url, init] = httpFetchMock.mock.calls[0] as [string, RequestInit];
		expect(url).toBe('http://127.0.0.1:51234/api/internal/client-errors');
		const payload = JSON.parse(init.body as string) as { route: string | null; message: string };
		expect(payload.route).toBe('/mail/[accountId]/[folderName]/[stableId]');
		expect(payload.message).toBe('boom');
		expect(init.body).not.toContain('Canary');
	});

	it('sends one error once per load, however often it is thrown', async () => {
		const error = new Error('thrown every frame');

		const results = [];
		for (let i = 0; i < 5; i++) {
			results.push(await reportClientError({ kind: 'window_error', error }));
		}

		expect(results).toEqual(['sent', 'repeated', 'repeated', 'repeated', 'repeated']);
		expect(httpFetchMock).toHaveBeenCalledTimes(1);
	});

	it('tries an error again after a report of it failed', async () => {
		const error = new Error('thrown before the backend is up');
		requireSessionMock.mockRejectedValueOnce(new Error('no session yet'));

		const first = await reportClientError({ kind: 'window_error', error });
		const second = await reportClientError({ kind: 'window_error', error });

		expect([first, second]).toEqual(['failed', 'sent']);
		expect(httpFetchMock).toHaveBeenCalledTimes(1);
	});

	it('stops after twenty different errors in one load', async () => {
		for (let i = 0; i < 25; i++) {
			await reportClientError({ kind: 'manual', message: `error ${i}` });
		}

		expect(httpFetchMock).toHaveBeenCalledTimes(20);
	});
});
