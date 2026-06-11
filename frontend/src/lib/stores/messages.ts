/**
 * Store for the message list in the currently selected folder. Paginated
 * state built on `createPagedListStore`; local mutations for optimistic
 * content changes.
 */
import { listMessages } from '$lib/api/mailRead.js';
import { getClientConfigSnapshot } from '$lib/stores/clientConfig.js';
import { createPagedListStore, type PagedListState } from './createPagedListStore.js';
import type { MailSummaryResponse } from '$lib/types.js';

interface MessagesContext {
	accountId: number;
	folderName: string;
	page: number;
	size: number;
}

export type MessagesState = PagedListState<MessagesContext, MailSummaryResponse>;

const store = createPagedListStore<MessagesContext, MailSummaryResponse>((ctx) =>
	listMessages(ctx.accountId, ctx.folderName, { page: ctx.page, size: ctx.size })
);

export const messagesState = store.state;

/**
 * Loads a page of messages and stores it in `messagesState`. If another
 * `loadPage` with a different context starts before this one finishes, the
 * old result is discarded.
 */
export async function loadPage(
	accountId: number,
	folderName: string,
	page = 0,
	size?: number
): Promise<void> {
	const finalSize = size ?? getClientConfigSnapshot().mailDefaultPageSize;
	await store.load({ accountId, folderName, page, size: finalSize });
}

/** Reloads the current page (e.g. after sync_completed). */
export function reloadCurrentPage(): Promise<void> | void {
	return store.reload();
}

/** Optimistic mutation of the `seen` flag in the current list (callback after PATCH). */
export function markSeenLocally(stableId: string, seen: boolean): void {
	store.mutateReadyPage((page) => ({
		...page,
		content: page.content.map((m) => (m.stableId === stableId ? { ...m, seen } : m))
	}));
}

/** Removes a message from the current list (use after DELETE). */
export function removeMessageLocally(stableId: string): void {
	store.mutateReadyPage((page) => {
		const content = page.content.filter((m) => m.stableId !== stableId);
		if (content.length === page.content.length) return page;
		return { ...page, content, totalElements: Math.max(0, page.totalElements - 1) };
	});
}
