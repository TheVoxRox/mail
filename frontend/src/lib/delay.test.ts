import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { delayWithAbort } from './delay.js';

describe('delayWithAbort', () => {
	beforeEach(() => {
		vi.useFakeTimers();
	});

	afterEach(() => {
		vi.useRealTimers();
	});

	it('resolves after the given delay without a signal', async () => {
		let resolved = false;
		void delayWithAbort(200).then(() => {
			resolved = true;
		});

		await vi.advanceTimersByTimeAsync(199);
		expect(resolved).toBe(false);
		await vi.advanceTimersByTimeAsync(1);
		expect(resolved).toBe(true);
	});

	it('resolves immediately when the signal is already aborted', async () => {
		const controller = new AbortController();
		controller.abort();

		let resolved = false;
		void delayWithAbort(10_000, controller.signal).then(() => {
			resolved = true;
		});

		await vi.advanceTimersByTimeAsync(0);
		expect(resolved).toBe(true);
	});

	it('resolves (never rejects) as soon as the signal aborts mid-delay', async () => {
		const controller = new AbortController();

		let resolved = false;
		void delayWithAbort(10_000, controller.signal).then(() => {
			resolved = true;
		});

		await vi.advanceTimersByTimeAsync(100);
		expect(resolved).toBe(false);
		controller.abort();
		await vi.advanceTimersByTimeAsync(0);
		expect(resolved).toBe(true);
	});

	it('removes the abort listener when the timer wins (no accumulation on a shared signal)', async () => {
		const controller = new AbortController();
		const removeSpy = vi.spyOn(controller.signal, 'removeEventListener');

		// Mirrors the production pattern: one long-lived signal shared across
		// many polling iterations — each finished delay must detach its listener.
		await Promise.all([
			(async () => {
				for (let i = 0; i < 3; i++) {
					await delayWithAbort(100, controller.signal);
				}
			})(),
			vi.advanceTimersByTimeAsync(300)
		]);

		expect(removeSpy).toHaveBeenCalledTimes(3);
	});
});
