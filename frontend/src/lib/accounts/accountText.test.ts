import { describe, expect, it } from 'vitest';
import { stripAccountText } from './accountText.js';

describe('stripAccountText', () => {
	it('strips the single-line fields, no-break spaces included', () => {
		expect(
			stripAccountText({
				accountName: ' Work ',
				email: 'jan@example.com ',
				displayName: '\u00a0Jan Novak',
				username: '\tjan@example.com',
				signature: '',
				imapHost: ' imap.example.com',
				smtpHost: 'smtp.example.com\u202f'
			})
		).toEqual({
			accountName: 'Work',
			email: 'jan@example.com',
			displayName: 'Jan Novak',
			username: 'jan@example.com',
			signature: '',
			imapHost: 'imap.example.com',
			smtpHost: 'smtp.example.com'
		});
	});

	it('keeps the indentation of a signature and drops its blank lines at the ends', () => {
		const stripped = stripAccountText({
			accountName: 'Work',
			email: 'jan@example.com',
			displayName: '',
			username: 'jan',
			signature: '\n   Jan Novak\n   VoxRox  \n\n',
			imapHost: '',
			smtpHost: ''
		});

		expect(stripped.signature).toBe('   Jan Novak\n   VoxRox');
	});

	it('leaves a signature of whitespace alone as none', () => {
		const stripped = stripAccountText({
			accountName: 'Work',
			email: 'jan@example.com',
			displayName: '  ',
			username: 'jan',
			signature: ' \n\t\n ',
			imapHost: '',
			smtpHost: ''
		});

		expect(stripped.displayName).toBe('');
		expect(stripped.signature).toBe('');
	});
});
