/**
 * Where a row of a folder listing opens. The flat list and the conversation
 * list each carried both builders, word for word.
 *
 * Which of the two a row uses is the list's business: the flat list asks the
 * folder in view, the conversation list asks the folder the message itself is
 * in, because an expanded thread shows members of other folders.
 */
import { resolve } from '$app/paths';

/** A message opened read-only, under the folder it lives in. */
export function messageHref(accountId: number, folderName: string, stableId: string): string {
	return resolve('/mail/[accountId]/[folderName]/[stableId]', {
		accountId: String(accountId),
		folderName: encodeURIComponent(folderName),
		stableId: encodeURIComponent(stableId)
	});
}

/** A draft reopened in the composer, where it can be sent. */
export function draftHref(stableId: string): string {
	return `${resolve('compose')}?draft=${encodeURIComponent(stableId)}`;
}
