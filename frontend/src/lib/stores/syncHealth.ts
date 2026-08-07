/**
 * Which accounts are currently failing to synchronize.
 *
 * Fed by the `sync_failed` / `sync_recovered` notifications rather than derived
 * from `AccountResponse.lastErrorCode`, because the account carries a single
 * error slot shared with the send pipeline: a standing `SMTP_SEND_FAILED` must
 * not light up a "mail is not arriving" indicator, and the send path already
 * has its own toast. The event is the only thing that knows the transition was
 * a sync one.
 *
 * The failure *text* still comes from the account — the backend localizes it in
 * `AccountMapper` — so the indicator, the toast and Settings → Accounts can
 * never disagree about the same failure.
 */

import { derived, writable } from 'svelte/store';
import { accountsState } from './accounts.js';
import type { AccountResponse } from '$lib/types.js';

/** Account ids whose last sync pass reported a failure. */
export const failingSyncAccountIds = writable<readonly number[]>([]);

export function markSyncFailed(accountId: number): void {
	failingSyncAccountIds.update((ids) => (ids.includes(accountId) ? ids : [...ids, accountId]));
}

export function markSyncRecovered(accountId: number): void {
	failingSyncAccountIds.update((ids) => ids.filter((id) => id !== accountId));
}

/** Test seam — the stream is the only writer in the app itself. */
export function resetSyncHealth(): void {
	failingSyncAccountIds.set([]);
}

export interface FailingAccount {
	id: number;
	email: string;
	/** Localized standing error from the backend; null if the account went away. */
	detail: string | null;
}

/**
 * Failing accounts joined with their localized error text. Accounts that have
 * disappeared from the list (deleted while failing) drop out entirely — an
 * indicator pointing at a row that no longer exists in Settings would be a
 * dead end.
 */
export const failingSyncAccounts = derived(
	[failingSyncAccountIds, accountsState],
	([$ids, $accounts]): FailingAccount[] => {
		if ($accounts.status !== 'ready' || $ids.length === 0) return [];
		return $ids
			.map((id) => $accounts.accounts.find((account: AccountResponse) => account.id === id))
			.filter((account): account is AccountResponse => account != null)
			.map((account) => ({ id: account.id, email: account.email, detail: account.lastError }));
	}
);
