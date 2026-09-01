import { get } from 'svelte/store';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { listFolders } from '$lib/api/folders.js';
import { folders, loadFolders, refreshFolders, resetFolderLoads } from './folders.js';
import { accountsState, activeAccountId } from './accounts.js';
import type { AccountResponse, FolderResponse } from '$lib/types.js';

vi.mock('$lib/api/folders.js', () => ({ listFolders: vi.fn() }));

const ACCOUNT = 4;

function folder(name: string, unreadCount: number): FolderResponse {
	return { ...({} as FolderResponse), folderRef: name, displayName: name, unreadCount };
}

/** A request that only resolves when the test says so. */
function deferred(): {
	promise: Promise<FolderResponse[]>;
	resolve: (v: FolderResponse[]) => void;
} {
	let resolve!: (v: FolderResponse[]) => void;
	const promise = new Promise<FolderResponse[]>((r) => {
		resolve = r;
	});
	return { promise, resolve };
}

describe('folder loads are coalesced per account', () => {
	beforeEach(() => {
		resetFolderLoads();
		vi.mocked(listFolders).mockReset();
	});

	afterEach(() => {
		resetFolderLoads();
	});

	it('collapses a burst of refreshes into two requests, however long the burst', async () => {
		const first = deferred();
		vi.mocked(listFolders)
			.mockReturnValueOnce(first.promise)
			.mockResolvedValue([folder('INBOX', 1)]);

		// A finished pass fires one folder event per folder plus one for the
		// pass; every one of them asks for a refresh.
		const all = [
			refreshFolders(ACCOUNT),
			refreshFolders(ACCOUNT),
			refreshFolders(ACCOUNT),
			refreshFolders(ACCOUNT),
			refreshFolders(ACCOUNT)
		];
		expect(vi.mocked(listFolders)).toHaveBeenCalledTimes(1);

		first.resolve([folder('INBOX', 0)]);
		await Promise.all(all);

		// One in flight, one queued behind it — never five.
		expect(vi.mocked(listFolders)).toHaveBeenCalledTimes(2);
	});

	it('never answers a later caller with a response issued before it asked', async () => {
		const stale = deferred();
		vi.mocked(listFolders)
			.mockReturnValueOnce(stale.promise)
			.mockResolvedValue([folder('INBOX', 7)]);

		const early = refreshFolders(ACCOUNT);
		// This caller is reacting to a sync that finished after the request
		// above went out, so that response cannot answer it.
		const late = refreshFolders(ACCOUNT);

		stale.resolve([folder('INBOX', 0)]);

		expect(await early).toEqual([folder('INBOX', 0)]);
		expect(await late).toEqual([folder('INBOX', 7)]);
	});

	it('leaves the store holding what the last request returned', async () => {
		const first = deferred();
		vi.mocked(listFolders)
			.mockReturnValueOnce(first.promise)
			.mockResolvedValue([folder('INBOX', 9)]);
		// `folders` reads through resolvedActiveAccountId, which needs a loaded
		// account list before it resolves to anything.
		accountsState.set({
			status: 'ready',
			accounts: [{ ...({} as AccountResponse), id: ACCOUNT }]
		});
		activeAccountId.set(ACCOUNT);

		const all = [refreshFolders(ACCOUNT), refreshFolders(ACCOUNT)];
		first.resolve([folder('INBOX', 0)]);
		await Promise.all(all);

		expect(get(folders)).toEqual([folder('INBOX', 9)]);
	});

	it('starts a fresh request once the burst has drained', async () => {
		vi.mocked(listFolders).mockResolvedValue([folder('INBOX', 1)]);

		await refreshFolders(ACCOUNT);
		await refreshFolders(ACCOUNT);

		// Sequential callers are not a burst; each gets its own request.
		expect(vi.mocked(listFolders)).toHaveBeenCalledTimes(2);
	});

	it('keeps accounts independent', async () => {
		const first = deferred();
		vi.mocked(listFolders)
			.mockReturnValueOnce(first.promise)
			.mockResolvedValue([folder('INBOX', 2)]);

		const a = loadFolders(ACCOUNT);
		const b = loadFolders(9);
		expect(vi.mocked(listFolders)).toHaveBeenCalledTimes(2);

		first.resolve([folder('INBOX', 0)]);
		await Promise.all([a, b]);
	});

	it('a failed request does not swallow the refetch queued behind it', async () => {
		const failing = deferred();
		vi.mocked(listFolders)
			.mockReturnValueOnce(failing.promise)
			.mockResolvedValue([folder('INBOX', 3)]);

		const doomed = refreshFolders(ACCOUNT);
		const queued = refreshFolders(ACCOUNT);

		// A transient failure of the in-flight request says nothing about
		// whether the caller behind it still needs its answer.
		(failing as unknown as { resolve: (v: unknown) => void }).resolve(
			Promise.reject(new Error('offline'))
		);

		await expect(doomed).rejects.toThrow('offline');
		expect(await queued).toEqual([folder('INBOX', 3)]);
	});
});
