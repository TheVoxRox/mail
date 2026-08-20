import { beforeEach, describe, expect, it, vi } from 'vitest';

/*
 * The importer is the one path that turns a whole file into contacts, and the
 * two ways it used to lose data were size and silence: the bulk endpoint
 * refuses more than 100 contacts per request, so an ordinary Google or Outlook
 * export failed validation as one unit and imported nothing, and a card with no
 * address was dropped without ever being counted, so a file half full of them
 * still reported a clean success. Every collaborator is stubbed — what these
 * tests pin is the orchestration, not the parser (which has its own suite) or
 * the HTTP layer.
 */
vi.mock('$lib/api/contacts.js', () => ({ bulkCreateContacts: vi.fn() }));
vi.mock('$lib/api/contactLabels.js', () => ({
	listContactLabels: vi.fn(() => Promise.resolve([])),
	createContactLabel: vi.fn()
}));
vi.mock('$lib/api/errors.js', () => ({ toErrorMessage: (err: unknown) => String(err) }));
vi.mock('$lib/stores/contactCounts.js', () => ({
	refreshContactCounts: vi.fn(() => Promise.resolve())
}));
vi.mock('$lib/stores/toasts.js', () => ({ pushToast: vi.fn() }));

import { bulkCreateContacts } from '$lib/api/contacts.js';
import { pushToast } from '$lib/stores/toasts.js';
import { importVCardFiles } from './importVCards.js';

/** Mirrors svelte-i18n's `$_`, and records what the outcome message was told. */
const t = vi.fn((id: string, options?: { values?: Record<string, string | number> }) =>
	options?.values ? `${id}:${JSON.stringify(options.values)}` : id
);

function card(body: string): string {
	return `BEGIN:VCARD\r\nVERSION:4.0\r\n${body}\r\nEND:VCARD\r\n`;
}

/** A .vcf holding `count` importable cards, plus `withoutEmail` unusable ones. */
function vcardFile(count: number, withoutEmail = 0): File {
	let text = '';
	for (let i = 0; i < count; i++) text += card(`FN:Person ${i}\r\nEMAIL:p${i}@example.com`);
	for (let i = 0; i < withoutEmail; i++) text += card(`FN:Addressless ${i}`);
	return new File([text], 'contacts.vcf', { type: 'text/vcard' });
}

/** A response for a batch in which every contact was created. */
function allCreated(count: number) {
	return { total: count, created: count, failed: 0, results: [] };
}

/** Sizes of the batches the importer sent, in order. */
function batchSizes(): number[] {
	return vi.mocked(bulkCreateContacts).mock.calls.map(([body]) => body.contacts.length);
}

describe('importVCardFiles — batching', () => {
	beforeEach(() => {
		vi.mocked(bulkCreateContacts).mockImplementation((body) =>
			Promise.resolve(allCreated(body.contacts.length))
		);
	});

	it('sends one batch when the file fits under the endpoint ceiling', async () => {
		expect(await importVCardFiles([vcardFile(100)], t)).toBe(true);

		expect(batchSizes()).toEqual([100]);
		expect(t).toHaveBeenCalledWith('contacts.vcardImportDone', {
			values: { created: 100, failed: 0 }
		});
	});

	it('splits a larger file into batches of 100 and sums the outcome', async () => {
		expect(await importVCardFiles([vcardFile(250)], t)).toBe(true);

		expect(batchSizes()).toEqual([100, 100, 50]);
		expect(t).toHaveBeenCalledWith('contacts.vcardImportDone', {
			values: { created: 250, failed: 0 }
		});
	});

	it('counts the cards of several files into the same batches', async () => {
		await importVCardFiles([vcardFile(60), vcardFile(60)], t);

		expect(batchSizes()).toEqual([100, 20]);
	});

	it('keeps the batches that landed when one request is rejected', async () => {
		vi.mocked(bulkCreateContacts)
			.mockResolvedValueOnce(allCreated(100))
			.mockRejectedValueOnce(new Error('offline'))
			.mockResolvedValueOnce(allCreated(50));

		// True, because 150 contacts did reach the address book — the caller has
		// to reload its list.
		expect(await importVCardFiles([vcardFile(250)], t)).toBe(true);
		expect(t).toHaveBeenCalledWith('contacts.vcardImportDone', {
			values: { created: 150, failed: 100 }
		});
		// The counts alone would not say why 100 are missing.
		expect(vi.mocked(pushToast)).toHaveBeenCalledWith('Error: offline', { tone: 'error' });
	});

	it('reports the plain failure when no batch got through', async () => {
		vi.mocked(bulkCreateContacts).mockRejectedValue(new Error('offline'));

		expect(await importVCardFiles([vcardFile(150)], t)).toBe(false);

		expect(vi.mocked(pushToast).mock.calls).toEqual([['Error: offline', { tone: 'error' }]]);
		expect(t).not.toHaveBeenCalledWith('contacts.vcardImportDone', expect.anything());
	});
});

describe('importVCardFiles — cards without an address', () => {
	beforeEach(() => {
		vi.mocked(bulkCreateContacts).mockImplementation((body) =>
			Promise.resolve(allCreated(body.contacts.length))
		);
	});

	it('reports how many the file lost instead of claiming a clean success', async () => {
		expect(await importVCardFiles([vcardFile(1, 2)], t)).toBe(true);

		expect(batchSizes()).toEqual([1]);
		expect(t).toHaveBeenCalledWith('contacts.vcardImportDoneSkipped', {
			values: { created: 1, failed: 0, skipped: 2 }
		});
	});

	it('stays on the plain message when nothing was skipped', async () => {
		await importVCardFiles([vcardFile(2)], t);

		expect(t).not.toHaveBeenCalledWith('contacts.vcardImportDoneSkipped', expect.anything());
	});

	it('says the file was empty when no card had an address', async () => {
		expect(await importVCardFiles([vcardFile(0, 3)], t)).toBe(false);

		expect(vi.mocked(bulkCreateContacts)).not.toHaveBeenCalled();
		expect(t).toHaveBeenCalledWith('contacts.vcardImportEmpty');
	});
});
