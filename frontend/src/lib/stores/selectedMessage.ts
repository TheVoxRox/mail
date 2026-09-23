/**
 * Store for the currently selected message – holds the detail (headers +
 * attachments) and the content (HTML/plain), fetched together. A simple LRU
 * cache for detail/content enables fast switching between messages.
 */

import { writable } from 'svelte/store';
import { getMessageContent, getMessageDetail } from '$lib/api/mailRead.js';
import { ApiError } from '$lib/api/client.js';
import { toError } from '$lib/api/errors.js';
import { reloadCurrentPage } from '$lib/stores/messages.js';
import type { MailContentResponse, MailDetailResponse } from '$lib/types.js';

const CACHE_LIMIT = 30;

export interface SelectedMessage {
	stableId: string;
	detail: MailDetailResponse | null;
	content: MailContentResponse | null;
	loading: boolean;
	error: Error | null;
	/** The message no longer exists under this id (404) — a "ghost" from a stale list. */
	notFound: boolean;
}

export const selectedMessage = writable<SelectedMessage | null>(null);

/**
 * Where the message list should put focus after a mutation took away the
 * control the user was on.
 *
 * `row` names a message to land on: the flat list's neighbouring row, and in
 * the grouped list the conversation that message represents. `conversation` is
 * the grouped list's own shape — a thread survives a delete whenever another of
 * its messages is still in the folder, and then it is the row the user was
 * reading, but which message represents it has changed, so it is named by
 * thread and carries a neighbouring row for when it does not survive.
 * `emptied` says the list has no rows left, so the empty-state message is the
 * only place focus can go — without it focus would fall to `<body>`.
 */
export type ListFocusRestore =
	| { kind: 'row'; stableId: string }
	| { kind: 'conversation'; threadId: string; fallbackStableId: string | null }
	| { kind: 'emptied' };

export const listFocusRestore = writable<ListFocusRestore | null>(null);

const detailCache = new Map<string, MailDetailResponse>();
const contentCache = new Map<string, MailContentResponse>();

function touch<T>(cache: Map<string, T>, key: string, value: T) {
	cache.delete(key);
	cache.set(key, value);
	while (cache.size > CACHE_LIMIT) {
		const oldest = cache.keys().next().value;
		if (oldest === undefined) break;
		cache.delete(oldest);
	}
}

let currentToken = 0;

export function clearSelection(): void {
	currentToken++;
	selectedMessage.set(null);
}

export function requestListFocusRestore(stableId: string): void {
	listFocusRestore.set({ kind: 'row', stableId });
}

/** The grouped list's restore request — see {@link ListFocusRestore}. */
export function requestConversationFocusRestore(
	threadId: string,
	fallbackStableId: string | null
): void {
	listFocusRestore.set({ kind: 'conversation', threadId, fallbackStableId });
}

/** The mutation removed the last row — focus belongs on the empty state. */
export function requestEmptyListFocus(): void {
	listFocusRestore.set({ kind: 'emptied' });
}

export function clearListFocusRestore(): void {
	listFocusRestore.set(null);
}

export async function selectMessage(stableId: string): Promise<void> {
	const token = ++currentToken;
	const detailCached = detailCache.get(stableId) ?? null;
	const contentCached = contentCache.get(stableId) ?? null;

	selectedMessage.set({
		stableId,
		detail: detailCached,
		content: contentCached,
		loading: !detailCached || !contentCached,
		error: null,
		notFound: false
	});

	// Both requests start at once: the detail is read from the local database and
	// the content is the body, fetched from the mail server when it is not cached,
	// so the header does not wait for the body. The store still takes them in that
	// order, so the body never shows under a header that is not there yet.
	const detailRequest = detailCached ? Promise.resolve(detailCached) : getMessageDetail(stableId);
	const contentRequest = contentCached
		? Promise.resolve(contentCached)
		: getMessageContent(stableId);
	// A failed body is handled below, once the detail has settled; until then this
	// keeps it from being reported as unhandled. When the detail fails as well,
	// the detail's error is the one that counts.
	void contentRequest.catch(() => undefined);

	try {
		const detail = await detailRequest;
		if (!detailCached) touch(detailCache, stableId, detail);
		if (token !== currentToken) return;

		selectedMessage.update((s) => (s && s.stableId === stableId ? { ...s, detail } : s));

		const content = await contentRequest;
		if (!contentCached) touch(contentCache, stableId, content);
		if (token !== currentToken) return;

		selectedMessage.update((s) =>
			s && s.stableId === stableId ? { ...s, content, loading: false } : s
		);
	} catch (err) {
		if (token !== currentToken) return;
		// A 404 means the message no longer exists under this id — typically a
		// "ghost" left in a stale list after the folder was re-synced. Recover
		// gracefully instead of wedging on a raw error: drop it from the cache,
		// reload the list so the stale row disappears, and flag notFound so the
		// detail pane shows a friendly "no longer available" notice.
		if (err instanceof ApiError && err.status === 404) {
			invalidateMessage(stableId);
			void reloadCurrentPage();
			selectedMessage.update((s) =>
				s && s.stableId === stableId
					? { ...s, detail: null, content: null, error: null, notFound: true, loading: false }
					: s
			);
			return;
		}
		const error = toError(err);
		selectedMessage.update((s) =>
			s && s.stableId === stableId ? { ...s, error, loading: false } : s
		);
	}
}

/** Invalidates the cache for a message (e.g. after a flag change reflected in detail). */
export function invalidateMessage(stableId: string): void {
	detailCache.delete(stableId);
	contentCache.delete(stableId);
}

export function patchSelectedMessageDetail(
	stableId: string,
	patch: Partial<MailDetailResponse>
): void {
	const cachedDetail = detailCache.get(stableId);
	if (cachedDetail) {
		touch(detailCache, stableId, {
			...cachedDetail,
			...patch
		});
	}

	selectedMessage.update((state) => {
		if (!state || state.stableId !== stableId || !state.detail) return state;
		const detail = {
			...state.detail,
			...patch
		};
		touch(detailCache, stableId, detail);
		return {
			...state,
			detail
		};
	});
}
