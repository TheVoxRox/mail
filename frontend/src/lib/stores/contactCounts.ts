/**
 * Store for the contact counts of the active account (sidebar badges).
 *
 * Mirrors the folders store: on `activeAccountId` change the counts load
 * automatically, cached per account so switching does not flicker. Mutation
 * sites (create/edit/delete/merge/import) call `refreshContactCounts`.
 */

import { derived, get, writable } from 'svelte/store';
import { getContactCounts } from '$lib/api/contacts.js';
import type { ContactCountsResponse } from '$lib/types.js';
import { resolvedActiveAccountId } from './accounts.js';

type CountsByAccount = Record<number, ContactCountsResponse>;

const countsByAccount = writable<CountsByAccount>({});

export const contactCounts = derived(
	[countsByAccount, resolvedActiveAccountId],
	([$countsByAccount, $resolvedActiveAccountId]): ContactCountsResponse | null => {
		if ($resolvedActiveAccountId == null) return null;
		return $countsByAccount[$resolvedActiveAccountId] ?? null;
	}
);

/**
 * Loads the counts for one account. Failures are swallowed — the badges are
 * secondary information and the list itself reports its own errors; a failed
 * refresh just leaves the previous (or no) badge values in place.
 */
export async function refreshContactCounts(accountId: number): Promise<void> {
	try {
		const counts = await getContactCounts(accountId);
		countsByAccount.update((map) => ({ ...map, [accountId]: counts }));
	} catch {
		// Keep stale values; the next successful refresh reconciles them.
	}
}

resolvedActiveAccountId.subscribe((id) => {
	if (id == null) return;
	const cached = get(countsByAccount)[id];
	if (!cached) void refreshContactCounts(id);
});
