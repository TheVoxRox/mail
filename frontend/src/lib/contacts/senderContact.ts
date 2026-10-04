/**
 * Turning the `From` line of an open message into the seed of a new contact.
 *
 * The detail response carries the sender as one display string, `"Personal
 * <address>"` or a bare address (`MessageFetcher.formatAddress`), and nothing
 * else — the bare address on the content response arrives with the body, which
 * is a later request and is missing entirely when the body fails to load. The
 * header is therefore the only source available at the moment the affordance
 * has to be rendered, so both halves are read from it here.
 *
 * The address is taken the way the backend takes it in
 * `HeaderAddresses.labelAddress`: what the label's last `<…>` holds when the
 * label ends with one, the whole string otherwise. `formatAddress` writes the
 * address last and does not quote the personal part, so any bracket a name
 * holds comes before it. The first `<` and the first `>` used to decide it, so
 * a name holding an address of its own — `Alice <a@evil> <a@real>` — prefilled
 * the form with that one (IMAP/SMTP audit B1-11). What the backend does not do
 * — and this does — is refuse the result when it is not an address at all:
 * `sender` may be the localized "(unknown sender)" fallback, and no contact can
 * be seeded from that.
 */

import { isValidEmailAddress } from '$lib/compose/addresses.js';
import { splitDisplayName } from '$lib/contacts/displayName.js';

/** Everything a prefilled contact form can learn from a message's sender. */
export interface SenderContactSeed {
	email: string;
	name: string | null;
	surname: string | null;
}

export function senderContactSeed(sender: string | null | undefined): SenderContactSeed | null {
	if (!sender) return null;

	const raw = sender.trim();
	const open = raw.lastIndexOf('<');
	const bracketed = open >= 0 && raw.endsWith('>');

	const email = (bracketed ? raw.slice(open + 1, -1) : raw).trim();
	if (!isValidEmailAddress(email)) return null;

	// A bare address carries no name, and the address must not become one —
	// "jan" as the given name of jan@example.com is noise the user has to delete.
	const personal = bracketed ? raw.slice(0, open).trim() : '';
	const { name, surname } = splitDisplayName(personal);
	return { email, name, surname };
}
