import { describe, expect, it } from 'vitest';
import {
	appendSignature,
	autoSignature,
	composeKind,
	insertSignatureAt,
	signatureBlock,
	signatureManagedForKind,
	swapSignature
} from './signature.js';

/**
 * Phase 1 signature insertion: only new messages and mailto links get a
 * signature; replies/forwards/drafts are left untouched. The block format
 * (`\n\n-- \n<sig>`) must stay deterministic so swap-on-account-change can find
 * and replace it verbatim — and never resurrect a signature the user removed.
 */
describe('composeKind', () => {
	const kind = (qs: string) => composeKind(new URLSearchParams(qs));

	it('maps each param to its kind with reply > forward > to > draft priority', () => {
		expect(kind('reply=1')).toBe('reply');
		expect(kind('forward=1')).toBe('forward');
		expect(kind('to=a@b.cz')).toBe('mailto');
		expect(kind('draft=1')).toBe('draft');
		expect(kind('')).toBe('new');
	});

	it('prefers mailto over draft when both are present (matches prefill priority)', () => {
		expect(kind('draft=1&to=a@b.cz')).toBe('mailto');
	});
});

describe('signatureManagedForKind', () => {
	it('manages only new messages and mailto', () => {
		expect(signatureManagedForKind('new')).toBe(true);
		expect(signatureManagedForKind('mailto')).toBe(true);
		expect(signatureManagedForKind('reply')).toBe(false);
		expect(signatureManagedForKind('forward')).toBe(false);
		expect(signatureManagedForKind('draft')).toBe(false);
	});
});

describe('signatureBlock', () => {
	it('builds an RFC 3676 block for a non-empty signature', () => {
		expect(signatureBlock('Jan Novak')).toBe('\n\n-- \nJan Novak');
	});

	it('right-trims trailing whitespace but keeps internal newlines', () => {
		expect(signatureBlock('Jan Novak\nVoxRox  \n')).toBe('\n\n-- \nJan Novak\nVoxRox');
	});

	it('is empty for null, undefined, empty and whitespace-only signatures', () => {
		expect(signatureBlock(null)).toBe('');
		expect(signatureBlock(undefined)).toBe('');
		expect(signatureBlock('')).toBe('');
		expect(signatureBlock('   \n\t')).toBe('');
	});
});

describe('appendSignature', () => {
	it('appends the block to an empty body', () => {
		expect(appendSignature('', 'Jan')).toBe('\n\n-- \nJan');
	});

	it('appends the block after existing body text', () => {
		expect(appendSignature('Hello', 'Jan')).toBe('Hello\n\n-- \nJan');
	});

	it('is a no-op for an empty signature', () => {
		expect(appendSignature('Hello', '')).toBe('Hello');
		expect(appendSignature('Hello', null)).toBe('Hello');
	});
});

describe('autoSignature', () => {
	it('returns the signature when auto-insert is enabled', () => {
		expect(autoSignature({ signature: 'Jan', signatureAutoInsert: true })).toBe('Jan');
	});

	it('returns empty string when auto-insert is disabled', () => {
		expect(autoSignature({ signature: 'Jan', signatureAutoInsert: false })).toBe('');
	});

	it('returns empty string for a null/undefined account or missing signature', () => {
		expect(autoSignature(null)).toBe('');
		expect(autoSignature(undefined)).toBe('');
		expect(autoSignature({ signatureAutoInsert: true })).toBe('');
	});
});

describe('insertSignatureAt', () => {
	it('inserts the block at the caret and returns the caret after it', () => {
		const result = insertSignatureAt('Hello world', 'Jan', 5, 5);
		expect(result.body).toBe('Hello\n\n-- \nJan world');
		expect(result.caret).toBe(5 + '\n\n-- \nJan'.length);
	});

	it('appends the block when the caret is at the end', () => {
		const result = insertSignatureAt('Hello', 'Jan', 5, 5);
		expect(result.body).toBe('Hello\n\n-- \nJan');
		expect(result.caret).toBe('Hello\n\n-- \nJan'.length);
	});

	it('replaces a selection with the block', () => {
		const result = insertSignatureAt('Hello world', 'Jan', 6, 11);
		expect(result.body).toBe('Hello \n\n-- \nJan');
		expect(result.caret).toBe('Hello \n\n-- \nJan'.length);
	});

	it('is a no-op for a blank signature, keeping the caret at the selection end', () => {
		expect(insertSignatureAt('Hello', '', 2, 4)).toEqual({ body: 'Hello', caret: 4 });
		expect(insertSignatureAt('Hello', null, 2, 4)).toEqual({ body: 'Hello', caret: 4 });
	});

	it('clamps out-of-range offsets to the body bounds', () => {
		const result = insertSignatureAt('Hi', 'Jan', -3, 99);
		expect(result.body).toBe('\n\n-- \nJan');
		expect(result.caret).toBe('\n\n-- \nJan'.length);
	});
});

describe('swapSignature', () => {
	it('appends the new signature when the previous account had none', () => {
		const result = swapSignature('Hello', '', 'Jan');
		expect(result.body).toBe('Hello\n\n-- \nJan');
		expect(result.appliedSignature).toBe('Jan');
	});

	it('replaces the previous block when it is still intact at the end', () => {
		const body = appendSignature('Hello', 'Jan');
		const result = swapSignature(body, 'Jan', 'Petr');
		expect(result.body).toBe('Hello\n\n-- \nPetr');
		expect(result.appliedSignature).toBe('Petr');
	});

	it('removes the block when switching to an account without a signature', () => {
		const body = appendSignature('Hello', 'Jan');
		const result = swapSignature(body, 'Jan', '');
		expect(result.body).toBe('Hello');
		expect(result.appliedSignature).toBe('');
	});

	it('leaves the body untouched and stops if the user edited the block', () => {
		const edited = 'Hello\n\n-- \nJan (edited by hand)';
		const result = swapSignature(edited, 'Jan', 'Petr');
		expect(result.body).toBe(edited);
		expect(result.appliedSignature).toBe('Jan');
	});

	it('does not resurrect a signature the user deleted entirely', () => {
		// User removed the whole block; a later account switch must not re-add one.
		const result = swapSignature('Hello', 'Jan', 'Petr');
		expect(result.body).toBe('Hello');
		expect(result.appliedSignature).toBe('Jan');
	});
});
