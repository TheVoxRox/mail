/**
 * Store for the conversation-grouped view of the selected folder (threading
 * Phase 2). Paginated state built on `createPagedListStore`, mirroring the flat
 * `messages` store — the mail layout swaps between the two on the
 * `messageGrouping` preference.
 */
import { listConversations } from '$lib/api/mailRead.js';
import { getClientConfigSnapshot } from '$lib/stores/clientConfig.js';
import { createPagedListStore } from './createPagedListStore.js';
import type { ConversationSummaryResponse } from '$lib/types.js';

interface ConversationsContext {
	accountId: number;
	folderName: string;
	page: number;
	size: number;
}

const store = createPagedListStore<ConversationsContext, ConversationSummaryResponse>((ctx) =>
	listConversations(ctx.accountId, ctx.folderName, { page: ctx.page, size: ctx.size })
);

export const conversationsState = store.state;

/**
 * Loads a page of conversations and stores it in `conversationsState`. A newer
 * `load` with a different context discards this one's result.
 */
export async function loadConversationsPage(
	accountId: number,
	folderName: string,
	page = 0,
	size?: number
): Promise<void> {
	const finalSize = size ?? getClientConfigSnapshot().mailDefaultPageSize;
	await store.load({ accountId, folderName, page, size: finalSize });
}

/** Reloads the current conversations page (e.g. after `sync_completed`). */
export function reloadCurrentConversationsPage(): Promise<void> | void {
	return store.reload();
}
