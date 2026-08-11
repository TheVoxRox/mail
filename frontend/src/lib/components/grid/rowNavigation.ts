/**
 * Pure helpers for roving keyboard navigation in list components
 * (MessageList, ContactList…). Extracted so the state machine can be
 * unit-tested — the component on top only handles focus / select /
 * preventDefault.
 *
 * When the key handling changes here, update the user-facing overview in
 * routes/settings/shortcuts/+page.svelte — it is a hand-maintained mirror.
 */

export const ROW_NAV_PAGE_STEP = 10;

interface CellNavOptions {
	/** Currently focused cell. */
	row: number;
	col: number;
	/** `rowCount - 1`. If < 0, the helper returns null. */
	maxRow: number;
	/** `colCount - 1`. If < 0, the helper returns null. */
	maxCol: number;
	/** Ctrl held — Home/End jump to the first/last row instead of the column edge. */
	ctrl?: boolean;
	/** Step for PageUp/PageDown. Default 10. */
	pageStep?: number;
}

/**
 * Returns the next focused cell for the given key in a 2-D roving grid, or
 * `null` when the key is not a navigation key (or the cell would not move).
 *
 * Arrow keys move one cell. Without Ctrl, Home/End jump to the first/last
 * column of the current row; with Ctrl they jump to the first/last row of the
 * current column. PageUp/PageDown move by `pageStep` rows. Mirrors the inbox
 * grid (MessageList) so the keyboard model is identical across message lists.
 */
export function computeNextCell(
	key: string,
	options: CellNavOptions
): { row: number; col: number } | null {
	const { row, col, maxRow, maxCol } = options;
	if (maxRow < 0 || maxCol < 0) return null;
	const step = options.pageStep ?? ROW_NAV_PAGE_STEP;
	let next: { row: number; col: number };
	switch (key) {
		case 'ArrowDown':
			next = { row: Math.min(maxRow, row + 1), col };
			break;
		case 'ArrowUp':
			next = { row: Math.max(0, row - 1), col };
			break;
		case 'ArrowRight':
			next = { row, col: Math.min(maxCol, col + 1) };
			break;
		case 'ArrowLeft':
			next = { row, col: Math.max(0, col - 1) };
			break;
		case 'Home':
			next = options.ctrl ? { row: 0, col } : { row, col: 0 };
			break;
		case 'End':
			next = options.ctrl ? { row: maxRow, col } : { row, col: maxCol };
			break;
		case 'PageDown':
			next = { row: Math.min(maxRow, row + step), col };
			break;
		case 'PageUp':
			next = { row: Math.max(0, row - step), col };
			break;
		default:
			return null;
	}
	if (next.row === row && next.col === col) return null;
	return next;
}

/**
 * Focuses the cell marked `[data-cell-target][data-col="{col}"]` inside the row
 * `[data-row-index="{rowIndex}"]` within `gridEl`. Shared by the roving-cell
 * grids (MessageList, SearchResultsGrid, ContactList) so the cell lookup stays
 * identical.
 *
 * Returns whether focus actually moved: false means the grid or the cell is not
 * (yet) in the DOM — the caller may be a component instance about to be torn
 * down, and a one-shot focus request must survive that to be honoured by the
 * instance that replaces it.
 */
export function focusGridCell(gridEl: HTMLElement | null, rowIndex: number, col: number): boolean {
	const cell = gridEl
		?.querySelector<HTMLElement>(`[data-row-index="${rowIndex}"]`)
		?.querySelector<HTMLElement>(`[data-cell-target][data-col="${col}"]`);
	if (!cell) return false;
	cell.focus();
	return true;
}
