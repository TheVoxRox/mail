import { describe, expect, it } from 'vitest';
import { MAX_EMAILS_PER_CONTACT, buildMergePreview, exceedsEmailLimit } from './mergePreview.js';
import type { ContactEmailResponse, ContactResponse } from '$lib/types.js';

/**
 * The merge preview is the non-trivial part of the contact merge: a
 * case-insensitive, order-preserving union where the target keeps its primary
 * address and source addresses are flagged so the UI (and a screen reader) can
 * announce which addresses are being pulled in. These tests pin that algorithm
 * and the per-contact e-mail cap that gates the merge button — the same union
 * the e2e merge tests exercise once, here across the edge cases.
 */

let emailIdSeq = 0;

function email(address: string, primary = false): ContactEmailResponse {
	emailIdSeq += 1;
	return { id: emailIdSeq, email: address, label: null, primary };
}

function contact(id: number, emails: ContactEmailResponse[]): ContactResponse {
	return {
		id,
		emails,
		name: `Contact ${id}`,
		surname: null,
		note: null,
		createdAt: '2026-01-01T00:00:00Z',
		updatedAt: '2026-01-01T00:00:00Z'
	};
}

describe('buildMergePreview', () => {
	it('returns nothing when there is no target', () => {
		expect(buildMergePreview(null, [contact(2, [email('a@x.cz')])])).toEqual([]);
	});

	it('keeps the target addresses first, in order, preserving the primary flag', () => {
		const target = contact(1, [email('primary@x.cz', true), email('second@x.cz')]);

		const out = buildMergePreview(target, []);

		expect(out).toEqual([
			{ email: 'primary@x.cz', primary: true, fromTarget: true },
			{ email: 'second@x.cz', primary: false, fromTarget: true }
		]);
	});

	it('appends source addresses after the target, marked fromTarget=false and never primary', () => {
		const target = contact(1, [email('jana@x.cz', true)]);
		const source = contact(2, [email('jan@x.cz', true)]);

		const out = buildMergePreview(target, [source]);

		expect(out).toEqual([
			{ email: 'jana@x.cz', primary: true, fromTarget: true },
			// Source brought a primary address, but only the target may stay primary.
			{ email: 'jan@x.cz', primary: false, fromTarget: false }
		]);
	});

	it('deduplicates a source address that the target already has, case-insensitively', () => {
		const target = contact(1, [email('Jana@X.cz', true)]);
		const source = contact(2, [email('jana@x.CZ'), email('new@x.cz')]);

		const out = buildMergePreview(target, [source]);

		// The collided address stays the target's (original casing + primary); only
		// the genuinely new source address is appended.
		expect(out).toEqual([
			{ email: 'Jana@X.cz', primary: true, fromTarget: true },
			{ email: 'new@x.cz', primary: false, fromTarget: false }
		]);
	});

	it('deduplicates duplicate addresses within the target itself', () => {
		const target = contact(1, [email('dup@x.cz', true), email('DUP@x.cz')]);

		const out = buildMergePreview(target, []);

		expect(out).toEqual([{ email: 'dup@x.cz', primary: true, fromTarget: true }]);
	});

	it('deduplicates across multiple sources, first occurrence wins and order is preserved', () => {
		const target = contact(1, [email('t@x.cz', true)]);
		const sourceA = contact(2, [email('shared@x.cz'), email('a-only@x.cz')]);
		const sourceB = contact(3, [email('SHARED@x.cz'), email('b-only@x.cz')]);

		const out = buildMergePreview(target, [sourceA, sourceB]);

		expect(out.map((e) => e.email)).toEqual([
			't@x.cz',
			'shared@x.cz',
			'a-only@x.cz',
			'b-only@x.cz'
		]);
		// shared@x.cz came from sourceA; sourceB's casing variant is dropped.
		expect(out.every((e) => !e.primary || e.email === 't@x.cz')).toBe(true);
	});
});

describe('exceedsEmailLimit', () => {
	it('does not exceed at exactly the cap, but does one over', () => {
		expect(MAX_EMAILS_PER_CONTACT).toBe(10);
		expect(exceedsEmailLimit(MAX_EMAILS_PER_CONTACT)).toBe(false);
		expect(exceedsEmailLimit(MAX_EMAILS_PER_CONTACT + 1)).toBe(true);
		expect(exceedsEmailLimit(0)).toBe(false);
	});

	it('agrees with the preview length at the 10/11 boundary', () => {
		// Target with 2 + source with 8 unique = 10 (allowed); + 1 more = 11 (blocked).
		const target = contact(1, [email('t1@x.cz', true), email('t2@x.cz')]);
		const eight = contact(
			2,
			Array.from({ length: 8 }, (_, i) => email(`s${i}@x.cz`))
		);
		const atCap = buildMergePreview(target, [eight]);
		expect(atCap).toHaveLength(10);
		expect(exceedsEmailLimit(atCap.length)).toBe(false);

		const nine = contact(
			3,
			Array.from({ length: 9 }, (_, i) => email(`u${i}@x.cz`))
		);
		const overCap = buildMergePreview(target, [nine]);
		expect(overCap).toHaveLength(11);
		expect(exceedsEmailLimit(overCap.length)).toBe(true);
	});
});
