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
vi.mock('#lib/api/contacts.js', () => ({ bulkCreateContacts: vi.fn() }));
vi.mock('#lib/api/contactLabels.js', () => ({
	listContactLabels: vi.fn(() => Promise.resolve([])),
	createContactLabel: vi.fn()
}));
vi.mock('#lib/api/errors.js', () => ({ toErrorMessage: (err: unknown) => String(err) }));
vi.mock('#lib/stores/contactCounts.js', () => ({
	refreshContactCounts: vi.fn(() => Promise.resolve())
}));
vi.mock('#lib/stores/toasts.js', () => ({ pushToast: vi.fn() }));

import { ApiError } from '#lib/api/client.js';
import { bulkCreateContacts } from '#lib/api/contacts.js';
import { pushToast } from '#lib/stores/toasts.js';
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
			values: { created: 100, duplicates: 0, failed: 0, invalid: 0 }
		});
	});

	it('splits a larger file into batches of 100 and sums the outcome', async () => {
		expect(await importVCardFiles([vcardFile(250)], t)).toBe(true);

		expect(batchSizes()).toEqual([100, 100, 50]);
		expect(t).toHaveBeenCalledWith('contacts.vcardImportDone', {
			values: { created: 250, duplicates: 0, failed: 0, invalid: 0 }
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
			values: { created: 150, duplicates: 0, failed: 100, invalid: 0 }
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
			values: { created: 1, duplicates: 0, failed: 0, invalid: 0, skipped: 2 }
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

/** A batch answer built from per-card outcomes, the way the backend counts them. */
function outcome(...codes: Array<'CREATED' | string>) {
	const results = codes.map((code, index) =>
		code === 'CREATED'
			? { index, status: 'CREATED' as const }
			: { index, status: 'FAILED' as const, errorCode: code, errorMessage: code }
	);
	const created = results.filter((item) => item.status === 'CREATED').length;
	return { total: codes.length, created, failed: codes.length - created, results };
}

describe('importVCardFiles — cards already in the address book', () => {
	/*
	 * Re-importing a file is the ordinary way to meet these. The backend counts
	 * them among the failures, and so did the report: an error-toned "Selhalo"
	 * with no reason, read out assertively, for an import that lost nothing.
	 */
	it('counts them apart from the failures, and the toast is not an error', async () => {
		vi.mocked(bulkCreateContacts).mockResolvedValueOnce(
			outcome('CREATED', 'CONTACT_DUPLICATE', 'CONTACT_DUPLICATE')
		);

		expect(await importVCardFiles([vcardFile(3)], t)).toBe(true);

		expect(t).toHaveBeenCalledWith('contacts.vcardImportDone', {
			values: { created: 1, duplicates: 2, failed: 0, invalid: 0 }
		});
		expect(vi.mocked(pushToast)).toHaveBeenCalledWith(expect.any(String), { tone: 'info' });
	});

	it('reports a file that is all in the address book without an error', async () => {
		vi.mocked(bulkCreateContacts).mockResolvedValueOnce(
			outcome('CONTACT_DUPLICATE', 'CONTACT_DUPLICATE')
		);

		await importVCardFiles([vcardFile(2)], t);

		expect(t).toHaveBeenCalledWith('contacts.vcardImportDone', {
			values: { created: 0, duplicates: 2, failed: 0, invalid: 0 }
		});
		expect(vi.mocked(pushToast)).not.toHaveBeenCalledWith(expect.any(String), { tone: 'error' });
	});

	it('keeps the error tone for a real failure beside them', async () => {
		vi.mocked(bulkCreateContacts).mockResolvedValueOnce(
			outcome('CREATED', 'CONTACT_DUPLICATE', 'VALIDATION_ERROR')
		);

		await importVCardFiles([vcardFile(3)], t);

		expect(t).toHaveBeenCalledWith('contacts.vcardImportDone', {
			values: { created: 1, duplicates: 1, failed: 1, invalid: 0 }
		});
		expect(vi.mocked(pushToast)).toHaveBeenCalledWith(expect.any(String), { tone: 'error' });
	});

	it('sums them over the batches', async () => {
		vi.mocked(bulkCreateContacts)
			.mockResolvedValueOnce(outcome(...Array<string>(100).fill('CONTACT_DUPLICATE')))
			.mockResolvedValueOnce(outcome('CONTACT_DUPLICATE', 'CREATED'));

		await importVCardFiles([vcardFile(102)], t);

		expect(t).toHaveBeenCalledWith('contacts.vcardImportDone', {
			values: { created: 1, duplicates: 101, failed: 0, invalid: 0 }
		});
	});

	it('carries them into the message that also reports skipped cards', async () => {
		vi.mocked(bulkCreateContacts).mockResolvedValueOnce(outcome('CONTACT_DUPLICATE'));

		await importVCardFiles([vcardFile(1, 1)], t);

		expect(t).toHaveBeenCalledWith('contacts.vcardImportDoneSkipped', {
			values: { created: 0, duplicates: 1, failed: 0, invalid: 0, skipped: 1 }
		});
	});
});

/** A .vcf holding one card per body, in order. */
function vcardOf(...bodies: string[]): File {
	return new File([bodies.map(card).join('')], 'contacts.vcf', { type: 'text/vcard' });
}

/** The answer the bulk endpoint gives when a field of one contact fails validation. */
function refusedAsInvalid(detail: string) {
	return new ApiError(400, 'Bad Request', null, {
		detail,
		errorCode: 'VALIDATION_ERROR'
	} as never);
}

describe('importVCardFiles — an address the backend would refuse', () => {
	/*
	 * One such address used to cost its whole batch: the bulk endpoint refuses
	 * the request over a single invalid field, so a Google export with one odd
	 * address imported none of the hundred contacts around it.
	 */
	beforeEach(() => {
		vi.mocked(bulkCreateContacts).mockImplementation((body) =>
			Promise.resolve(allCreated(body.contacts.length))
		);
	});

	it('imports the contact without it, and says how many it left out', async () => {
		expect(
			await importVCardFiles(
				[
					vcardOf(
						'FN:Jan\r\nEMAIL:jan@moje_firma.cz\r\nEMAIL:jan@x.cz',
						'FN:Jana\r\nEMAIL:jana@x.cz'
					)
				],
				t
			)
		).toBe(true);

		const [[sent]] = vi.mocked(bulkCreateContacts).mock.calls;
		expect(sent.contacts.map((contact) => contact.emails.map((email) => email.email))).toEqual([
			['jan@x.cz'],
			['jana@x.cz']
		]);
		expect(t).toHaveBeenCalledWith('contacts.vcardImportDone', {
			values: { created: 2, duplicates: 0, failed: 0, invalid: 1 }
		});
		// Nothing went wrong that the user has to act on.
		expect(vi.mocked(pushToast)).toHaveBeenCalledWith(expect.any(String), { tone: 'info' });
	});
});

describe('importVCardFiles — a batch refused over one contact', () => {
	/*
	 * The parser cannot know every bound the backend validates (a note past 1000
	 * characters, an eleventh address), and the bulk endpoint refuses the whole
	 * request over one of them. Such a batch goes again one contact at a time,
	 * so only the offending card fails.
	 */
	// clearMocks leaves queued once-answers in place; a test that fails before
	// using them up must not hand them to the next one.
	beforeEach(() => {
		vi.mocked(bulkCreateContacts).mockReset();
	});

	it('sends the batch again one contact per request, and only the bad one fails', async () => {
		vi.mocked(bulkCreateContacts)
			.mockRejectedValueOnce(refusedAsInvalid('Note too long'))
			.mockResolvedValueOnce(allCreated(1))
			.mockRejectedValueOnce(refusedAsInvalid('Note too long'))
			.mockResolvedValueOnce(allCreated(1));

		expect(await importVCardFiles([vcardFile(3)], t)).toBe(true);

		expect(batchSizes()).toEqual([3, 1, 1, 1]);
		expect(t).toHaveBeenCalledWith('contacts.vcardImportDone', {
			values: { created: 2, duplicates: 0, failed: 1, invalid: 0 }
		});
		// The reason the one card failed is said, not only counted.
		expect(vi.mocked(pushToast)).toHaveBeenCalledWith('ApiError: Note too long', {
			tone: 'error'
		});
	});

	it('does not resend a batch refused for any other reason', async () => {
		vi.mocked(bulkCreateContacts).mockRejectedValueOnce(
			new ApiError(503, 'Service Unavailable', null)
		);

		expect(await importVCardFiles([vcardFile(3)], t)).toBe(false);

		expect(batchSizes()).toEqual([3]);
	});
});
