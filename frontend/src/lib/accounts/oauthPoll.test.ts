import { describe, expect, it, vi } from 'vitest';
import {
	OAUTH_POLL_MAX_ATTEMPTS,
	isOtherIdentity,
	pollForOAuthAccount,
	signedInAccountFor,
	type OAuthPollAccount,
	type OAuthPollOptions
} from './oauthPoll.js';

type TestAccount = OAuthPollAccount;

const ACCOUNT: TestAccount = { id: 1, email: 'test.user@gmail.com', oauth2Provider: 'google' };
/** The account a sign-in in the browser adds as someone other than the address typed. */
const OTHER: TestAccount = { id: 2, email: 'someone.else@gmail.com', oauth2Provider: 'google' };
/** The same address as a password account, before a sign-in takes it over. */
const PASSWORD_ACCOUNT: TestAccount = { ...ACCOUNT, oauth2Provider: null };

/** sleep is injected and resolves instantly so the ~10-min budget runs in ms. */
function baseOptions(
	listAccounts: () => Promise<TestAccount[]>,
	overrides: Partial<OAuthPollOptions<TestAccount>> = {}
): OAuthPollOptions<TestAccount> {
	return {
		email: 'test.user@gmail.com',
		baseline: [],
		listAccounts,
		sleep: async () => {},
		...overrides
	};
}

describe('pollForOAuthAccount', () => {
	it('resolves the account once it appears within budget (case-insensitive)', async () => {
		let calls = 0;
		const listAccounts = vi.fn(async () => {
			calls += 1;
			// Appears on the 3rd poll, with different casing than the query.
			return calls >= 3 ? [{ ...ACCOUNT, email: 'Test.User@Gmail.com' }] : [];
		});

		const result = await pollForOAuthAccount(baseOptions(listAccounts));

		expect(result).toEqual({ ...ACCOUNT, email: 'Test.User@Gmail.com' });
		expect(calls).toBe(3);
	});

	it('recovers via the final reconcile when the account is created as the budget expires', async () => {
		let calls = 0;
		// Empty for every in-budget poll; only the post-budget reconcile sees it.
		const listAccounts = vi.fn(async () => {
			calls += 1;
			return calls > OAUTH_POLL_MAX_ATTEMPTS ? [ACCOUNT] : [];
		});

		const result = await pollForOAuthAccount(baseOptions(listAccounts));

		// This is the near-miss the fix targets: budget exhausted, account still
		// returned instead of a false timeout.
		expect(result).toEqual(ACCOUNT);
		expect(calls).toBe(OAUTH_POLL_MAX_ATTEMPTS + 1);
	});

	it('returns null when the account never appears (genuine timeout)', async () => {
		const listAccounts = vi.fn(async () => [] as TestAccount[]);

		const result = await pollForOAuthAccount(baseOptions(listAccounts));

		expect(result).toBeNull();
		expect(listAccounts).toHaveBeenCalledTimes(OAUTH_POLL_MAX_ATTEMPTS + 1);
	});

	it('ignores transient list errors and keeps polling', async () => {
		let calls = 0;
		const listAccounts = vi.fn(async () => {
			calls += 1;
			if (calls < 3) throw new Error('network down');
			return [ACCOUNT];
		});

		const result = await pollForOAuthAccount(baseOptions(listAccounts));

		expect(result).toEqual(ACCOUNT);
	});

	it('aborts early when shouldContinue turns false (cancel / leave page)', async () => {
		const listAccounts = vi.fn(async () => [] as TestAccount[]);
		let checks = 0;
		const result = await pollForOAuthAccount(
			baseOptions(listAccounts, {
				// Stay active for a few checks, then simulate the user cancelling.
				shouldContinue: () => {
					checks += 1;
					return checks <= 6;
				}
			})
		);

		expect(result).toBeNull();
		// Far fewer than the full budget — it bailed out, not timed out.
		expect(listAccounts.mock.calls.length).toBeLessThan(OAUTH_POLL_MAX_ATTEMPTS);
	});

	/*
	 * The wizard matched by address alone, so an account that already had the
	 * address was found on the first poll and reported added before the user had
	 * signed in at all.
	 */
	it('does not count an account a sign-in already owned before this one started', async () => {
		const listAccounts = vi.fn(async () => [ACCOUNT]);

		const result = await pollForOAuthAccount(baseOptions(listAccounts, { baseline: [ACCOUNT] }));

		expect(result).toBeNull();
		expect(listAccounts).toHaveBeenCalledTimes(OAUTH_POLL_MAX_ATTEMPTS + 1);
	});

	it('counts a password account once the sign-in has taken it over', async () => {
		let calls = 0;
		const listAccounts = vi.fn(async () => {
			calls += 1;
			return calls >= 3 ? [ACCOUNT] : [PASSWORD_ACCOUNT];
		});

		const result = await pollForOAuthAccount(
			baseOptions(listAccounts, { baseline: [PASSWORD_ACCOUNT] })
		);

		expect(result).toEqual(ACCOUNT);
		expect(calls).toBe(3);
	});

	/*
	 * The browser lets the user sign in as anyone, and the backend adds the account
	 * of the identity they signed in with. Waiting for the typed address alone ran
	 * the poll out for an account that had been added.
	 */
	it('counts an account the sign-in added under another identity', async () => {
		let calls = 0;
		const listAccounts = vi.fn(async () => {
			calls += 1;
			return calls >= 3 ? [OTHER] : [];
		});

		const result = await pollForOAuthAccount(baseOptions(listAccounts));

		expect(result).toEqual(OTHER);
		expect(calls).toBe(3);
	});

	it('prefers the account added under the typed address', async () => {
		const listAccounts = vi.fn(async () => [OTHER, ACCOUNT]);

		const result = await pollForOAuthAccount(baseOptions(listAccounts));

		expect(result).toEqual(ACCOUNT);
	});

	it('does not count another identity a sign-in already owned before this one started', async () => {
		const listAccounts = vi.fn(async () => [OTHER]);

		const result = await pollForOAuthAccount(baseOptions(listAccounts, { baseline: [OTHER] }));

		expect(result).toBeNull();
	});

	it('does not count a password account the sign-in has not taken over', async () => {
		const listAccounts = vi.fn(async () => [PASSWORD_ACCOUNT]);

		const result = await pollForOAuthAccount(
			baseOptions(listAccounts, { baseline: [PASSWORD_ACCOUNT] })
		);

		expect(result).toBeNull();
	});
});

describe('isOtherIdentity', () => {
	it('is the typed address whatever its casing, and another address is not', () => {
		expect(isOtherIdentity(ACCOUNT, ' Test.User@Gmail.com ')).toBe(false);
		expect(isOtherIdentity(OTHER, 'test.user@gmail.com')).toBe(true);
	});
});

describe('signedInAccountFor', () => {
	it('finds the account a sign-in owns under the address, whatever its casing', () => {
		expect(signedInAccountFor([ACCOUNT], ' Test.User@Gmail.com ')).toEqual(ACCOUNT);
	});

	it('leaves a password account under the address to the sign-in, which upgrades it', () => {
		expect(signedInAccountFor([PASSWORD_ACCOUNT], ACCOUNT.email)).toBeUndefined();
	});
});
