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

/*
 * One request in flight per account, and at most one more queued behind it.
 *
 * A finished sync pass fires a folder event per folder that found mail and
 * then one for the pass, and each answers with a refresh — five `GET /folders`
 * inside a second on a three-folder pass, every one of them queueing behind
 * the IMAP connection lock the sync was holding.
 *
 * The tempting collapse — hand a late caller the request already in flight —
 * is wrong here, and quietly so: that request was issued *before* the event
 * this caller is reacting to, so its response can predate the change it is
 * being asked about. It is the same pre-sync-snapshot hazard the backend
 * invalidates its folder cache to avoid. Queueing one refetch behind instead
 * keeps the guarantee that every trigger is followed by a fetch issued after
 * it, while a burst of any length still costs two requests rather than N.
 */
const inFlight = new Map<number, Promise<FolderResponse[]>>();
const queued = new Map<number, Promise<FolderResponse[]>>();

export function loadFolders(accountId: number): Promise<FolderResponse[]> {
	const alreadyQueued = queued.get(accountId);
	// A refetch is already scheduled to start after the running one; it will be
	// issued after this trigger too, so it answers this caller as well.
	if (alreadyQueued) return alreadyQueued;

	const running = inFlight.get(accountId);
	if (!running) return startLoad(accountId);

	const next = running
		// The running request's outcome is its own callers' business; a failure
		// there must not cancel the refetch this caller still needs.
		.catch(() => undefined)
		.then(() => {
			queued.delete(accountId);
			return startLoad(accountId);
		});
	queued.set(accountId, next);
	return next;
}

async function startLoad(accountId: number): Promise<FolderResponse[]> {
	const load = fetchFolders(accountId);
	inFlight.set(accountId, load);
	try {
		return await load;
	} finally {
		// Only if it is still ours: a queued refetch may have replaced it.
		if (inFlight.get(accountId) === load) inFlight.delete(accountId);
	}
}

async function fetchFolders(accountId: number): Promise<FolderResponse[]> {
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

/**
 * Test seam — the in-flight bookkeeping outlives a single test otherwise, and
 * a queued refetch from one case would resolve inside the next.
 *
 * @testseam
 */
export function resetFolderLoads(): void {
	inFlight.clear();
	queued.clear();
}

/**
 * Optimistically shifts a folder's unread count in the active-account cache.
 * Used after a delete/move whose server-side effect is async: a folder
 * re-fetch here would still read the pre-move count (the IMAP move has not
 * landed yet), so the heading badge would stay stale. The next sync reconciles
 * the real value. No-op when the folder is not currently cached.
 */
export function adjustFolderUnread(accountId: number, folderRef: string, delta: number): void {
	if (delta === 0) return;
	foldersByAccount.update((map) => {
		const list = map[accountId];
		if (!list) return map;
		let changed = false;
		const next = list.map((folder) => {
			if (folder.folderRef !== folderRef) return folder;
			changed = true;
			return { ...folder, unreadCount: Math.max(0, folder.unreadCount + delta) };
		});
		return changed ? { ...map, [accountId]: next } : map;
	});
}

resolvedActiveAccountId.subscribe((id) => {
	if (id == null) return;
	const cached = get(foldersByAccount)[id];
	if (!cached) void loadFolders(id).catch(() => undefined);
});
