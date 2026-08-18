/**
 * Route parameters, validated once instead of in every `+page.ts` that takes
 * them.
 *
 * Three loads take an account id and two of those a folder name on top; each
 * spelled out the same `Number(...)` / `Number.isInteger` / `error(400, …)`
 * dance, so the message a malformed URL produces existed in three copies that
 * nothing kept in step. The messages stay English on purpose: a 400 from a
 * hand-edited or stale URL is a developer-facing failure, not a user-facing
 * one, and these strings never reach the localized UI.
 */
import { error } from '@sveltejs/kit';

/**
 * A positive integer account id, or a 400.
 *
 * `Number('')` is 0 and `Number('1.5')` is not an integer, so both fall out of
 * the same check; ids start at 1, which is why 0 is rejected rather than
 * clamped.
 */
export function requireAccountId(raw: string): number {
	const accountId = Number(raw);
	if (!Number.isInteger(accountId) || accountId <= 0) {
		error(400, 'Invalid account ID.');
	}
	return accountId;
}

/** The decoded folder name, or a 400 when the segment is empty. */
export function requireFolderName(raw: string): string {
	const folderName = decodeURIComponent(raw);
	if (!folderName) {
		error(400, 'Missing folder name.');
	}
	return folderName;
}

/** The decoded message id, or a 400 when the segment is empty. */
export function requireStableId(raw: string): string {
	const stableId = decodeURIComponent(raw);
	if (!stableId) {
		error(400, 'Missing message ID.');
	}
	return stableId;
}
