import { expect, test, type Locator, type Page } from '@playwright/test';
import { openApp, rowsOf, setPrefs } from '../e2e-helpers';

/*
 * Shared column tracks in the two flat message grids.
 *
 * Both used to size their columns per row, which lines up with the row above
 * only while every row's content happens to be the same width. The status cell
 * is `auto` around `MessageFlags`, whose three icons are each conditional, so
 * the fixture's flagged / answered / attachment rows measured 30px there and
 * the plain ones 16px — and the subject column behind them started 14px
 * further right, a step in the middle of the list. Fixed by moving the tracks
 * to the container and making the rows `subgrid` (the arrangement the grouped
 * list already uses).
 *
 * The assertions come in pairs on purpose. Alignment alone would pass on a
 * fixture where every row carries the same icons — vacuously, and silently, at
 * exactly the moment the test stopped being able to fail. So each case first
 * proves the rows really do differ in what drives the column, and only then
 * that the column does not move.
 */

/*
 * `aria-colindex` values, which are `COL_* + 1` in the components and differ
 * between the two grids: the search results have no select column and carry a
 * folder column the inbox does not. Cells are addressed by these rather than by
 * the roving `data-col`, because `data-col` sits on the focusable element
 * inside the cell (the subject link) and its box is the cell's minus padding —
 * measuring the two grids through different boxes would let one case tolerate
 * what the other catches.
 */
const INBOX_COLS = { status: 2, subject: 3, sender: 4, actions: 6 };
const SEARCH_COLS = { status: 1, subject: 2 };

interface RowMetrics {
	icons: number;
	subjectX: number;
	height: number;
}

/**
 * Every row's icon count and subject-column left edge, read in **one** pass.
 *
 * Per-row `boundingBox()` calls would read each row in its own round trip, so a
 * reflow part-way through the walk (a late font swap, the scrollbar appearing)
 * would leave rows measured under different layouts and the comparison below
 * would fail without any CSS being wrong — the flake class that already cost
 * this suite two CI reds (#283, #284).
 */
async function readRows(
	grid: Locator,
	cols: { status: number; subject: number }
): Promise<RowMetrics[]> {
	return grid.evaluate(
		(el, colIndex) =>
			[...el.querySelectorAll('[role="row"][data-stable-id]')].map((row) => {
				const subject = row.querySelector<HTMLElement>(
					`[role="gridcell"][aria-colindex="${colIndex.subject}"]`
				);
				return {
					icons: row.querySelectorAll(`[role="gridcell"][aria-colindex="${colIndex.status}"] svg`)
						.length,
					subjectX: subject?.getBoundingClientRect().x ?? Number.NaN,
					height: row.getBoundingClientRect().height
				};
			}),
		cols
	);
}

/**
 * Sub-pixel tolerance rather than equality. The tracks resolve to fractional
 * widths (761.797px, 61.2031px measured), so rounding to whole pixels and then
 * demanding a single distinct value turns a 0.001px difference across a
 * rounding boundary into a failure — while masking up to half a pixel of real
 * drift in the other direction.
 */
function spread(values: number[]): number {
	return Math.max(...values) - Math.min(...values);
}

async function openGrid(page: Page, url: string, name: string): Promise<Locator> {
	await openApp(page, url);
	const grid = page.getByRole('grid', { name });
	await expect(rowsOf(grid).first()).toBeVisible();
	return grid;
}

test.beforeEach(async ({ page }) => {
	/*
	 * `messageGrouping` is pinned, not inherited. `flat` is today's default but
	 * the threading rollout is staged to flip it, and a grouped default renders
	 * ConversationList instead — a treegrid under a different name, so these
	 * tests would die on a locator timeout that says nothing about grouping.
	 */
	await setPrefs(page, { locale: 'cs', messageGrouping: 'flat' });
});

test.describe('Sloupce plochých seznamů', () => {
	test('seznam zpráv drží sloupec předmětu na jednom místě napříč řádky', async ({ page }) => {
		const grid = await openGrid(page, '/mail/1/INBOX', 'Seznam zpráv');
		const rows = await readRows(grid, INBOX_COLS);

		// The precondition: without rows that differ in status icons the alignment
		// assertion below cannot fail, so assert the spread exists before using it.
		expect(new Set(rows.map((row) => row.icons)).size).toBeGreaterThan(1);
		expect(spread(rows.map((row) => row.subjectX))).toBeLessThanOrEqual(0.5);
	});

	test('výsledky hledání drží sloupec předmětu na jednom místě napříč řádky', async ({ page }) => {
		const grid = await openGrid(page, '/search/1?q=zpr%C3%A1va', 'Výsledky');
		const rows = await readRows(grid, SEARCH_COLS);

		expect(new Set(rows.map((row) => row.icons)).size).toBeGreaterThan(1);
		expect(spread(rows.map((row) => row.subjectX))).toBeLessThanOrEqual(0.5);
	});

	/*
	 * The two traps that come with `subgrid`, both of them silent. A padding on
	 * the row insets every one of its tracks at once, so the actions column
	 * leaves the list on the right; and the container's implicit rows are `auto`,
	 * so without `content-start` a short list stretches its rows over the whole
	 * viewport — measured at 597px for two rows whose natural height is 65px.
	 */
	test('řádky nepřetékají doprava ani se neroztahují v krátké složce', async ({ page }) => {
		const grid = await openGrid(page, '/mail/1/SENT', 'Seznam zpráv');

		const box = await grid.evaluate((el, actionsCol) => {
			const rows = [...el.querySelectorAll('[role="row"][data-stable-id]')];
			const actions = rows[rows.length - 1].querySelector<HTMLElement>(
				`[role="gridcell"][aria-colindex="${actionsCol}"]`
			);
			const gridRect = el.getBoundingClientRect();
			return {
				scrolls: el.scrollWidth > el.clientWidth + 1,
				gridHeight: gridRect.height,
				gridRight: gridRect.right,
				rowsHeight: rows.reduce((sum, row) => sum + row.getBoundingClientRect().height, 0),
				actionsRight: actions?.getBoundingClientRect().right ?? Number.POSITIVE_INFINITY
			};
		}, INBOX_COLS.actions);

		expect(box.scrolls).toBe(false);
		expect(box.actionsRight).toBeLessThanOrEqual(box.gridRight);
		/*
		 * Summed, not per-row against a fraction of the grid: a per-row threshold
		 * like `height < gridHeight / 2` only catches stretching while the folder
		 * has two rows and silently stops catching it the day the fixture gains a
		 * third. Stretched rows fill the grid exactly, so the total is the
		 * fixture-independent signal.
		 */
		expect(box.rowsHeight).toBeLessThan(box.gridHeight);
	});

	/*
	 * The row axis is not shared. `subgrid` covers the columns; `grid-rows-[auto
	 * auto]` stays each row's own, so an empty cell still collapses its own
	 * track. The sender cell can genuinely be empty — in Drafts and Sent it
	 * renders `recipientsTo`, which is nullable, so a draft saved without a To
	 * header leaves it blank — and without a floor that row renders shorter than
	 * every other, which is the same "one row shaped unlike the rest" defect the
	 * columns above are about. `min-h-8` is the height the filled cell already
	 * has (20px line + 12px padding), so it costs nothing when there is text.
	 *
	 * Emptied here rather than seeded through a fixture because no fixture
	 * produces a recipient-less draft, and the property under test is the CSS
	 * floor, not the data path that reaches it.
	 */
	test('prázdná buňka odesílatele nezkrátí řádek', async ({ page }) => {
		const grid = await openGrid(page, '/mail/1/INBOX', 'Seznam zpráv');

		const heights = await grid.evaluate((el, senderCol) => {
			const rows = [...el.querySelectorAll('[role="row"][data-stable-id]')];
			const target = rows[1];
			const neighbour = rows[2];
			const before = target.getBoundingClientRect().height;
			const sender = target.querySelector<HTMLElement>(
				`[role="gridcell"][aria-colindex="${senderCol}"]`
			);
			if (sender) sender.textContent = '';
			// No explicit reflow nudge: `getBoundingClientRect` below flushes the
			// pending layout itself, and reading a property for its side effect is
			// the kind of line a later cleanup deletes as dead.
			return {
				before,
				after: target.getBoundingClientRect().height,
				neighbour: neighbour.getBoundingClientRect().height
			};
		}, INBOX_COLS.sender);

		expect(heights.after).toBeCloseTo(heights.before, 1);
		expect(heights.after).toBeCloseTo(heights.neighbour, 1);
	});
});
