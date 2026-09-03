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
 * The pass reported itself finished.
 *
 * Only one of the two things worth saying is said. "No new messages" is the
 * case the per-folder `sync_completed` toast never covered, and the reason this
 * event exists — but it may only be claimed when the pass actually ran every
 * folder (`allFoldersSynced`); a folder skipped because its own cycle was
 * already running downloads into the same mailbox, and its toast would then
 * contradict this sentence. Otherwise the announcement stops at "finished" and
 * leaves the counting to those toasts, which name the folder and are not a
 * second reading of the same number.
 */
export function completeManualSync(
	accountId: number,
	newMessagesCount: number,
	allFoldersSynced: boolean
): void {
	stopWaiting(accountId);
	announcePolite(get(_)(completionMessageKey(newMessagesCount, allFoldersSynced)));
}

/**
 * A pass that did not cover every folder may not be announced as finished.
 *
 * `allFoldersSynced === false` covers two different things — a folder skipped
 * because its own cycle already held it, and a pass that failed outright — and
 * both make the count a floor rather than a total, so neither may claim the
 * sync is done. Previously only the "no new messages" half was guarded and the
 * fallback was `nav.syncFinished`, the wording meant for a pass that brought
 * mail.
 *
 * Found by NVDA listening 2026-09-03 with the mail server unreachable: the
 * failed pass was announced as finished two seconds before the failure toast
 * reached the live region. On every *later* failing pass it is the only thing
 * said about the outcome at all — the edge-triggered `sync_failed` does not
 * fire again while the error code stays the same.
 */
function completionMessageKey(newMessagesCount: number, allFoldersSynced: boolean): string {
	if (!allFoldersSynced) return 'nav.syncFinishedIncomplete';
	return newMessagesCount === 0 ? 'nav.syncFinishedEmpty' : 'nav.syncFinished';
}

/** The trigger itself failed, so no pass is coming to report anything. */
export function abandonManualSync(accountId: number): void {
	stopWaiting(accountId);
}

/**
 * The notification stream dropped, so no pending completion can still arrive —
 * a reconnect opens a fresh emitter and the backend replays nothing. Releasing
 * the waits here is what keeps the Synchronise button from sitting disabled for
 * the full timeout on the one failure the client already knows about.
 *
 * Says the same thing the timeout says, for the same reason: the passes are
 * still running, we have only stopped being able to hear them finish.
 */
export function releaseManualSyncsOnStreamLoss(): void {
	const waiting = get(syncingAccountIds);
	if (waiting.length === 0) return;
	for (const accountId of waiting) stopWaiting(accountId);
	announcePolite(get(_)('nav.syncStillRunning'));
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
