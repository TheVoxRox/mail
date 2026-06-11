/**
 * Store for the IMAP folders of the active account.
 *
 * On `activeAccountId` change the folders reload automatically. We keep a
 * per-account map so fast switching between accounts does not flicker.
 */

import { derived, get, writable } from 'svelte/store';
import { listFolders } from '$lib/api/folders.js';
import { toError } from '$lib/api/errors.js';
import type { FolderResponse } from '$lib/types.js';
import { resolvedActiveAccountId } from './accounts.js';

type FoldersByAccount = Record<number, FolderResponse[]>;

type FoldersState =
	| { status: 'idle' }
	| { status: 'loading' }
	| { status: 'ready' }
	| { status: 'error'; error: Error };

export const foldersState = writable<FoldersState>({ status: 'idle' });
const foldersByAccount = writable<FoldersByAccount>({});

export const folders = derived(
	[foldersByAccount, resolvedActiveAccountId],
	([$foldersByAccount, $resolvedActiveAccountId]): FolderResponse[] => {
		if ($resolvedActiveAccountId == null) return [];
		return $foldersByAccount[$resolvedActiveAccountId] ?? [];
	}
);

export async function loadFolders(accountId: number): Promise<FolderResponse[]> {
	foldersState.set({ status: 'loading' });
	try {
		const list = await listFolders(accountId);
		foldersByAccount.update((map) => ({ ...map, [accountId]: list }));
		foldersState.set({ status: 'ready' });
		return list;
	} catch (err) {
		const error = toError(err);
		foldersState.set({ status: 'error', error });
		throw error;
	}
}

/** Forces a folder refresh for a specific account (e.g. after sync_completed). */
export function refreshFolders(accountId: number): Promise<FolderResponse[]> {
	return loadFolders(accountId);
}

resolvedActiveAccountId.subscribe((id) => {
	if (id == null) return;
	const cached = get(foldersByAccount)[id];
	if (!cached) void loadFolders(id).catch(() => undefined);
});
