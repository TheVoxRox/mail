/**
 * The component-side half of roving-cell grid navigation: where the roving
 * position currently is, the attributes a cell needs to take part in it, and
 * the moves a key handler asks for. The pure key math and the DOM lookup stay
 * in `rowNavigation.ts` — this is the state that sits on top of them.
 *
 * Every data grid in the app kept its own copy of that state together with a
 * `setFocus` / `handleCellFocus` pair, and every cell repeated the same five
 * attributes by hand. The copies had already drifted in naming, which is the
 * tell that behaviour drifts next — and in grids the maintainer reads with a
 * screen reader, drift means the reading cursor lands somewhere different
 * depending on which list you happen to be in.
 *
 * Deliberately left with the callers: `handleKeydown` itself, because
 * Enter/Space means something different in each grid (open the message, edit
 * the contact, expand the thread), and the trigger for `clampRow`, because
 * each list gates it on its own store state. Both look like duplication and
 * are not.
 */
import { tick } from 'svelte';
import { computeNextCell, focusGridCell, ROW_NAV_PAGE_STEP } from './rowNavigation.js';

/** What a cell must carry to be reachable by the roving position. */
export interface RovingCellProps {
	'data-cell-target': string;
	'data-col': number;
	tabindex: 0 | -1;
	onfocus: () => void;
}

export interface RovingGrid {
	/** Row index holding the roving position. */
	readonly row: number;
	/** Column holding the roving position. */
	readonly col: number;
	/** Whether the roving position is on this cell. */
	isAt(rowIndex: number, col: number): boolean;
	/** Spread onto a cell: `<td {...grid.cell(rowIndex, COL_NAME)}>`. */
	cell(rowIndex: number, col: number): RovingCellProps;
	/**
	 * Records where focus landed without moving it — for a cell that took focus
	 * on its own (a click, or Tab into the grid).
	 */
	track(rowIndex: number, col: number): void;
	/** Moves the roving position, and the DOM focus after it once rendered. */
	moveTo(rowIndex: number, col: number): void;
	/**
	 * Focuses the cell right now and records the position if that worked.
	 * Returns false when the grid or the row is not in the DOM — the caller may
	 * be an instance about to be torn down, and a one-shot focus request has to
	 * survive that to be honoured by the instance replacing it.
	 */
	focusNow(rowIndex: number, col: number): boolean;
	/**
	 * Where an arrow / Home / End / PageUp / PageDown press would land, or null
	 * when the key is not one of those. Moves nothing — for grids that first
	 * need to know whether the move crosses a row (the inbox drags the reading
	 * pane's selection along with it, but only then).
	 */
	nextCell(
		event: KeyboardEvent,
		rowIndex: number,
		rowCount: number
	): { row: number; col: number } | null;
	/**
	 * `nextCell` plus the move and `preventDefault`. Returns whether the key was
	 * a navigation key, so the caller can fall through to keys of its own.
	 */
	navigate(event: KeyboardEvent, rowIndex: number, rowCount: number): boolean;
	/** Pulls the position back inside a page that shrank. */
	clampRow(rowCount: number): void;
}

interface RovingGridOptions {
	/** The `role="grid"` element. A thunk — it is bound after this runs. */
	element: () => HTMLElement | null;
	/** Column the position starts on. */
	initialCol: number;
	/** `colCount - 1`. */
	maxCol: number;
}

export function createRovingGrid({ element, initialCol, maxCol }: RovingGridOptions): RovingGrid {
	let row = $state(0);
	let col = $state(initialCol);

	function track(rowIndex: number, nextCol: number): void {
		row = rowIndex;
		col = nextCol;
	}

	function moveTo(rowIndex: number, nextCol: number): void {
		track(rowIndex, nextCol);
		void tick().then(() => focusGridCell(element(), rowIndex, nextCol));
	}

	function nextCell(
		event: KeyboardEvent,
		rowIndex: number,
		rowCount: number
	): { row: number; col: number } | null {
		return computeNextCell(event.key, {
			row: rowIndex,
			col,
			maxRow: rowCount - 1,
			maxCol,
			ctrl: event.ctrlKey,
			pageStep: ROW_NAV_PAGE_STEP
		});
	}

	return {
		get row() {
			return row;
		},
		get col() {
			return col;
		},
		isAt: (rowIndex, targetCol) => row === rowIndex && col === targetCol,
		cell: (rowIndex, targetCol) => ({
			'data-cell-target': '',
			'data-col': targetCol,
			tabindex: row === rowIndex && col === targetCol ? 0 : -1,
			onfocus: () => track(rowIndex, targetCol)
		}),
		track,
		moveTo,
		focusNow(rowIndex, nextCol) {
			if (!focusGridCell(element(), rowIndex, nextCol)) return false;
			track(rowIndex, nextCol);
			return true;
		},
		nextCell,
		navigate(event, rowIndex, rowCount) {
			const next = nextCell(event, rowIndex, rowCount);
			if (!next) return false;
			event.preventDefault();
			moveTo(next.row, next.col);
			return true;
		},
		clampRow(rowCount) {
			if (row >= rowCount) row = Math.max(0, rowCount - 1);
		}
	};
}
