import { beforeEach, describe, expect, it, vi } from 'vitest';

// The search behind the lookup is substring-based, so the whole point of the
// module is what it does with the rows that came back — stub the call and drive
// those rows directly.
vi.mock('$lib/api/contacts.js', () => ({ listContacts: vi.fn() }));

import { listContacts } from '$lib/api/contacts.js';
import type { ContactResponse, PagedResponse } from '$lib/types.js';
import { findContactByEmail } from './lookup.js';

function contact(id: number, ...emails: string[]): ContactResponse {
	return {
		id,
		name: null,
		surname: null,
		note: null,
		createdAt: '2026-01-01T00:00:00',
		updatedAt: '2026-01-01T00:00:00',
		emails: emails.map((email, index) => ({
			id: id * 10 + index,
			email,
			label: null,
			primary: index === 0
		})),
		labels: []
	};
}

function page(content: ContactResponse[]): PagedResponse<ContactResponse> {
	return {
		content,
		page: 0,
		size: 10,
		totalElements: content.length,
		totalPages: 1,
		first: true,
		last: true
	};
}

describe('findContactByEmail', () => {
	beforeEach(() => vi.clearAllMocks());

	it('returns the contact whose address matches exactly', () => {
		vi.mocked(listContacts).mockResolvedValue(page([contact(1, 'jana@example.com')]));

		return expect(findContactByEmail('jana@example.com')).resolves.toMatchObject({ id: 1 });
	});

	it('matches a secondary address too — a contact is one person, not one address', () => {
		vi.mocked(listContacts).mockResolvedValue(
			page([contact(1, 'jana@example.com', 'jana.home@example.com')])
		);

		return expect(findContactByEmail('jana.home@example.com')).resolves.toMatchObject({ id: 1 });
	});

	it('ignores a row that merely contains the address', () => {
		// The substring search answers this for "jana@example.com"; taking the
		// first row would call a different person a match.
		vi.mocked(listContacts).mockResolvedValue(page([contact(2, 'jana@example.com.invalid')]));

		return expect(findContactByEmail('jana@example.com')).resolves.toBeNull();
	});

	it('compares case-insensitively, the way addresses are', () => {
		vi.mocked(listContacts).mockResolvedValue(page([contact(1, 'Jana@Example.com')]));

		return expect(findContactByEmail('jana@example.com')).resolves.toMatchObject({ id: 1 });
	});

	it('does not query at all for a blank address', async () => {
		await expect(findContactByEmail('  ')).resolves.toBeNull();

		expect(vi.mocked(listContacts)).not.toHaveBeenCalled();
	});
});
