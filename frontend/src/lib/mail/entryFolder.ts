/**
 * Where the Mail workspace opens.
 *
 * `/` is not a place. It is the routing decision "which folder does Mail open
 * on", rendered as a page — and that cost a screen reader user a false stop:
 * focus landed on a `<main>` named after the accountless welcome screen and the
 * reader announced it, on the way to a folder the app had already decided on.
 * Measured over an NVDA session on 2026-09-03, on the ordinary return from
 * Settings to Mail as well as on Esc out of the composer.
 *
 * The decision itself is one line of choice — the INBOX if the account has one,
 * otherwise whatever folder comes first — and it lives here so the route that
 * still has to make it asynchronously and the load that can make it from cache
 * agree on the answer.
 */
import { resolve } from '$app/paths';
import type { FolderResponse } from '$lib/types.js';

/**
 * The folder Mail opens on. `undefined` means the account has no folders at
 * all, which is a state of its own — an empty mailbox, not a failure.
 */
export function pickEntryFolder(list: readonly FolderResponse[]): FolderResponse | undefined {
	return list.find((folder) => folder.role === 'INBOX') ?? list[0];
}

/** The route that shows `folder`, ready to navigate to. */
export function folderHref(accountId: number, folder: FolderResponse): string {
	return resolve('/mail/[accountId]/[folderName]', {
		accountId: String(accountId),
		folderName: encodeURIComponent(folder.folderRef)
	});
}
