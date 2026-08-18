import { describe, expect, it } from 'vitest';
import { clampPageTarget } from './paging.js';

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
