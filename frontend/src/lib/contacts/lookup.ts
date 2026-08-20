/**
 * Is this address already in the address book?
 *
 * There is no endpoint for the question, and the search that answers it is
 * substring-based (`ContactService.searchContacts`), so the match is confirmed
 * here rather than trusted: querying "jan@example.com" also returns a contact
 * at "jan@example.com.invalid", and taking the first row would call that a hit.
 *
 * Deliberately without a cache. The answer goes stale the moment a contact is
 * created or edited anywhere in the app, so keeping it would mean invalidating
 * it from every one of those places — more moving parts than the one small
 * query it would save against a database on the same machine.
 */

import { listContacts } from '$lib/api/contacts.js';
import type { ContactResponse } from '$lib/types.js';

/**
 * Enough rows that the exact match is among them even when the address is a
 * prefix of several others, few enough to stay one cheap page.
 */
const LOOKUP_PAGE_SIZE = 10;

export async function findContactByEmail(email: string): Promise<ContactResponse | null> {
	const wanted = email.trim().toLowerCase();
	if (!wanted) return null;

	const page = await listContacts({ q: wanted, size: LOOKUP_PAGE_SIZE });
	return (
		page.content.find((contact) =>
			contact.emails.some((entry) => entry.email.toLowerCase() === wanted)
		) ?? null
	);
}
