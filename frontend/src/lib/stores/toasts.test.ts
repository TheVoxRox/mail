import { get } from 'svelte/store';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
	dismissToast,
	pauseToastCountdowns,
	pushToast,
	resumeToastCountdowns,
	toasts
} from './toasts.js';
import { setToastDismissal } from './uiLayout.js';

const messages = () => get(toasts).map((toast) => toast.message);

describe('toast countdowns (WCAG 2.2.1)', () => {
	beforeEach(() => {
		vi.useFakeTimers();
		setToastDismissal('auto');
	});

	afterEach(() => {
		resumeToastCountdowns();
		for (const toast of get(toasts)) dismissToast(toast.id);
		setToastDismissal('auto');
		vi.useRealTimers();
	});

	it('closes a toast by itself once its time is up', () => {
		pushToast('Saved', { ttl: 5000 });
		vi.advanceTimersByTime(4999);
		expect(messages()).toEqual(['Saved']);
		vi.advanceTimersByTime(1);
		expect(messages()).toEqual([]);
	});

	it('keeps every toast when automatic closing is off', () => {
		setToastDismissal('manual');
		pushToast('Saved', { ttl: 5000 });
		vi.advanceTimersByTime(60_000);
		expect(messages()).toEqual(['Saved']);
	});

	it('stops the clocks of toasts already shown when automatic closing is turned off', () => {
		pushToast('Saved', { ttl: 5000 });
		vi.advanceTimersByTime(3000);
		setToastDismissal('manual');
		vi.advanceTimersByTime(60_000);
		expect(messages()).toEqual(['Saved']);
	});

	it('stops the clock while paused and resumes it with the time that was left', () => {
		pushToast('Saved', { ttl: 5000 });
		vi.advanceTimersByTime(3000);
		pauseToastCountdowns();
		vi.advanceTimersByTime(60_000);
		expect(messages()).toEqual(['Saved']);

		resumeToastCountdowns();
		vi.advanceTimersByTime(1999);
		expect(messages()).toEqual(['Saved']);
		vi.advanceTimersByTime(1);
		expect(messages()).toEqual([]);
	});

	it('starts a toast pushed during a pause with its full time on resume', () => {
		pauseToastCountdowns();
		pushToast('Saved', { ttl: 5000 });
		vi.advanceTimersByTime(60_000);
		resumeToastCountdowns();
		vi.advanceTimersByTime(4999);
		expect(messages()).toEqual(['Saved']);
		vi.advanceTimersByTime(1);
		expect(messages()).toEqual([]);
	});
});
