import { describe, expect, it } from 'vitest';
import { draftHref, messageHref } from './messageHref.js';

describe('messageHref', () => {
	it('puts the folder and the message in one path segment each', () => {
		// A hierarchy delimiter in the folder name and the characters a stableId
		// carries must not turn into extra segments the route cannot match.
		expect(messageHref(7, '[Gmail]/Sent Mail', 'INBOX:42/1')).toBe(
			'/mail/7/%5BGmail%5D%2FSent%20Mail/INBOX%3A42%2F1'
		);
	});
});

describe('draftHref', () => {
	it('reopens the draft in the composer by its stableId', () => {
		// Only the tail is asserted: `resolve('compose')` is relative to the
		// base, and the base under vitest is not the app's.
		expect(draftHref('Drafts:9/1')).toMatch(/compose\?draft=Drafts%3A9%2F1$/);
	});
});
