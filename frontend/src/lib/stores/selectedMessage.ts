/**
 * Store for the currently selected message – holds the detail (headers +
 * attachments) and the content (HTML/plain) with lazy fetch. A simple LRU
 * cache for detail/content enables fast switching between messages.
 */

import { writable } from 'svelte/store';
import { getMessageContent, getMessageDetail } from '$lib/api/mailRead.js';
import { toError } from '$lib/api/errors.js';
import type { MailContentResponse, MailDetailResponse } from '$lib/types.js';

const CACHE_LIMIT = 30;

export interface SelectedMessage {
	stableId: string;
	detail: MailDetailResponse | null;
	content: MailContentResponse | null;
	loading: boolean;
	error: Error | null;
}

export const selectedMessage = writable<SelectedMessage | null>(null);
export const restoreListFocusStableId = writable<string | null>(null);

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

export function requestListFocusRestore(stableId: string | null): void {
	restoreListFocusStableId.set(stableId);
}

export function clearListFocusRestore(): void {
	restoreListFocusStableId.set(null);
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
		error: null
	});

	try {
		const detail = detailCached ?? (await getMessageDetail(stableId));
		if (!detailCached) touch(detailCache, stableId, detail);
		if (token !== currentToken) return;

		selectedMessage.update((s) => (s && s.stableId === stableId ? { ...s, detail } : s));

		const content = contentCached ?? (await getMessageContent(stableId));
		if (!contentCached) touch(contentCache, stableId, content);
		if (token !== currentToken) return;

		selectedMessage.update((s) =>
			s && s.stableId === stableId ? { ...s, content, loading: false } : s
		);
	} catch (err) {
		if (token !== currentToken) return;
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
