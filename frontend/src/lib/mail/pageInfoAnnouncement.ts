/**
 * Screen-reader summary of a loaded message-list page ("Page 1 of 3,
 * 25 messages"). Shared by the inbox pagination, the folder-switch
 * announcement in the mail layout and the search results page so all three
 * read the same.
 */
import type { PagedResponse } from '$lib/types.js';

type Translate = (key: string, options?: { values: Record<string, string | number> }) => string;

/**
 * Whether this is the last page of a folder that goes on past what can be
 * browsed. The count still says how much mail the folder holds, so without the
 * note the last page would read as the folder's oldest mail.
 */
export function olderMailOnServerOnly(page: PagedResponse<unknown>): boolean {
	return page.last && page.olderOnServer === true;
}

export function messagesPageInfo(translate: Translate, page: PagedResponse<unknown>): string {
	const info = translate('messages.pageInfo', {
		values: {
			current: page.page + 1,
			total: Math.max(1, page.totalPages),
			totalCount: translate('messages.totalCount', { values: { count: page.totalElements } })
		}
	});
	return olderMailOnServerOnly(page) ? `${info}. ${translate('messages.olderOnServer')}` : info;
}
