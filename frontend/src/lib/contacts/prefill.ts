/**
 * The link between an open message and the new-contact form: how the sender
 * travels into the form as a prefill, and how the form finds its way back.
 *
 * It is carried in the URL rather than in a store because the form is a route.
 * That makes the prefill a bookmarkable, hand-editable input, so every value is
 * validated on the way in — shape only, the way the rest of `contacts/+page.ts`
 * validates its params: a malformed one is dropped and the form opens empty
 * instead of failing.
 */

import { resolve } from '$app/paths';
import { isValidEmailAddress } from '$lib/compose/addresses.js';

/** What the form starts with when it was opened from a message. */
export interface ContactPrefill {
	email: string;
	name: string | null;
	surname: string | null;
}

/** `ContactCreateRequest` caps both name fields at 255. */
const MAX_NAME_LENGTH = 255;

/**
 * Where the form returns to. Only a message route qualifies: the parameter
 * decides a navigation, so accepting an arbitrary string would let a crafted
 * link send the user anywhere — and "anywhere" in this shell includes the
 * routes that talk to the mailbox. The `/mail/` prefix also rules out a
 * protocol-relative `//host` target.
 */
export function readReturnTo(params: URLSearchParams): string | null {
	const raw = params.get('returnTo');
	return raw && raw.startsWith('/mail/') ? raw : null;
}

export function readContactPrefill(params: URLSearchParams): ContactPrefill | null {
	const email = params.get('email')?.trim();
	if (!email || !isValidEmailAddress(email)) return null;

	return {
		email,
		name: readName(params.get('name')),
		surname: readName(params.get('surname'))
	};
}

function readName(raw: string | null): string | null {
	const trimmed = raw?.trim();
	if (!trimmed) return null;
	// Truncated rather than dropped: an over-long name is still the best guess
	// at who this is, and the form is where the user fixes it.
	return trimmed.slice(0, MAX_NAME_LENGTH);
}

/** Link to the new-contact form, seeded with what the message knows. */
export function contactCreateHref(prefill: ContactPrefill, returnTo?: string | null): string {
	const params = new URLSearchParams({ create: '1', email: prefill.email });
	if (prefill.name) params.set('name', prefill.name);
	if (prefill.surname) params.set('surname', prefill.surname);
	if (returnTo) params.set('returnTo', returnTo);
	return `${resolve('/contacts')}?${params.toString()}`;
}

/** Link to the form of a contact that already exists. */
export function contactEditHref(contactId: number, returnTo?: string | null): string {
	const params = new URLSearchParams({ edit: String(contactId) });
	if (returnTo) params.set('returnTo', returnTo);
	return `${resolve('/contacts')}?${params.toString()}`;
}
