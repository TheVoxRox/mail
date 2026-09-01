/**
 * Which accounts have a sync the user asked for still running.
 *
 * The Synchronise button used to answer its own question: it flipped back to
 * idle as soon as the 202 and the follow-up folder refresh returned, measured
 * ~3.7 s into a pass that ran for 12. So the button said "done" while the sync
 * was still going, and a screen-reader user got "started" and then silence.
 *
 * The truthful end of a pass is only known on the backend, and it says so with
 * `sync_cycle_completed`. That event is the writer here; the store exists
 * because the event arrives in the notifications stream while the button lives
 * in the sidebar.
 */

import { get, writable } from 'svelte/store';
import { _ } from 'svelte-i18n';
import { announcePolite } from './toasts.js';

/** Account ids whose user-triggered sync has not reported completion yet. */
export const syncingAccountIds = writable<readonly number[]>([]);

/**
 * How long to keep waiting for the completion event before letting the button
 * go. The pass is not cancelled and may still finish and announce itself; this
 * only stops the UI from waiting forever when the event cannot arrive at all —
 * a restarted sidecar, a stream that never reconnected.
 *
 * Generous on purpose: a first sync of a large mailbox legitimately runs for
 * minutes, and cutting it short would put "still running" in front of a user
 * whose sync was about to finish.
 */
const COMPLETION_TIMEOUT_MS = 180_000;

const timers = new Map<number, ReturnType<typeof setTimeout>>();

export function beginManualSync(accountId: number): void {
	clearTimer(accountId);
	syncingAccountIds.update((ids) => (ids.includes(accountId) ? ids : [...ids, accountId]));
	timers.set(
		accountId,
		setTimeout(() => {
			timers.delete(accountId);
			stopWaiting(accountId);
			/*
			 * Not "finished" — we do not know that. Saying so lets the user press
			 * Synchronise again, which the backend answers by having the running
			 * pass report for both requests.
			 */
			announcePolite(get(_)('nav.syncStillRunning'));
		}, COMPLETION_TIMEOUT_MS)
	);
}

/**
 * The pass reported itself finished. Announces the outcome including "no new
 * messages" — that is the whole point of the event, and the case the
 * per-folder `sync_completed` toast never covered.
 */
export function completeManualSync(accountId: number, newMessagesCount: number): void {
	stopWaiting(accountId);
	const translate = get(_);
	announcePolite(
		newMessagesCount > 0
			? translate('nav.syncFinishedWithMessages', { values: { count: newMessagesCount } })
			: translate('nav.syncFinishedEmpty')
	);
}

/** The trigger itself failed, so no pass is coming to report anything. */
export function abandonManualSync(accountId: number): void {
	stopWaiting(accountId);
}

function stopWaiting(accountId: number): void {
	clearTimer(accountId);
	syncingAccountIds.update((ids) => ids.filter((id) => id !== accountId));
}

function clearTimer(accountId: number): void {
	const timer = timers.get(accountId);
	if (timer) {
		clearTimeout(timer);
		timers.delete(accountId);
	}
}

/**
 * Test seam — the sidebar and the stream are the only writers in the app
 * itself.
 *
 * @testseam
 */
export function resetManualSync(): void {
	for (const timer of timers.values()) clearTimeout(timer);
	timers.clear();
	syncingAccountIds.set([]);
}
