import { describe, expect, it } from 'vitest';
import { mentionsAttachment } from './attachmentReminder.js';

/**
 * The reminder must catch the common cs/en phrasings while never firing on
 * quoted text or signatures — a reply to "posílám fakturu v příloze" without
 * a new attachment is the normal case, not a mistake.
 */
describe('mentionsAttachment', () => {
	it('detects Czech attachment phrasings across inflections', () => {
		expect(mentionsAttachment('Posílám fakturu v příloze.')).toBe(true);
		expect(mentionsAttachment('Přílohu s podklady najdete níže.')).toBe(true);
		expect(mentionsAttachment('Přikládám smlouvu k podpisu.')).toBe(true);
		expect(mentionsAttachment('Přiložil jsem oba dokumenty.')).toBe(true);
		expect(mentionsAttachment('Vše je přiloženo.')).toBe(true);
	});

	it('detects Czech typed without diacritics', () => {
		expect(mentionsAttachment('v priloze posilam fakturu')).toBe(true);
		expect(mentionsAttachment('prikladam smlouvu')).toBe(true);
	});

	it('detects English attachment phrasings', () => {
		expect(mentionsAttachment('Please see the attached invoice.')).toBe(true);
		expect(mentionsAttachment('I attach the report for review.')).toBe(true);
		expect(mentionsAttachment('The attachments contain the full data.')).toBe(true);
		expect(mentionsAttachment('Enclosed you will find the contract.')).toBe(true);
	});

	it('ignores bodies without attachment talk', () => {
		expect(mentionsAttachment('')).toBe(false);
		expect(mentionsAttachment('Sejdeme se zítra v devět.')).toBe(false);
		expect(mentionsAttachment('See you tomorrow at nine.')).toBe(false);
	});

	it('does not confuse "příklad" (example) with "přikládám" once diacritics are stripped', () => {
		expect(mentionsAttachment('Uvedu příklad z praxe.')).toBe(false);
		expect(mentionsAttachment('uvedu priklad z praxe')).toBe(false);
		expect(mentionsAttachment('Například tento případ.')).toBe(false);
	});

	it('ignores mentions inside the quoted original of a reply / forward', () => {
		const backendQuote =
			'Díky, mrknu na to.\n\n---------- Původní zpráva ----------\nOd: jana@example.com\n\nPosílám fakturu v příloze.';
		expect(mentionsAttachment(backendQuote)).toBe(false);

		const shortQuote = 'Díky!\n\n--- Přeposlaná zpráva ---\nEnclosed you will find the contract.';
		expect(mentionsAttachment(shortQuote)).toBe(false);

		const outlookQuote = 'Thanks!\n\n-----Original Message-----\nSee the attached invoice.';
		expect(mentionsAttachment(outlookQuote)).toBe(false);
	});

	it('still fires when the authored text above the quote mentions an attachment', () => {
		const body =
			'Přílohu posílám v tomto e-mailu.\n\n---------- Původní zpráva ----------\nText původní zprávy.';
		expect(mentionsAttachment(body)).toBe(true);
	});

	it('ignores mentions inside the signature block', () => {
		const body =
			'Ahoj,\npodklady dodám zítra.\n\n-- \nJan Novák\nTato zpráva ani její přílohy nejsou nabídkou.';
		expect(mentionsAttachment(body)).toBe(false);
	});

	it('does not treat a bare dash rule as a quote boundary', () => {
		expect(mentionsAttachment('Úvod\n---\nPosílám fakturu v příloze.')).toBe(true);
	});
});
