import { describe, expect, it } from 'vitest';
import { messagesPageInfo, olderMailOnServerOnly } from './pageInfoAnnouncement.js';
import type { PagedResponse } from '$lib/types.js';

const translate = (key: string, options?: { values: Record<string, string | number> }) =>
	options ? `${key}(${Object.values(options.values).join('|')})` : key;

function page(overrides: Partial<PagedResponse<unknown>>): PagedResponse<unknown> {
	return {
		content: [],
		page: 0,
		size: 50,
		totalPages: 1,
		totalElements: 10,
		first: true,
		last: true,
		...overrides
	};
}

describe('messagesPageInfo', () => {
	it('reads the page, the page count and the folder total', () => {
		expect(messagesPageInfo(translate, page({ page: 1, totalPages: 3, totalElements: 120 }))).toBe(
			'messages.pageInfo(2|3|messages.totalCount(120))'
		);
	});

	// B1-9: the pager of a folder past the local window ends where the mirror
	// does, while the count keeps the folder's size.
	it('says that older mail is on the server only on the last page of a cut listing', () => {
		const lastOfCut = page({
			page: 199,
			totalPages: 200,
			totalElements: 2_000_000,
			olderOnServer: true
		});

		expect(messagesPageInfo(translate, lastOfCut)).toBe(
			'messages.pageInfo(200|200|messages.totalCount(2000000)). messages.olderOnServer'
		);
	});

	it('does not say it on an earlier page of a cut listing', () => {
		const earlier = page({
			page: 3,
			totalPages: 200,
			totalElements: 2_000_000,
			first: false,
			last: false,
			olderOnServer: true
		});

		expect(olderMailOnServerOnly(earlier)).toBe(false);
		expect(messagesPageInfo(translate, earlier)).not.toContain('messages.olderOnServer');
	});

	it('does not say it where the listing is whole', () => {
		expect(olderMailOnServerOnly(page({ olderOnServer: false }))).toBe(false);
		expect(olderMailOnServerOnly(page({}))).toBe(false);
	});
});
