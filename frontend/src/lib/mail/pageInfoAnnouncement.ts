/**
 * Screen-reader summary of a loaded message-list page ("Page 1 of 3,
 * 25 messages"). Shared by the inbox pagination, the folder-switch
 * announcement in the mail layout and the search results page so all three
 * read the same.
 */
import type { PagedResponse } from '$lib/types.js';

type Translate = (key: string, options?: { values: Record<string, string | number> }) => string;

export function messagesPageInfo(translate: Translate, page: PagedResponse<unknown>): string {
	return translate('messages.pageInfo', {
		values: {
			current: page.page + 1,
			total: Math.max(1, page.totalPages),
			totalCount: translate('messages.totalCount', { values: { count: page.totalElements } })
		}
	});
}
