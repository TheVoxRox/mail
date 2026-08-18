import { describe, expect, it } from 'vitest';
import { fieldControlProps, fieldHintId } from './field.js';

describe('fieldHintId', () => {
	it('derives the hint id from the field id', () => {
		expect(fieldHintId('acc-email')).toBe('acc-email-hint');
	});

	it('has no id to give when the field has no `for`', () => {
		expect(fieldHintId(undefined)).toBeUndefined();
	});
});

describe('fieldControlProps', () => {
	it('points the control at the hint', () => {
		expect(fieldControlProps({ for: 'acc-email', hint: 'Use your address.' })).toEqual({
			'aria-describedby': 'acc-email-hint',
			'aria-invalid': undefined
		});
	});

	it('points the control at the error and marks it invalid', () => {
		expect(
			fieldControlProps({
				for: 'acc-imap-host',
				error: 'Fill in the IMAP host.',
				errorId: 'acc-imap-host-error'
			})
		).toEqual({
			'aria-describedby': 'acc-imap-host-error',
			'aria-invalid': 'true'
		});
	});

	it('lists both, hint first — it describes the field, the error only what is wrong now', () => {
		expect(
			fieldControlProps({
				for: 'acc-imap-host',
				hint: 'Usually imap.example.com.',
				error: 'Fill in the IMAP host.',
				errorId: 'acc-imap-host-error'
			})
		).toEqual({
			'aria-describedby': 'acc-imap-host-hint acc-imap-host-error',
			'aria-invalid': 'true'
		});
	});

	it('describes nothing when the field renders neither', () => {
		expect(fieldControlProps({ for: 'contact-name' })).toEqual({
			'aria-describedby': undefined,
			'aria-invalid': undefined
		});
	});

	/*
	 * Both elements render without an id in these cases, so there is nothing to
	 * point at. Naming one anyway would produce an aria-describedby that
	 * resolves to no element — worse than none, because it reads as wired.
	 */
	it('drops a hint the field could not give an id to', () => {
		expect(fieldControlProps({ hint: 'Orphan hint.' })['aria-describedby']).toBeUndefined();
	});

	it('drops an error the caller gave no id to, but still marks the control invalid', () => {
		expect(fieldControlProps({ for: 'acc-port', error: 'Out of range.' })).toEqual({
			'aria-describedby': undefined,
			'aria-invalid': 'true'
		});
	});
});
