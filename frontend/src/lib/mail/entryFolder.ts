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
 * The decision follows Outlook: back to the folder the account was last
 * showing, so leaving Mail for Contacts or Settings (Ctrl+2, Ctrl+3) or for the
 * composer and coming back with Ctrl+1 or Esc does not lose the folder. Before
 * any folder has been shown it is the INBOX if the account has one, otherwise
 * whatever folder comes first — which is also the answer on every start, as in
 * Outlook, because the memory lives for the session only. Ctrl+Shift+I asks for
 * the Inbox by name and skips the memory. It lives here so the route that still
 * has to make the decision asynchronously and the load that can make it from
 * cache agree on the answer.
 */
import { resolve } from '$app/paths';
import type { FolderResponse } from '#lib/types.js';

/** Last folder shown per account, by `folderRef`. Session memory only. */
const lastFolderByAccount = new Map<number, string>();

/** Records that `accountId` is showing `folderRef`; called by the folder route. */
export function rememberFolder(accountId: number, folderRef: string): void {
	lastFolderByAccount.set(accountId, folderRef);
}

/** The folder `accountId` was last showing, if any this session. */
export function lastFolderRef(accountId: number): string | undefined {
	return lastFolderByAccount.get(accountId);
}

/**
 * The folder Mail opens on: `remembered` while the account still lists it,
 * then the INBOX, then the first folder. A remembered folder that has since
 * been deleted or renamed is passed over rather than opened as an empty
 * listing. `undefined` means the account has no folders at all, which is a
 * state of its own — an empty mailbox, not a failure.
 */
export function pickEntryFolder(
	list: readonly FolderResponse[],
	remembered?: string
): FolderResponse | undefined {
	return (
		(remembered === undefined
			? undefined
			: list.find((folder) => folder.folderRef === remembered)) ??
		list.find((folder) => folder.role === 'INBOX') ??
		list[0]
	);
}

/** The route that shows `folder`, ready to navigate to. */
export function folderHref(accountId: number, folder: FolderResponse): string {
	return resolve('/mail/[accountId]/[folderName]', {
		accountId: String(accountId),
		folderName: encodeURIComponent(folder.folderRef)
	});
}
