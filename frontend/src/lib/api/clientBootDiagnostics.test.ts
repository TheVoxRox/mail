// @vitest-environment jsdom
import { describe, expect, it, vi } from 'vitest';

const { requireSessionMock, httpFetchMock, pageMock } = vi.hoisted(() => ({
	requireSessionMock: vi.fn(),
	httpFetchMock: vi.fn(),
	pageMock: { route: { id: null as string | null } }
}));

vi.mock('$app/state', () => ({ page: pageMock }));
vi.mock('$lib/stores/session.js', () => ({ requireSession: requireSessionMock }));
vi.mock('./http.js', () => ({ httpFetch: httpFetchMock }));

import { reportClientBootDiagnostics } from './clientBootDiagnostics.js';

describe('reportClientBootDiagnostics', () => {
	it('reports the route id, never the path with the folder and message in it', async () => {
		requireSessionMock.mockResolvedValue({ apiKey: 'k', baseUrl: 'http://127.0.0.1:51234/api' });
		httpFetchMock.mockResolvedValue(new Response(null, { status: 202 }));
		pageMock.route.id = '/mail/[accountId]/[folderName]/[stableId]';
		window.history.replaceState(null, '', '/mail/7/Canary%20Lawyer%204417/0123abcd?q=x#top');

		await reportClientBootDiagnostics({
			phase: 'ready',
			slowLevel: 'fast',
			startedAt: 0,
			timings: { uiStart: 0, appReady: 1200 }
		});

		const [url, init] = httpFetchMock.mock.calls[0] as [string, RequestInit];
		expect(url).toBe('http://127.0.0.1:51234/api/internal/client-boot');
		const payload = JSON.parse(init.body as string) as { route: string | null };
		expect(payload.route).toBe('/mail/[accountId]/[folderName]/[stableId]');
		expect(init.body).not.toContain('Canary');
	});
});
