import { describe, expect, it } from 'vitest';
import { accountOptionLabel } from './accountLabel.js';

describe('accountOptionLabel', () => {
	it('keeps a clean domain-based name and appends the e-mail after a colon', () => {
		expect(accountOptionLabel('Post', 'info@post.cz')).toBe('Post: info@post.cz');
		expect(accountOptionLabel('Outlook', 'info@outlook.com')).toBe('Outlook: info@outlook.com');
	});

	it('shows only the e-mail when the name merely repeats it', () => {
		// Name equals the full address.
		expect(accountOptionLabel('info@post.cz', 'info@post.cz')).toBe('info@post.cz');
		// Name equals the local part (case-insensitive).
		expect(accountOptionLabel('INFO', 'info@post.cz')).toBe('info@post.cz');
	});

	it('normalises the legacy "<Provider>: <email>" name so the address is not doubled', () => {
		expect(accountOptionLabel('Outlook: user@outlook.com', 'user@outlook.com')).toBe(
			'Outlook: user@outlook.com'
		);
		expect(accountOptionLabel('Gmail: someone@gmail.com', 'someone@gmail.com')).toBe(
			'Gmail: someone@gmail.com'
		);
	});

	it('strips an embedded e-mail and dangling separators from a free-text name', () => {
		expect(accountOptionLabel('Práce: info@post.cz', 'info@post.cz')).toBe('Práce: info@post.cz');
		expect(accountOptionLabel('info@post.cz - Práce', 'info@post.cz')).toBe('Práce: info@post.cz');
	});

	it('falls back to the e-mail when the name is blank or only separators', () => {
		expect(accountOptionLabel('', 'info@post.cz')).toBe('info@post.cz');
		expect(accountOptionLabel('   :  ', 'info@post.cz')).toBe('info@post.cz');
	});
});
