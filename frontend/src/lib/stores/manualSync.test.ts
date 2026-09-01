import { get } from 'svelte/store';
import { addMessages, init } from 'svelte-i18n';
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import csMessages from '$lib/i18n/messages/cs.json';
import {
	abandonManualSync,
	beginManualSync,
	completeManualSync,
	resetManualSync,
	syncingAccountIds
} from './manualSync.js';
import { politeAnnouncements } from './toasts.js';

beforeAll(() => {
	addMessages('cs', csMessages);
	void init({ fallbackLocale: 'cs', initialLocale: 'cs' });
});

function announcements(): string[] {
	return get(politeAnnouncements).map((entry) => entry.message);
}

describe('manual sync waiting state', () => {
	beforeEach(() => {
		vi.useFakeTimers();
		resetManualSync();
		politeAnnouncements.set([]);
	});

	afterEach(() => {
		resetManualSync();
		vi.useRealTimers();
	});

	it('waits for the account until its pass reports back', () => {
		beginManualSync(4);
		expect(get(syncingAccountIds)).toEqual([4]);

		completeManualSync(4, 0);

		expect(get(syncingAccountIds)).toEqual([]);
	});

	it('announces a pass that found nothing — the case no folder event covers', () => {
		beginManualSync(4);
		completeManualSync(4, 0);

		expect(announcements()).toContain('Synchronizace dokončena, žádné nové zprávy.');
	});

	it('announces the message count when the pass downloaded something', () => {
		beginManualSync(4);
		completeManualSync(4, 3);

		expect(announcements()).toContain('Synchronizace dokončena, 3 nové zprávy.');
	});

	it('leaves other accounts waiting when one of them finishes', () => {
		beginManualSync(4);
		beginManualSync(9);

		completeManualSync(4, 0);

		expect(get(syncingAccountIds)).toEqual([9]);
	});

	it('stops waiting when the trigger itself failed, and says nothing about a pass that never ran', () => {
		beginManualSync(4);
		politeAnnouncements.set([]);

		abandonManualSync(4);

		expect(get(syncingAccountIds)).toEqual([]);
		expect(announcements()).toEqual([]);
	});

	it('gives up after the timeout without claiming the pass finished', () => {
		beginManualSync(4);

		vi.advanceTimersByTime(180_000);

		expect(get(syncingAccountIds)).toEqual([]);
		// "Finished" would be a guess; the pass may well still be running.
		expect(announcements()).toContain('Synchronizace pokračuje na pozadí.');
	});

	it('does not give up on a pass that reported back before the timeout', () => {
		beginManualSync(4);
		completeManualSync(4, 0);
		politeAnnouncements.set([]);

		vi.advanceTimersByTime(180_000);

		expect(announcements()).toEqual([]);
	});

	it('an abandoned trigger cannot fire the timeout of a later attempt', () => {
		beginManualSync(4);
		abandonManualSync(4);
		beginManualSync(4);
		vi.advanceTimersByTime(179_000);
		politeAnnouncements.set([]);

		// The second attempt owns the deadline; the first one's is gone.
		vi.advanceTimersByTime(1_000);

		expect(announcements()).toContain('Synchronizace pokračuje na pozadí.');
	});
});
