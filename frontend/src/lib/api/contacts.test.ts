import { describe, expect, it, vi } from 'vitest';

const { apiGetMock, apiPostMock, apiPutMock, apiDeleteMock } = vi.hoisted(() => ({
	apiGetMock: vi.fn(),
	apiPostMock: vi.fn(),
	apiPutMock: vi.fn(),
	apiDeleteMock: vi.fn()
}));

vi.mock('./client.js', () => ({
	api: { get: apiGetMock, post: apiPostMock, put: apiPutMock, delete: apiDeleteMock },
	apiRaw: vi.fn()
}));

import { createContact, getContact, getContactCounts, listContacts } from './contacts.js';

/*
 * A backend older than a field answers 200 without the key, and every read site
 * in the UI treats the two contact lists as always-present. #252 is what that
 * costs when it is not caught here: the pre-labels sidecar sent rows with no
 * `labels`, `c.labels.length` threw while rendering and the whole contact list
 * stayed blank. These lock in that the client hands the UI a contact with no
 * labels instead.
 */
describe('contacts API — responses missing a list field', () => {
	function pageOf(content: unknown[]) {
		return {
			content,
			page: 0,
			size: 25,
			totalElements: content.length,
			totalPages: 1,
			first: true,
			last: true
		};
	}

	const legacyRow = {
		id: 1,
		name: 'Jana',
		surname: 'Novak',
		note: 'Projekt',
		createdAt: '2026-01-01T00:00:00',
		updatedAt: '2026-01-02T00:00:00',
		emails: [{ id: 1, email: 'jana@example.com', label: 'WORK', primary: true }]
	};

	it('fills in a missing `labels` on a listed contact and keeps the rest', async () => {
		apiGetMock.mockResolvedValue(pageOf([legacyRow]));

		const result = await listContacts();

		expect(result.content[0].labels).toEqual([]);
		expect(result.content[0].emails).toHaveLength(1);
		expect(result.content[0].name).toBe('Jana');
		expect(result.totalElements).toBe(1);
	});

	it('fills in a missing `emails`', async () => {
		apiGetMock.mockResolvedValue(pageOf([{ ...legacyRow, emails: undefined, labels: [] }]));

		const result = await listContacts();

		expect(result.content[0].emails).toEqual([]);
	});

	it('leaves present lists untouched', async () => {
		const labels = [{ id: 7, name: 'Klienti' }];
		apiGetMock.mockResolvedValue(pageOf([{ ...legacyRow, labels }]));

		const result = await listContacts();

		expect(result.content[0].labels).toEqual(labels);
	});

	it('turns a page without `content` into an empty page', async () => {
		apiGetMock.mockResolvedValue({ ...pageOf([]), content: undefined });

		const result = await listContacts();

		expect(result.content).toEqual([]);
	});

	it('normalizes the single-contact reads and writes too', async () => {
		apiGetMock.mockResolvedValue(legacyRow);
		apiPostMock.mockResolvedValue(legacyRow);

		expect((await getContact(1)).labels).toEqual([]);
		expect((await createContact({ emails: [] })).labels).toEqual([]);
	});

	it('fills in `labels` missing from the sidebar counts', async () => {
		// The same backend answered counts in its pre-labels shape.
		apiGetMock.mockResolvedValue({ total: 1, work: 1, home: 0, other: 0 });

		const counts = await getContactCounts();

		expect(counts.labels).toEqual([]);
		expect(counts.total).toBe(1);
	});
});
