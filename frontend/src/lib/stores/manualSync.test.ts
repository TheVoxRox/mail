import { get } from 'svelte/store';
import { addMessages, init } from 'svelte-i18n';
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import csMessages from '$lib/i18n/messages/cs.json';
import {
	abandonManualSync,
	beginManualSync,
	completeManualSync,
	releaseManualSyncsOnStreamLoss,
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

		completeManualSync(4, 0, true);

		expect(get(syncingAccountIds)).toEqual([]);
	});

	it('announces a pass that found nothing — the case no folder event covers', () => {
		beginManualSync(4);
		completeManualSync(4, 0, true);

		expect(announcements()).toContain('Synchronizace dokončena, žádné nové zprávy.');
	});

	it('leaves the counting to the folder toasts when the pass did find mail', () => {
		beginManualSync(4);
		completeManualSync(4, 3, true);

		// The per-folder toast already said how many and where; repeating the
		// number here would read the same fact twice into the live region.
		expect(announcements()).toContain('Synchronizace dokončena.');
	});

	it('will not call a zero "no new messages" when the pass skipped a folder', () => {
		beginManualSync(4);
		// A folder whose own cycle was already running downloaded into the same
		// mailbox; its toast would contradict the claim.
		completeManualSync(4, 0, false);

		expect(announcements()).toContain('Synchronizace dokončena.');
		expect(announcements()).not.toContain('Synchronizace dokončena, žádné nové zprávy.');
	});

	it('leaves other accounts waiting when one of them finishes', () => {
		beginManualSync(4);
		beginManualSync(9);

		completeManualSync(4, 0, true);

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
		completeManualSync(4, 0, true);
		politeAnnouncements.set([]);

		vi.advanceTimersByTime(180_000);

		expect(announcements()).toEqual([]);
	});

	it('releases every wait when the notification stream drops', () => {
		beginManualSync(4);
		beginManualSync(9);
		politeAnnouncements.set([]);

		releaseManualSyncsOnStreamLoss();

		// A reconnect replays nothing, so the completions can never arrive; the
		// button must not sit disabled for the rest of the timeout.
		expect(get(syncingAccountIds)).toEqual([]);
		expect(announcements()).toContain('Synchronizace pokračuje na pozadí.');
	});

	it('stays quiet about a stream drop when nothing was waiting', () => {
		releaseManualSyncsOnStreamLoss();

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
