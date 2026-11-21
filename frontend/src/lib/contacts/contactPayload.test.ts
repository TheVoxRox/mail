import { describe, expect, it } from 'vitest';
import { storedNote, toContactPayload, type ContactFormValues } from './contactPayload.js';

function form(overrides: Partial<ContactFormValues>): ContactFormValues {
	return {
		name: 'Marie',
		surname: 'Novakova',
		note: '',
		emails: [{ email: 'marie@example.com', label: '', primary: true }],
		labelIds: [],
		...overrides
	};
}

describe('toContactPayload', () => {
	it('sends the names without the whitespace around them, a blank one as null', () => {
		const payload = toContactPayload(form({ name: '  Marie \u00a0', surname: '   ' }));

		expect(payload.name).toBe('Marie');
		expect(payload.surname).toBeNull();
	});

	it('sends the note as a block: the indentation of its lines stays, blank ends go', () => {
		expect(toContactPayload(form({ note: '\n  VIP\n  since 2020 \n\n' })).note).toBe(
			'  VIP\n  since 2020'
		);
		expect(toContactPayload(form({ note: ' \n ' })).note).toBeNull();
	});

	it('strips the addresses, leaves out the empty rows and puts the primary first', () => {
		const payload = toContactPayload(
			form({
				emails: [
					{ email: ' first@example.com', label: 'WORK', primary: false },
					{ email: '   ', label: '', primary: false },
					{ email: 'second@example.com\u00a0', label: '', primary: true }
				]
			})
		);

		expect(payload.emails).toEqual([
			{ email: 'second@example.com', label: null },
			{ email: 'first@example.com', label: 'WORK' }
		]);
	});

	it('makes the first remaining address primary when the primary row was left empty', () => {
		const payload = toContactPayload(
			form({
				emails: [
					{ email: '', label: '', primary: true },
					{ email: 'only@example.com', label: 'HOME', primary: false }
				]
			})
		);

		expect(payload.emails).toEqual([{ email: 'only@example.com', label: 'HOME' }]);
	});

	it('passes the labels through', () => {
		expect(toContactPayload(form({ labelIds: [3, 7] })).labelIds).toEqual([3, 7]);
	});
});

describe('storedNote', () => {
	it('is the note as it is stored, so its length is the one the limit applies to', () => {
		expect(storedNote('\n\n  line one\n  line two  \n')).toBe('  line one\n  line two');
		expect(storedNote('   ')).toBe('');
	});
});
