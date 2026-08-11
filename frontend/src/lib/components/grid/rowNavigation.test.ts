import { describe, expect, it } from 'vitest';
import { computeNextCell, isDeliberateOpenClick, ROW_NAV_PAGE_STEP } from './rowNavigation.js';

describe('isDeliberateOpenClick', () => {
	const click = (detail: number) => ({ detail }) as MouseEvent;

	it('treats a single click as select-only, the twin of an Arrow key', () => {
		expect(isDeliberateOpenClick(click(1))).toBe(false);
	});

	it('opens on a double click', () => {
		expect(isDeliberateOpenClick(click(2))).toBe(true);
		expect(isDeliberateOpenClick(click(3))).toBe(true);
	});

	// A screen reader in browse mode activates the cell instead of delivering
	// Enter, and that arrives with no click count. Counting it as a single click
	// is what made Enter look dead in the message list.
	it('opens on an activation carrying no click count', () => {
		expect(isDeliberateOpenClick(click(0))).toBe(true);
	});
});

describe('computeNextCell', () => {
	const grid = { row: 2, col: 1, maxRow: 5, maxCol: 4 };

	it('returns null on empty grid (no rows or no cols)', () => {
		expect(computeNextCell('ArrowDown', { row: 0, col: 0, maxRow: -1, maxCol: 4 })).toBeNull();
		expect(computeNextCell('ArrowRight', { row: 0, col: 0, maxRow: 5, maxCol: -1 })).toBeNull();
	});

	it('returns null for a non-navigation key', () => {
		expect(computeNextCell('x', grid)).toBeNull();
		expect(computeNextCell('Enter', grid)).toBeNull();
		expect(computeNextCell(' ', grid)).toBeNull();
	});

	it('arrow keys move one cell along the right axis', () => {
		expect(computeNextCell('ArrowDown', grid)).toEqual({ row: 3, col: 1 });
		expect(computeNextCell('ArrowUp', grid)).toEqual({ row: 1, col: 1 });
		expect(computeNextCell('ArrowRight', grid)).toEqual({ row: 2, col: 2 });
		expect(computeNextCell('ArrowLeft', grid)).toEqual({ row: 2, col: 0 });
	});

	it('arrow keys clamp at the edges and return null when they cannot move', () => {
		expect(computeNextCell('ArrowUp', { row: 0, col: 1, maxRow: 5, maxCol: 4 })).toBeNull();
		expect(computeNextCell('ArrowDown', { row: 5, col: 1, maxRow: 5, maxCol: 4 })).toBeNull();
		expect(computeNextCell('ArrowLeft', { row: 2, col: 0, maxRow: 5, maxCol: 4 })).toBeNull();
		expect(computeNextCell('ArrowRight', { row: 2, col: 4, maxRow: 5, maxCol: 4 })).toBeNull();
	});

	it('Home / End jump to the column edges of the current row', () => {
		expect(computeNextCell('Home', grid)).toEqual({ row: 2, col: 0 });
		expect(computeNextCell('End', grid)).toEqual({ row: 2, col: 4 });
	});

	it('Ctrl+Home / Ctrl+End jump to the row edges of the current column', () => {
		expect(computeNextCell('Home', { ...grid, ctrl: true })).toEqual({ row: 0, col: 1 });
		expect(computeNextCell('End', { ...grid, ctrl: true })).toEqual({ row: 5, col: 1 });
	});

	it('PageDown / PageUp move by step along rows and clamp', () => {
		expect(computeNextCell('PageDown', { row: 0, col: 2, maxRow: 50, maxCol: 4 })).toEqual({
			row: ROW_NAV_PAGE_STEP,
			col: 2
		});
		expect(computeNextCell('PageUp', { row: 3, col: 2, maxRow: 50, maxCol: 4 })).toEqual({
			row: 0,
			col: 2
		});
		expect(computeNextCell('PageDown', { row: 48, col: 2, maxRow: 50, maxCol: 4 })).toEqual({
			row: 50,
			col: 2
		});
	});

	it('respects a custom pageStep override', () => {
		expect(
			computeNextCell('PageDown', { row: 0, col: 0, maxRow: 50, maxCol: 4, pageStep: 25 })
		).toEqual({ row: 25, col: 0 });
	});
});
