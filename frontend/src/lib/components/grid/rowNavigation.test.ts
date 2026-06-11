// @vitest-environment jsdom
import { describe, expect, it } from 'vitest';
import {
	computeNextCell,
	computeNextRowIndex,
	rowIndexFromTarget,
	ROW_NAV_PAGE_STEP
} from './rowNavigation.js';

describe('computeNextRowIndex', () => {
	it('returns null on empty list', () => {
		expect(computeNextRowIndex('ArrowDown', { maxIndex: -1, currentIndex: 0 })).toBeNull();
	});

	it('returns null for non-navigation key', () => {
		expect(computeNextRowIndex('x', { maxIndex: 9, currentIndex: 3 })).toBeNull();
		expect(computeNextRowIndex('Enter', { maxIndex: 9, currentIndex: 3 })).toBeNull();
	});

	it('ArrowDown / ArrowUp clamp at boundaries', () => {
		expect(computeNextRowIndex('ArrowDown', { maxIndex: 5, currentIndex: 5 })).toBe(5);
		expect(computeNextRowIndex('ArrowUp', { maxIndex: 5, currentIndex: 0 })).toBe(0);
		expect(computeNextRowIndex('ArrowDown', { maxIndex: 5, currentIndex: 2 })).toBe(3);
		expect(computeNextRowIndex('ArrowUp', { maxIndex: 5, currentIndex: 2 })).toBe(1);
	});

	it('Home goes to 0, End to maxIndex', () => {
		expect(computeNextRowIndex('Home', { maxIndex: 5, currentIndex: 3 })).toBe(0);
		expect(computeNextRowIndex('End', { maxIndex: 5, currentIndex: 3 })).toBe(5);
	});

	it('PageDown / PageUp respect step and clamp', () => {
		expect(computeNextRowIndex('PageDown', { maxIndex: 20, currentIndex: 5 })).toBe(15);
		expect(computeNextRowIndex('PageDown', { maxIndex: 20, currentIndex: 15 })).toBe(20);
		expect(computeNextRowIndex('PageUp', { maxIndex: 20, currentIndex: 5 })).toBe(0);
		expect(computeNextRowIndex('PageUp', { maxIndex: 20, currentIndex: 15 })).toBe(5);
	});

	it('respects custom pageStep override', () => {
		expect(computeNextRowIndex('PageDown', { maxIndex: 50, currentIndex: 0, pageStep: 25 })).toBe(
			25
		);
	});

	it('default pageStep equals ROW_NAV_PAGE_STEP', () => {
		expect(computeNextRowIndex('PageDown', { maxIndex: 50, currentIndex: 0 })).toBe(
			ROW_NAV_PAGE_STEP
		);
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

describe('rowIndexFromTarget', () => {
	it('returns null for null / non-Element', () => {
		expect(rowIndexFromTarget(null)).toBeNull();
		expect(rowIndexFromTarget(undefined as unknown as EventTarget)).toBeNull();
	});

	it('returns null when no ancestor has data-row-index', () => {
		const el = document.createElement('div');
		expect(rowIndexFromTarget(el)).toBeNull();
	});

	it('reads data-row-index from element itself', () => {
		const el = document.createElement('div');
		el.dataset.rowIndex = '7';
		expect(rowIndexFromTarget(el)).toBe(7);
	});

	it('reads data-row-index from closest ancestor', () => {
		const parent = document.createElement('div');
		parent.dataset.rowIndex = '4';
		const child = document.createElement('span');
		parent.appendChild(child);
		expect(rowIndexFromTarget(child)).toBe(4);
	});

	it('returns null when data-row-index is not an integer', () => {
		const el = document.createElement('div');
		el.dataset.rowIndex = 'not-a-number';
		expect(rowIndexFromTarget(el)).toBeNull();
		el.dataset.rowIndex = '1.5';
		expect(rowIndexFromTarget(el)).toBeNull();
	});
});
