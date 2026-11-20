import { describe, expect, it } from 'vitest';
import { clampPageTarget, lastPageIfPastEnd } from './paging.js';

describe('clampPageTarget', () => {
	it('resolves a target inside the range', () => {
		expect(clampPageTarget(3, 10, 0)).toBe(3);
	});

	it('reports the page already shown as no move', () => {
		expect(clampPageTarget(4, 10, 4)).toBeNull();
	});

	it('clamps past the last page instead of rejecting', () => {
		expect(clampPageTarget(900, 12, 0)).toBe(11);
	});

	it('clamps below the first page', () => {
		expect(clampPageTarget(-3, 12, 5)).toBe(0);
	});

	/*
	 * A single-page list: every control resolves to the page in view, so the
	 * pager must report no move rather than asking for page 0 again.
	 */
	it('has nowhere to go in a one-page list', () => {
		expect(clampPageTarget(1, 1, 0)).toBeNull();
		expect(clampPageTarget(-1, 1, 0)).toBeNull();
	});

	/* An empty list still has a page 0 — `totalPages` is 0, `lastPage` is not -1. */
	it('does not fall below zero on an empty list', () => {
		expect(clampPageTarget(2, 0, 0)).toBeNull();
		expect(clampPageTarget(2, 0, 3)).toBe(0);
	});

	it('truncates a fractional target rather than landing between pages', () => {
		expect(clampPageTarget(2.7, 10, 0)).toBe(2);
	});

	it('treats a target that is not a number as no move', () => {
		expect(clampPageTarget(Number.NaN, 10, 0)).toBeNull();
	});
});

describe('lastPageIfPastEnd', () => {
	function shown(page: number, rows: number, totalElements: number, size = 25) {
		return {
			content: Array.from({ length: rows }, (_, index) => index),
			page,
			totalPages: Math.ceil(totalElements / size),
			totalElements
		};
	}

	it('leaves a page that has rows alone', () => {
		expect(lastPageIfPastEnd(shown(1, 1, 26))).toBeNull();
	});

	/* The removal of the second page's only row: the server still answers page 1. */
	it('points an emptied last page at the one before it', () => {
		expect(lastPageIfPastEnd(shown(1, 0, 25))).toBe(0);
	});

	it('goes to the last page that exists, not just one back', () => {
		// A bulk removal can take more than a page's worth.
		expect(lastPageIfPastEnd(shown(4, 0, 30))).toBe(1);
	});

	it('leaves an empty first page alone, that is where an empty list is said', () => {
		expect(lastPageIfPastEnd(shown(0, 0, 0))).toBeNull();
	});

	it('leaves a later page alone when the whole list is empty', () => {
		// Page 0 would be just as empty; asking for it costs a request for nothing.
		expect(lastPageIfPastEnd(shown(2, 0, 0))).toBeNull();
	});

	it('moves back even when the page count claims the page still exists', () => {
		// A caller loops until null, so an inconsistent answer must not keep it on
		// the same index.
		expect(lastPageIfPastEnd({ content: [], page: 3, totalPages: 9, totalElements: 200 })).toBe(2);
	});
});
