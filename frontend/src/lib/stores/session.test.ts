import { beforeEach, describe, expect, it, vi } from 'vitest';
import { get } from 'svelte/store';

const { loadSessionMock } = vi.hoisted(() => ({ loadSessionMock: vi.fn() }));

vi.mock('$lib/api/session.js', () => ({ loadSession: loadSessionMock }));

import { initSession, invalidateSession, requireSession, sessionState } from './session.js';

const PAYLOAD = {
	appName: 'VoxRox Mail',
	appVersion: '0.1.0',
	apiVersion: '1.0',
	minClientVersion: '0.1.0',
	dbSchemaVersion: '1',
	port: 12345,
	apiKey: 'k',
	baseUrl: 'http://localhost:12345/api'
};

beforeEach(() => {
	// mockReset (not just mockClear) — clearMocks:true in vitest.config drains
	// the call history before each test, but the `mockResolvedValueOnce` queue
	// survives and would leak across tests. mockReset wipes both.
	loadSessionMock.mockReset();
	sessionState.set({ status: 'idle' });
});

describe('initSession', () => {
	it('transitions idle → loading → ready and stores the payload', async () => {
		const seen: string[] = [];
		const unsub = sessionState.subscribe((s) => seen.push(s.status));
		loadSessionMock.mockResolvedValueOnce(PAYLOAD);

		const session = await initSession();
		unsub();

		expect(session).toBe(PAYLOAD);
		expect(seen).toEqual(['idle', 'loading', 'ready']);
		expect(get(sessionState)).toEqual({ status: 'ready', session: PAYLOAD });
	});

	it('transitions idle → loading → error on load failure', async () => {
		loadSessionMock.mockRejectedValueOnce(new Error('boom'));

		await expect(initSession()).rejects.toThrow('boom');
		const state = get(sessionState);
		expect(state.status).toBe('error');
		if (state.status === 'error') {
			expect(state.error.message).toBe('boom');
		}
	});

	it('passes LoadSessionOptions through to loadSession', async () => {
		loadSessionMock.mockResolvedValueOnce(PAYLOAD);
		const ac = new AbortController();
		await initSession({ maxAttempts: 5, delayMs: 10, signal: ac.signal });
		expect(loadSessionMock).toHaveBeenCalledWith({
			maxAttempts: 5,
			delayMs: 10,
			signal: ac.signal
		});
	});

	it('wraps non-Error rejection into Error', async () => {
		loadSessionMock.mockRejectedValueOnce('plain string');

		await expect(initSession()).rejects.toThrow('plain string');
		const state = get(sessionState);
		if (state.status === 'error') {
			expect(state.error).toBeInstanceOf(Error);
			expect(state.error.message).toBe('plain string');
		}
	});
});

describe('invalidateSession', () => {
	it('resets state to idle', async () => {
		loadSessionMock.mockResolvedValueOnce(PAYLOAD);
		await initSession();
		expect(get(sessionState).status).toBe('ready');

		invalidateSession();
		expect(get(sessionState)).toEqual({ status: 'idle' });
	});
});

describe('requireSession', () => {
	it('returns the loaded session when state is ready (no extra loads)', async () => {
		loadSessionMock.mockResolvedValueOnce(PAYLOAD);
		await initSession();

		loadSessionMock.mockClear();
		const session = await requireSession();
		expect(session).toBe(PAYLOAD);
		expect(loadSessionMock).not.toHaveBeenCalled();
	});

	it('triggers initSession when state is idle', async () => {
		loadSessionMock.mockResolvedValueOnce(PAYLOAD);

		const session = await requireSession();
		expect(session).toBe(PAYLOAD);
		expect(loadSessionMock).toHaveBeenCalledOnce();
	});

	it('deduplicates parallel calls (single load for many requesters)', async () => {
		let resolveLoad!: (s: typeof PAYLOAD) => void;
		loadSessionMock.mockImplementationOnce(
			() => new Promise<typeof PAYLOAD>((r) => (resolveLoad = r))
		);

		const a = requireSession();
		const b = requireSession();
		const c = requireSession();
		resolveLoad(PAYLOAD);

		await expect(Promise.all([a, b, c])).resolves.toEqual([PAYLOAD, PAYLOAD, PAYLOAD]);
		expect(loadSessionMock).toHaveBeenCalledOnce();
	});

	it('after an error, the next requireSession retries the load', async () => {
		loadSessionMock.mockRejectedValueOnce(new Error('first'));
		await expect(requireSession()).rejects.toThrow('first');

		loadSessionMock.mockResolvedValueOnce(PAYLOAD);
		await expect(requireSession()).resolves.toBe(PAYLOAD);
		expect(loadSessionMock).toHaveBeenCalledTimes(2);
	});
});
