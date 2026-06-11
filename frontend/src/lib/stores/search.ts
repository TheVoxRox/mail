/**
 * Store for full-text search results (`GET /messages/account/{id}/search`).
 * Parallel model to `messages.ts` built on the shared `createPagedListStore`.
 */
import { searchMessages } from '$lib/api/mailRead.js';
import { getClientConfigSnapshot } from '$lib/stores/clientConfig.js';
import { createPagedListStore } from './createPagedListStore.js';
import type { MailSummaryResponse } from '$lib/types.js';

interface SearchContext {
	accountId: number;
	query: string;
	page: number;
	size: number;
}

const store = createPagedListStore<SearchContext, MailSummaryResponse>((ctx) =>
	searchMessages(ctx.accountId, ctx.query, { page: ctx.page, size: ctx.size })
);

export const searchState = store.state;

export async function runSearch(
	accountId: number,
	query: string,
	page = 0,
	size?: number
): Promise<void> {
	const finalSize = size ?? getClientConfigSnapshot().mailDefaultPageSize;
	await store.load({ accountId, query, page, size: finalSize });
}

/** Re-runs the current search (e.g. after a row action mutates a result). */
export function reloadSearch(): Promise<void> | void {
	return store.reload();
}
