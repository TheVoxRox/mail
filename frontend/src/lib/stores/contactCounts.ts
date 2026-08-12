/**
 * Store for the contact counts behind the sidebar badges.
 *
 * There is one address book for the whole application, so unlike the folders
 * store this holds a single value rather than a per-account cache — switching
 * mailbox does not change the address book. Mutation sites
 * (create/edit/delete/merge/import) call `refreshContactCounts`.
 */

import { get, writable } from 'svelte/store';
import { getContactCounts } from '$lib/api/contacts.js';
import type { ContactCountsResponse } from '$lib/types.js';

export const contactCounts = writable<ContactCountsResponse | null>(null);

/**
 * Reloads the counts. Failures are swallowed — the badges are secondary
 * information and the list itself reports its own errors; a failed refresh just
 * leaves the previous (or no) badge values in place.
 */
export async function refreshContactCounts(): Promise<void> {
	try {
		contactCounts.set(await getContactCounts());
	} catch {
		// Keep stale values; the next successful refresh reconciles them.
	}
}

/** First read is lazy: whoever renders the badges asks for them once. */
export function ensureContactCounts(): void {
	if (get(contactCounts) == null) void refreshContactCounts();
}
