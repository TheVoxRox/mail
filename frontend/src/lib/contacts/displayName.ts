/**
 * Splitting one display name into the two fields a contact has.
 *
 * The rule is the last space: everything before it is the given name (so
 * "Jan Evangelista Novak" keeps both given names together), everything after it
 * is the surname, and a single word is a given name with no surname. That is a
 * guess — plenty of names do not work that way — which is why both callers put
 * the result somewhere the user can correct it rather than saving it blind.
 *
 * It lives here because there are two of those callers: the vCard import
 * reading `FN`, and the prefill built from a message's `From` header. They were
 * one copied line apart from each other, and two guesses that drift are worse
 * than one guess that is wrong the same way everywhere.
 */
export function splitDisplayName(full: string): { name: string | null; surname: string | null } {
	const trimmed = full.trim();
	if (!trimmed) return { name: null, surname: null };

	const lastSpace = trimmed.lastIndexOf(' ');
	if (lastSpace <= 0) return { name: trimmed, surname: null };

	return {
		name: trimmed.slice(0, lastSpace).trim() || null,
		surname: trimmed.slice(lastSpace + 1).trim() || null
	};
}
