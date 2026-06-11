import { describe, expect, it } from 'vitest';
import {
	FIELD_INPUT_IDS,
	validateCustomServer,
	type CustomServerInput
} from './accountFormValidation.js';

const valid: CustomServerInput = {
	imapHost: 'imap.example.com',
	imapPort: 993,
	smtpHost: 'smtp.example.com',
	smtpPort: 465
};

describe('validateCustomServer', () => {
	it('returns null for a fully valid input', () => {
		expect(validateCustomServer(valid)).toBeNull();
	});

	it('flags empty imapHost first', () => {
		const result = validateCustomServer({ ...valid, imapHost: '   ' });
		expect(result).toEqual({
			field: 'imapHost',
			messageKey: 'accounts.form.errorImapHostRequired'
		});
	});

	it('flags host longer than 255 chars', () => {
		const tooLong = 'a'.repeat(256);
		expect(validateCustomServer({ ...valid, imapHost: tooLong })).toEqual({
			field: 'imapHost',
			messageKey: 'accounts.form.errorHostTooLong'
		});
		expect(validateCustomServer({ ...valid, smtpHost: tooLong })).toEqual({
			field: 'smtpHost',
			messageKey: 'accounts.form.errorHostTooLong'
		});
	});

	it('flags null/out-of-range/non-integer imapPort', () => {
		for (const port of [null, 0, 65536, 1.5, NaN]) {
			expect(validateCustomServer({ ...valid, imapPort: port })?.field).toBe('imapPort');
		}
	});

	it('flags null/out-of-range smtpPort', () => {
		for (const port of [null, 0, 65536, 1.5, NaN]) {
			expect(validateCustomServer({ ...valid, smtpPort: port })?.field).toBe('smtpPort');
		}
	});

	it('flags empty smtpHost when IMAP is fine', () => {
		expect(validateCustomServer({ ...valid, smtpHost: '' })).toEqual({
			field: 'smtpHost',
			messageKey: 'accounts.form.errorSmtpHostRequired'
		});
	});

	it('order: imapHost > imapPort > smtpHost > smtpPort (first error wins)', () => {
		const broken: CustomServerInput = {
			imapHost: '',
			imapPort: -1,
			smtpHost: '',
			smtpPort: -1
		};
		expect(validateCustomServer(broken)?.field).toBe('imapHost');
	});

	it('accepts boundary ports 1 and 65535', () => {
		expect(validateCustomServer({ ...valid, imapPort: 1, smtpPort: 65535 })).toBeNull();
	});
});

describe('FIELD_INPUT_IDS', () => {
	it('maps each custom field key to a stable input id', () => {
		expect(FIELD_INPUT_IDS).toEqual({
			imapHost: 'acc-imap-host',
			imapPort: 'acc-imap-port',
			smtpHost: 'acc-smtp-host',
			smtpPort: 'acc-smtp-port'
		});
	});
});
