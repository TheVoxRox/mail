import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { createProviderResolver } from './providerResolver.js';
import type { MailProviderResponse } from '$lib/types.js';

const gmail = {
	id: 1,
	name: 'Gmail',
	imapHost: 'imap.gmail.com',
	imapPort: 993,
	smtpHost: 'smtp.gmail.com',
	smtpPort: 465
} as unknown as MailProviderResponse;

const seznam = { ...gmail, id: 2, name: 'Seznam' } as unknown as MailProviderResponse;

beforeEach(() => {
	vi.useFakeTimers();
});
afterEach(() => {
	vi.useRealTimers();
});

describe('createProviderResolver', () => {
	it('does nothing for empty/invalid email', async () => {
		const onResolved = vi.fn();
		const onCleared = vi.fn();
		const resolveFn = vi.fn().mockResolvedValue(gmail);
		const r = createProviderResolver({ resolveFn, onResolved, onCleared });
		await r.resolveNow('');
		await r.resolveNow('not-an-email');
		await r.resolveNow('partial@');
		expect(resolveFn).not.toHaveBeenCalled();
		expect(onResolved).not.toHaveBeenCalled();
		expect(onCleared).not.toHaveBeenCalled();
	});

	it('calls onResolved with provider + normalized email on success', async () => {
		const onResolved = vi.fn();
		const onStart = vi.fn();
		const onEnd = vi.fn();
		const resolveFn = vi.fn().mockResolvedValue(gmail);
		const r = createProviderResolver({ resolveFn, onResolved, onStart, onEnd });
		await r.resolveNow('  Foo@GMAIL.COM ');
		expect(resolveFn).toHaveBeenCalledWith('foo@gmail.com');
		expect(onStart).toHaveBeenCalledOnce();
		expect(onEnd).toHaveBeenCalledOnce();
		expect(onResolved).toHaveBeenCalledWith(gmail, 'foo@gmail.com');
	});

	it('skips re-resolving the same successful email', async () => {
		const onResolved = vi.fn();
		const resolveFn = vi.fn().mockResolvedValue(gmail);
		const r = createProviderResolver({ resolveFn, onResolved });
		await r.resolveNow('a@b.com');
		await r.resolveNow('a@b.com');
		expect(resolveFn).toHaveBeenCalledOnce();
		expect(onResolved).toHaveBeenCalledOnce();
	});

	it('resolves again after reset()', async () => {
		const onResolved = vi.fn();
		const resolveFn = vi.fn().mockResolvedValue(gmail);
		const r = createProviderResolver({ resolveFn, onResolved });
		await r.resolveNow('a@b.com');
		r.reset();
		await r.resolveNow('a@b.com');
		expect(resolveFn).toHaveBeenCalledTimes(2);
	});

	it('on API error: clears state and calls onCleared (only if previously resolved)', async () => {
		const onResolved = vi.fn();
		const onCleared = vi.fn();
		const resolveFn = vi
			.fn()
			.mockResolvedValueOnce(gmail)
			.mockRejectedValueOnce(new Error('unknown domain'));
		const r = createProviderResolver({ resolveFn, onResolved, onCleared });
		await r.resolveNow('foo@gmail.com');
		expect(onResolved).toHaveBeenCalled();
		await r.resolveNow('other@example.com');
		expect(onCleared).toHaveBeenCalledOnce();
	});

	it('on first-call API error: no onCleared (nothing to clear)', async () => {
		const onCleared = vi.fn();
		const resolveFn = vi.fn().mockRejectedValue(new Error('nope'));
		const r = createProviderResolver({ resolveFn, onResolved: () => {}, onCleared });
		await r.resolveNow('foo@example.com');
		expect(onCleared).not.toHaveBeenCalled();
	});

	it('debounces schedule() and only fires the latest', async () => {
		const onResolved = vi.fn();
		const resolveFn = vi.fn().mockResolvedValue(gmail);
		const r = createProviderResolver({ resolveFn, onResolved, debounceMs: 500 });
		r.schedule('a@example.com');
		r.schedule('b@example.com');
		r.schedule('c@example.com');
		expect(resolveFn).not.toHaveBeenCalled();
		await vi.advanceTimersByTimeAsync(500);
		expect(resolveFn).toHaveBeenCalledOnce();
		expect(resolveFn).toHaveBeenCalledWith('c@example.com');
	});

	it('cancel() stops a pending debounce', async () => {
		const resolveFn = vi.fn().mockResolvedValue(gmail);
		const r = createProviderResolver({ resolveFn, onResolved: () => {} });
		r.schedule('a@example.com');
		r.cancel();
		await vi.advanceTimersByTimeAsync(500);
		expect(resolveFn).not.toHaveBeenCalled();
	});

	it('drops stale in-flight result when newer schedule wins', async () => {
		let resolveFirst!: (v: MailProviderResponse) => void;
		const firstPromise = new Promise<MailProviderResponse>((res) => (resolveFirst = res));
		const resolveFn = vi
			.fn()
			.mockImplementationOnce(() => firstPromise)
			.mockResolvedValueOnce(seznam);
		const onResolved = vi.fn();
		const r = createProviderResolver({ resolveFn, onResolved, debounceMs: 100 });

		r.schedule('first@example.com');
		await vi.advanceTimersByTimeAsync(100);
		// first request started but not resolved yet
		r.schedule('second@example.com');
		await vi.advanceTimersByTimeAsync(100);
		// second resolved synchronously (mockResolvedValueOnce)
		await Promise.resolve();
		expect(onResolved).toHaveBeenCalledWith(seznam, 'second@example.com');

		// now resolve the first (stale) — it must NOT call onResolved again
		resolveFirst(gmail);
		await Promise.resolve();
		expect(onResolved).toHaveBeenCalledOnce();
	});
});
