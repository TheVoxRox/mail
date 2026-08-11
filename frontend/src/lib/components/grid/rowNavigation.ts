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
/**
 * Is this click a deliberate open of the row, rather than the select-only
 * single click that mirrors an Arrow key?
 *
 * `event.detail` is the click count: 2 is the second click of a double click,
 * the mouse gesture for "open". 0 is not a mouse click at all — a screen reader
 * in browse mode keeps the unmodified keys for its own navigation and never
 * delivers Enter to the grid as a keydown, so it activates the cell under its
 * cursor instead and the webview dispatches that as a click carrying no click
 * count. That IS the Enter the user pressed. Counting it as a single click is
 * what makes Enter look dead: with the reading pane off nothing happens at all,
 * and beside a pane the message shows but the reading cursor never leaves the
 * row, so the SR user hears nothing of what they just opened.
 */
export function isDeliberateOpenClick(event: MouseEvent): boolean {
	return event.detail === 0 || event.detail >= 2;
}

/**
 * Column of the `[data-cell-target]` cell a click came from, or null when it
 * landed outside one. Lets a row-level click handler honour the same per-column
 * exemptions as its keydown twin: a cell that owns a control (checkbox, expand
 * toggle) must keep its own activation, and a screen-reader activation is fired
 * from the very cell it targets.
 */
export function clickedCellColumn(target: HTMLElement | null): number | null {
	const col = target?.closest<HTMLElement>('[data-cell-target]')?.dataset.col;
	return col === undefined ? null : Number(col);
}

export function focusGridCell(gridEl: HTMLElement | null, rowIndex: number, col: number): boolean {
	const cell = gridEl
		?.querySelector<HTMLElement>(`[data-row-index="${rowIndex}"]`)
		?.querySelector<HTMLElement>(`[data-cell-target][data-col="${col}"]`);
	if (!cell) return false;
	cell.focus();
	return true;
}
