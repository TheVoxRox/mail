import { describe, expect, it } from 'vitest';
import { resolveComposeFromAccount } from './controller.js';
import type { AccountResponse } from '$lib/types.js';

function account(id: number): AccountResponse {
	return { id } as AccountResponse;
}

describe('resolveComposeFromAccount', () => {
	it('keeps a still-valid current selection', () => {
		expect(resolveComposeFromAccount(2, [account(1), account(2)], 1)).toBe(2);
	});

	it('prefers the active account when nothing is selected yet (mount before accounts ready)', () => {
		// Previously the subscribe callback fell back to accounts[0] and ignored
		// the account the user was actually viewing.
		expect(resolveComposeFromAccount(null, [account(1), account(2)], 2)).toBe(2);
	});

	it('falls back to the first account when the active id is unset or stale', () => {
		expect(resolveComposeFromAccount(null, [account(1), account(2)], null)).toBe(1);
		expect(resolveComposeFromAccount(null, [account(1), account(2)], 99)).toBe(1);
	});

	it('re-resolves a selection that disappeared from the list', () => {
		expect(resolveComposeFromAccount(3, [account(1), account(2)], 2)).toBe(2);
	});

	it('returns null while the account list is empty (accounts not ready)', () => {
		expect(resolveComposeFromAccount(1, [], 1)).toBeNull();
	});
});
