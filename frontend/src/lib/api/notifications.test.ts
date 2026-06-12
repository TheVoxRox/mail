import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const { buildApiUrlMock, httpFetchMock, invalidateSessionMock } = vi.hoisted(() => ({
	buildApiUrlMock: vi.fn(),
	httpFetchMock: vi.fn(),
	invalidateSessionMock: vi.fn()
}));

vi.mock('./client.js', () => ({ buildApiUrl: buildApiUrlMock }));
vi.mock('./http.js', () => ({ httpFetch: httpFetchMock }));
vi.mock('$lib/stores/session.js', () => ({ invalidateSession: invalidateSessionMock }));

import { SseClient } from './notifications.js';

describe('SseClient reconnect lifecycle', () => {
	beforeEach(() => {
		vi.useFakeTimers();
		buildApiUrlMock.mockResolvedValue({ url: 'http://127.0.0.1:1/api/v1/x', apiKey: 'k' });
		httpFetchMock.mockRejectedValue(new Error('connection refused'));
	});

	afterEach(() => {
		vi.useRealTimers();
		vi.clearAllMocks();
	});

	it('close() during the backoff sleep + start() must not leave two loops running', async () => {
		// Production ordering of the restart flow: the stream fails, the client
		// sits in its reconnect backoff, and the app closes + immediately
		// restarts the client. Without the loop token the old loop woke from
		// its sleep, saw closed === false again and kept a SECOND stream —
		// duplicate connections and every event delivered twice.
		const client = new SseClient();
		client.start();
		await vi.advanceTimersByTimeAsync(0);
		expect(httpFetchMock).toHaveBeenCalledTimes(1);

		client.close();
		client.start();
		await vi.advanceTimersByTimeAsync(0);
		expect(httpFetchMock).toHaveBeenCalledTimes(2);

		// Single-loop timeline from the restart. The backoff survives the
		// restart by design (a rapid close+start must not hammer the backend),
		// so the new loop continues at 2s, then 4s → exactly two more attempts
		// within the next 8 seconds. A leaked old loop would add its own
		// wake-ups on top.
		await vi.advanceTimersByTimeAsync(8_000);
		expect(httpFetchMock).toHaveBeenCalledTimes(4);

		client.close();
	});

	it('close() stops reconnect attempts for good', async () => {
		const client = new SseClient();
		client.start();
		await vi.advanceTimersByTimeAsync(0);
		expect(httpFetchMock).toHaveBeenCalledTimes(1);

		client.close();
		await vi.advanceTimersByTimeAsync(60_000);
		expect(httpFetchMock).toHaveBeenCalledTimes(1);
	});
});
