import { describe, expect, it } from 'vitest';
import { accountOptionLabel, accountShortLabel } from './accountLabel.js';

describe('accountShortLabel', () => {
	const work = { id: 1, accountName: 'Work', email: 'jana@example.com' };

	it('names an account by its name alone', () => {
		expect(accountShortLabel(work, [work])).toBe('Work');
	});

	it('falls back to the address when the name only repeats it', () => {
		const bare = { id: 2, accountName: 'jana', email: 'jana@example.com' };
		const blank = { id: 3, accountName: ' : ', email: 'petr@example.com' };

		expect(accountShortLabel(bare, [bare])).toBe('jana@example.com');
		expect(accountShortLabel(blank, [blank])).toBe('petr@example.com');
	});

	it('adds the address when another account carries the same name, in any case', () => {
		// Two Gmail accounts both default to "Gmail" (AccountForm.svelte).
		const jana = { id: 1, accountName: 'Gmail', email: 'jana@gmail.com' };
		const petr = { id: 2, accountName: 'gmail', email: 'petr@gmail.com' };

		expect(accountShortLabel(jana, [jana, petr])).toBe('Gmail: jana@gmail.com');
		expect(accountShortLabel(petr, [jana, petr])).toBe('gmail: petr@gmail.com');
	});

	it('compares the names as the switcher shows them, without an embedded address', () => {
		// A legacy "<Provider>: <email>" name is "Gmail" once its address is gone.
		const legacy = { id: 1, accountName: 'Gmail: jana@gmail.com', email: 'jana@gmail.com' };
		const plain = { id: 2, accountName: 'Gmail', email: 'petr@gmail.com' };

		expect(accountShortLabel(legacy, [legacy, plain])).toBe('Gmail: jana@gmail.com');
	});

	it('does not count the account itself as the one sharing its name', () => {
		expect(accountShortLabel(work, [work, { ...work }])).toBe('Work');
	});
});

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
