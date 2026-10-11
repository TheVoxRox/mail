import { describe, expect, it } from 'vitest';
import {
	FIELD_INPUT_IDS,
	validateAccountForm,
	validateCustomServer,
	type AccountFormInput,
	type CustomServerInput
} from './accountFormValidation.js';

const valid: CustomServerInput = {
	imapHost: 'imap.example.com',
	imapPort: 993,
	smtpHost: 'smtp.example.com',
	smtpPort: 465
};

const fields = (input: CustomServerInput) => validateCustomServer(input).map((e) => e.field);

describe('validateCustomServer', () => {
	it('returns nothing for a fully valid input', () => {
		expect(validateCustomServer(valid)).toEqual([]);
	});

	it('flags an empty imapHost', () => {
		expect(validateCustomServer({ ...valid, imapHost: '   ' })).toEqual([
			{ field: 'imapHost', messageKey: 'accounts.form.errorImapHostRequired' }
		]);
	});

	it('flags a host longer than 255 chars', () => {
		const tooLong = 'a'.repeat(256);
		expect(validateCustomServer({ ...valid, imapHost: tooLong })).toEqual([
			{ field: 'imapHost', messageKey: 'accounts.form.errorHostTooLong' }
		]);
		expect(validateCustomServer({ ...valid, smtpHost: tooLong })).toEqual([
			{ field: 'smtpHost', messageKey: 'accounts.form.errorHostTooLong' }
		]);
	});

	it('flags a null, out-of-range or non-integer imapPort', () => {
		for (const port of [null, 0, 65536, 1.5, NaN]) {
			expect(fields({ ...valid, imapPort: port })).toEqual(['imapPort']);
		}
	});

	it('flags a null, out-of-range or non-integer smtpPort', () => {
		for (const port of [null, 0, 65536, 1.5, NaN]) {
			expect(fields({ ...valid, smtpPort: port })).toEqual(['smtpPort']);
		}
	});

	it('flags an empty smtpHost when IMAP is fine', () => {
		expect(validateCustomServer({ ...valid, smtpHost: '' })).toEqual([
			{ field: 'smtpHost', messageKey: 'accounts.form.errorSmtpHostRequired' }
		]);
	});

	it('reports every broken field, in the order they appear', () => {
		const broken: CustomServerInput = { imapHost: '', imapPort: -1, smtpHost: '', smtpPort: -1 };
		expect(fields(broken)).toEqual(['imapHost', 'imapPort', 'smtpHost', 'smtpPort']);
	});

	it('accepts boundary ports 1 and 65535', () => {
		expect(validateCustomServer({ ...valid, imapPort: 1, smtpPort: 65535 })).toEqual([]);
	});
});

describe('validateAccountForm', () => {
	const filled: AccountFormInput = {
		...valid,
		purpose: 'save',
		compact: false,
		passwordRequired: true,
		serverMode: 'provider',
		providerId: 1,
		email: 'jan@example.com',
		accountName: 'Example',
		username: 'jan@example.com',
		password: 'secret'
	};
	const formFields = (input: AccountFormInput) => validateAccountForm(input).map((e) => e.field);

	it('returns nothing for a filled form', () => {
		expect(validateAccountForm(filled)).toEqual([]);
	});

	it('reports every empty field of a new account, in the order the form shows them', () => {
		const empty = {
			...filled,
			email: '',
			accountName: ' ',
			providerId: null,
			username: '',
			password: ''
		};
		expect(validateAccountForm(empty)).toEqual([
			{ field: 'email', messageKey: 'accounts.form.errorEmailRequired' },
			{ field: 'accountName', messageKey: 'accounts.form.errorAccountNameRequired' },
			{ field: 'provider', messageKey: 'accounts.form.errorSelectProvider' },
			{ field: 'username', messageKey: 'accounts.form.errorUsernameRequired' },
			{ field: 'password', messageKey: 'accounts.form.errorPasswordRequired' }
		]);
	});

	it('says how an address should look when it is not one', () => {
		expect(validateAccountForm({ ...filled, email: 'jan.example.com' })).toEqual([
			{ field: 'email', messageKey: 'accounts.form.errorEmailInvalid' }
		]);
	});

	it('puts the custom server fields where the provider would be', () => {
		const custom = { ...filled, serverMode: 'custom' as const, accountName: '', imapHost: '' };
		expect(formFields({ ...custom, username: '' })).toEqual([
			'accountName',
			'imapHost',
			'username'
		]);
	});

	it('lets an edit keep the stored password', () => {
		expect(validateAccountForm({ ...filled, passwordRequired: false, password: '' })).toEqual([]);
	});

	it('asks a connection test for what it sends, not for the account name', () => {
		const test = { ...filled, purpose: 'test' as const, accountName: '', password: '' };
		expect(validateAccountForm(test)).toEqual([
			{ field: 'password', messageKey: 'accounts.form.errorPasswordRequiredForTest' }
		]);
	});

	it('checks only the password and the server in the compact wizard form', () => {
		const compact = { ...filled, compact: true, email: '', accountName: '', username: '' };
		expect(formFields({ ...compact, password: '' })).toEqual(['password']);
		expect(formFields({ ...compact, providerId: null })).toEqual(['provider']);
	});
});

describe('FIELD_INPUT_IDS', () => {
	it('maps each field key to a stable input id', () => {
		expect(FIELD_INPUT_IDS).toEqual({
			email: 'acc-email',
			accountName: 'acc-accountName',
			provider: 'acc-provider',
			imapHost: 'acc-imap-host',
			imapPort: 'acc-imap-port',
			smtpHost: 'acc-smtp-host',
			smtpPort: 'acc-smtp-port',
			username: 'acc-username',
			password: 'acc-password'
		});
	});
});
