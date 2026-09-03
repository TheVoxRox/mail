import { expect, test, type Locator, type Page } from '@playwright/test';
import {
	conversationGrid,
	messageGrid,
	openApp,
	rowsOf,
	setMockFlags,
	setPrefs
} from '../e2e-helpers';

const accountId = 1;
const folderName = 'INBOX';

test.beforeEach(async ({ page }) => {
	// Opt into the conversation-grouped folder view (Phase 2).
	await setPrefs(page, { locale: 'cs', messageGrouping: 'grouped' });
});

test.describe('Konverzační seskupení', () => {
	test('seskupený režim vykreslí seznam konverzací místo plochého seznamu', async ({ page }) => {
		await openApp(page, `/mail/${accountId}/${encodeURIComponent(folderName)}`);

		const grid = conversationGrid(page);
		await expect(grid).toBeVisible();
		// The flat message list must not be mounted at the same time.
		await expect(messageGrid(page)).toHaveCount(0);
		await expect(rowsOf(grid).first()).toBeVisible();
	});

	test('zaškrtávací políčko říká zpráva u samostatné zprávy a konverzace až u vlákna', async ({
		page
	}) => {
		// A one-message row is a message; calling it a conversation promises a
		// thread that is not there and misdescribes what a bulk action will reach.
		await openApp(page, `/mail/${accountId}/${encodeURIComponent(folderName)}`);

		await expect(
			page.getByRole('checkbox', { name: 'Vybrat zprávu Projektové podklady', exact: true })
		).toBeVisible();
		await expect(page.getByRole('checkbox', { name: /^Vybrat konverzaci/ })).toHaveCount(0);

		// The ARCHIVE row really is a 4-message conversation.
		await openApp(page, `/mail/${accountId}/ARCHIVE`);
		await expect(
			page.getByRole('checkbox', { name: 'Vybrat konverzaci Re: Plán vydání', exact: true })
		).toBeVisible();
	});

	test('jednoklik otevře konverzaci na reprezentativní zprávě', async ({ page }) => {
		await openApp(page, `/mail/${accountId}/${encodeURIComponent(folderName)}`);

		const grid = conversationGrid(page);
		const firstRow = rowsOf(grid).first();
		await expect(firstRow).toBeVisible();
		const stableId = await firstRow.getAttribute('data-stable-id');
		// The web-mail model: a click opens, the checkbox selects. Target the
		// subject rather than the row centre, which can land between cells.
		await firstRow.locator('[data-cell-target][data-col="3"]').click();

		await page.waitForURL(
			`**/mail/${accountId}/${encodeURIComponent(folderName)}/${encodeURIComponent(stableId ?? '')}`
		);
	});

	test('aktivace odečítačem otevře konverzaci jako Enter', async ({ page }) => {
		await setPrefs(page, { readingPane: 'off' });
		await openApp(page, `/mail/${accountId}/${encodeURIComponent(folderName)}`);

		const grid = conversationGrid(page);
		const firstRow = rowsOf(grid).first();
		await expect(firstRow).toBeVisible();
		const stableId = await firstRow.getAttribute('data-stable-id');
		// `a` inside the cell, not the cell: the roving target is the gridcell, and
		// the anchor is what browse mode activates.
		const subjectLink = firstRow.locator('[data-cell-target][data-col="3"] a');
		await expect(subjectLink).toHaveRole('link');
		await subjectLink.focus();

		// The reader keeps Enter for browse-mode navigation and activates the
		// subject link instead; the click it sends may carry no click count.
		await subjectLink.evaluate((el: HTMLElement) => el.click());

		await page.waitForURL(
			`**/mail/${accountId}/${encodeURIComponent(folderName)}/${encodeURIComponent(stableId ?? '')}`
		);
	});

	test('zaškrtávátko vybere konverzaci a neotevře ji', async ({ page }) => {
		await setPrefs(page, { readingPane: 'off' });
		await openApp(page, `/mail/${accountId}/${encodeURIComponent(folderName)}`);

		const grid = conversationGrid(page);
		const firstRow = rowsOf(grid).first();
		await expect(firstRow).toBeVisible();
		await firstRow.locator('input[type="checkbox"]').check();

		// Selection is the checkbox alone — it keeps its click to itself.
		await page.waitForTimeout(700);
		await expect(page).toHaveURL(new RegExp(`/mail/${accountId}/${folderName}$`));
		await expect(grid).toBeVisible();
	});
});

// The ARCHIVE fixture holds one thread: three ARCHIVE messages, arch-03 newest
// = the representative. Three further members of the same thread sit in the
// folders a conversation never spans and may be neither counted nor revealed:
// sent-plan-01 (SENT — the user's own reply), junk-plan-01 (JUNK) and
// draft-plan-01 (DRAFTS). No other test asserts ARCHIVE, so it is a free
// multi-message seam. The cross-folder member a conversation does span comes
// from the opt-in `mail.e2e.inboxThreadMember` seed.
//
// An expanded thread lists all its messages as child rows, the representative
// included, so arch-03 has both a parent row and a child row of its own and the
// two have to be addressed apart.
const archiveRow = (page: Page, stableId: string) =>
	conversationGrid(page).locator(
		`[role="row"][data-row-kind="conversation"][data-stable-id="${stableId}"]`
	);

const archiveMember = (page: Page, stableId: string) =>
	conversationGrid(page).locator(
		`[role="row"][data-row-kind="member"][data-stable-id="${stableId}"]`
	);

/** Either kind — for asserting a message has no row in the view at all. */
const anyArchiveRow = (page: Page, stableId: string) =>
	conversationGrid(page).locator(`[role="row"][data-stable-id="${stableId}"]`);

test.describe('Rozbalení konverzace', () => {
	test('kliknutí na šipku rozbalí a sbalí členy vlákna', async ({ page }) => {
		await openApp(page, `/mail/${accountId}/ARCHIVE`);

		const parent = archiveRow(page, 'arch-03');
		await expect(parent).toBeVisible();
		await expect(parent).toHaveAttribute('aria-expanded', 'false');
		// The three ARCHIVE messages; the sent, junked and unsent members of the
		// same thread are out of every conversation's scope.
		await expect(parent.getByText('konverzace, 3 zprávy')).toBeAttached();
		// Members are hidden while collapsed.
		await expect(anyArchiveRow(page, 'arch-01')).toHaveCount(0);
		await expect(anyArchiveRow(page, 'arch-02')).toHaveCount(0);

		await parent.locator('[data-expand-toggle]').click();

		await expect(parent).toHaveAttribute('aria-expanded', 'true');
		const member = archiveMember(page, 'arch-02');
		await expect(member).toBeVisible();
		await expect(member).toHaveAttribute('aria-level', '2');
		await expect(archiveMember(page, 'arch-01')).toBeVisible();
		// The user's own reply, the junked and the still-unsent member all stay
		// out — the badge counts 3, and the three child rows (the representative
		// included) are it.
		await expect(anyArchiveRow(page, 'sent-plan-01')).toHaveCount(0);
		await expect(anyArchiveRow(page, 'junk-plan-01')).toHaveCount(0);
		await expect(anyArchiveRow(page, 'draft-plan-01')).toHaveCount(0);
		await expect(page.locator('[role="row"][data-row-kind="member"]')).toHaveCount(3);

		await parent.locator('[data-expand-toggle]').click();

		await expect(parent).toHaveAttribute('aria-expanded', 'false');
		await expect(anyArchiveRow(page, 'arch-02')).toHaveCount(0);
		await expect(page.locator('[role="row"][data-row-kind="member"]')).toHaveCount(0);
	});

	test('rozbalovací prvek je tlačítko s názvem a jde ovládat klávesnicí', async ({ page }) => {
		// The ARIA treegrid contract (aria-expanded on the row + ArrowRight/Left) is
		// not reachable in a screen reader's browse mode, which swallows unmodified
		// arrow keys — so the toggle must also exist as a real, named button.
		await openApp(page, `/mail/${accountId}/ARCHIVE`);

		const toggle = page.getByRole('button', { name: 'Rozbalit konverzaci Re: Plán vydání' });
		await expect(toggle).toBeVisible();
		await expect(toggle).toHaveAttribute('aria-expanded', 'false');

		await toggle.focus();
		await toggle.press('Enter');

		const parent = archiveRow(page, 'arch-03');
		await expect(parent).toHaveAttribute('aria-expanded', 'true');
		await expect(archiveMember(page, 'arch-02')).toBeVisible();
		// Enter on the toggle must not also open the message: the row's own
		// Enter handler has to yield to the button.
		await expect(page).toHaveURL(new RegExp(`/mail/${accountId}/ARCHIVE$`));
		// The label tracks the state, so the control still says what the key did.
		const collapse = page.getByRole('button', { name: 'Sbalit konverzaci Re: Plán vydání' });
		await expect(collapse).toBeVisible();
		// Focus does not stay on it, though. Activating the toggle is the browse-mode
		// path — a screen reader sends Enter as a click — and leaving the cursor here
		// would make it walk the rest of the parent row (status, sender, date, the
		// actions trigger, each naming the same subject) before reaching the message
		// the thread was expanded for. It lands on the oldest member instead.
		//
		// On the sender cell (col 4), not the subject cell: a member's subject cell
		// is deliberately empty, so landing there would announce nothing at all —
		// which would spend the whole point of moving the cursor here. The polite
		// announcement carries the state change.
		await expect(page.locator('[data-cell-target]:focus')).toHaveAttribute('data-col', '4');
		await expect(archiveMember(page, 'arch-01').locator('[data-cell-target]:focus')).toHaveCount(1);

		await collapse.press(' ');

		await expect(parent).toHaveAttribute('aria-expanded', 'false');
		await expect(anyArchiveRow(page, 'arch-02')).toHaveCount(0);
		await expect(page).toHaveURL(new RegExp(`/mail/${accountId}/ARCHIVE$`));
	});

	test('rozbalený rodič neopakuje odesílatele a datum nejnovější zprávy', async ({ page }) => {
		// Expanded, the parent is a conversation header: the newest message it used
		// to stand for now has a child row of its own carrying exactly these two
		// fields. A screen reader reads the row cell by cell, so repeating them here
		// is speech spent on the way into the thread the user just opened.
		await openApp(page, `/mail/${accountId}/ARCHIVE`);

		const parent = archiveRow(page, 'arch-03');
		const parentSender = parent.locator('[data-cell-target][data-col="4"]');
		const parentDate = parent.locator('[data-cell-target][data-col="5"]');
		// Collapsed, the row is all there is — who wrote last and when is what the
		// folder is triaged on, so both stay.
		await expect(parentSender).not.toHaveText('');
		await expect(parentDate).not.toHaveText('');

		await parent.locator('[data-expand-toggle]').click();
		await expect(parent).toHaveAttribute('aria-expanded', 'true');

		await expect(parentSender).toHaveText('');
		await expect(parentDate).toHaveText('');
		// Emptied, not removed and not renamed. The cells are grid columns, so
		// dropping them would renumber the roving navigation — and an aria-label
		// would spend the saving on announcing the emptiness, which is the whole
		// point of leaving them silent.
		await expect(parentSender).toHaveCount(1);
		await expect(parentDate).toHaveCount(1);
		await expect(parentSender).not.toHaveAttribute('aria-label', /./);
		await expect(parentDate).not.toHaveAttribute('aria-label', /./);

		// The same message, as a child row, still carries both.
		const own = archiveMember(page, 'arch-03');
		await expect(own.locator('[data-col="4"]')).not.toHaveText('');
		await expect(own.locator('[data-col="5"]')).not.toHaveText('');

		// Empty must not mean collapsed. The columns belong to the list and the rows
		// are `subgrid`, so an emptied cell cannot shrink its own column any more —
		// but that is exactly the property worth pinning, because the arrangement
		// that preceded it (each row its own grid, the date track carrying a floor)
		// passed here on Windows and failed on CI's Linux fonts by 4.5px: a floor
		// aligns only while every date fits under it, which is a property of the
		// font, not of the layout. Compared against a member rather than against a
		// number written down here, so the assertion says "the same as its
		// children", which is the actual requirement.
		const memberDate = own.locator('[data-col="5"]');
		const parentBox = await parentDate.boundingBox();
		const memberBox = await memberDate.boundingBox();
		expect(parentBox).not.toBeNull();
		expect(memberBox).not.toBeNull();
		expect(parentBox?.x).toBeCloseTo(memberBox?.x ?? 0, 0);
		expect(parentBox?.width).toBeCloseTo(memberBox?.width ?? 0, 0);

		/*
		 * Row heights deliberately differ. This used to assert they matched, back
		 * when a member row carried the thread's subject on its first line and the
		 * counterpart on its second. The subject is gone from member rows — it
		 * belongs to the conversation and repeating it read the same string once
		 * per message — so a member is a single line and stands visibly under the
		 * two-line header. Pinned as a relation, not as pixels: shorter than the
		 * parent, and not collapsed to nothing.
		 */
		const parentRowBox = await parent.boundingBox();
		const memberRowBox = await own.boundingBox();
		expect(memberRowBox?.height ?? 0).toBeLessThan(parentRowBox?.height ?? 0);
		// Not collapsed either, compared against what the row actually holds rather
		// than against the `min-h-8` value written down a second time here — moving
		// that floor should change the row, not fail this assertion by its number.
		const memberSenderBox = await own.locator('[data-col="4"]').boundingBox();
		expect(memberRowBox?.height ?? 0).toBeGreaterThanOrEqual(memberSenderBox?.height ?? 0);
		expect(memberSenderBox?.height ?? 0).toBeGreaterThan(0);

		await parent.locator('[data-expand-toggle]').click();
		await expect(parent).toHaveAttribute('aria-expanded', 'false');
		await expect(parentSender).not.toHaveText('');
		await expect(parentDate).not.toHaveText('');
	});

	test('rozbalené vlákno neopakuje předmět u každé zprávy', async ({ page }) => {
		/*
		 * The other half of the parent-row trim above. The subject belongs to the
		 * conversation, but every member repeated it twice — once in its subject
		 * cell and once inside the name of its actions menu — so a three-message
		 * thread said one string eight times across four rows.
		 *
		 * Counted rather than spot-checked: an assertion per known place would
		 * keep passing when a new one appears, and this defect is precisely "the
		 * same words in one more place than anybody counted".
		 */
		await openApp(page, `/mail/${accountId}/ARCHIVE`);
		await page.getByRole('button', { name: 'Rozbalit konverzaci Re: Plán vydání' }).click();
		await expect(archiveMember(page, 'arch-01')).toBeVisible();

		const grid = conversationGrid(page);
		const mentions = await grid.evaluate((el) => {
			const count = (text: string | null) => (text?.match(/Plán vydání/g) ?? []).length;
			const inRow = (row: Element) =>
				count((row as HTMLElement).innerText) +
				[...row.querySelectorAll('[aria-label]')].reduce(
					(sum, node) => sum + count(node.getAttribute('aria-label')),
					0
				);
			const rows = [...el.querySelectorAll('[role="row"][data-stable-id]')];
			return {
				conversation: rows
					.filter((r) => r.getAttribute('data-row-kind') === 'conversation')
					.reduce((s, r) => s + inRow(r), 0),
				members: rows
					.filter((r) => r.getAttribute('data-row-kind') === 'member')
					.reduce((s, r) => s + inRow(r), 0),
				memberRows: rows.filter((r) => r.getAttribute('data-row-kind') === 'member').length
			};
		});

		expect(mentions.memberRows).toBe(3);
		// Not one mention on any member row, however many members there are.
		expect(mentions.members).toBe(0);
		/*
		 * The conversation row still owns it, in all four places it belongs: the
		 * link text, the expand button, the checkbox and the actions trigger. The
		 * number rather than "more than zero" — dropping it from two of the four
		 * would make the row quieter about what the thread is, and a loose
		 * assertion would call that fine.
		 */
		expect(mentions.conversation).toBe(4);

		// What a member says instead: who, and when. Same shape as its checkbox
		// (selectMemberFrom), from one shared helper, so the two cannot drift.
		const member = archiveMember(page, 'arch-02');
		await expect(member.locator('[data-col="4"]')).toHaveText(/Jana Novak/);
		await expect(
			member.getByRole('button', { name: /^Akce pro zprávu od Jana Novak .*, 21\. 4\./ })
		).toBeVisible();
	});

	test('prázdná buňka předmětu nepolyká pohyb šipkami ani návrat na rodiče', async ({ page }) => {
		/*
		 * A member's subject cell is empty but must stay a cell: `focusGridCell`
		 * resolves a roving move through `[data-cell-target][data-col]` inside the
		 * target row, so a row missing one would swallow ArrowDown from the column
		 * above it and strand the cursor on the parent.
		 */
		await openApp(page, `/mail/${accountId}/ARCHIVE`);
		await page.getByRole('button', { name: 'Rozbalit konverzaci Re: Plán vydání' }).click();
		await expect(archiveMember(page, 'arch-01')).toBeVisible();

		const parentSubject = archiveRow(page, 'arch-03').locator('[data-cell-target][data-col="3"]');
		await parentSubject.focus();
		await expect(parentSubject).toBeFocused();

		await parentSubject.press('ArrowDown');
		await expect(
			archiveMember(page, 'arch-01').locator('[data-cell-target][data-col="3"]:focus')
		).toHaveCount(1);

		/*
		 * And the treegrid contract answers on the member's own anchor. Expanding
		 * leaves the cursor on the sender cell, so ArrowLeft has to climb back to
		 * the parent from there — checking it only from the subject cell would test
		 * a column the user never lands on.
		 */
		const memberSender = archiveMember(page, 'arch-01').locator('[data-cell-target][data-col="4"]');
		await memberSender.focus();
		await memberSender.press('ArrowLeft');
		// On the parent's own anchor, its subject cell — not merely somewhere in
		// the parent row. Landing on its expand cell or its checkbox would still
		// satisfy a bare focus count while announcing "Rozbalení" or "Vybrat
		// konverzaci" instead of what the thread is.
		await expect(
			archiveRow(page, 'arch-03').locator('[data-cell-target][data-col="3"]:focus')
		).toHaveCount(1);
	});

	test('šipky na předmětu rozbalí a sbalí konverzaci', async ({ page }) => {
		await openApp(page, `/mail/${accountId}/ARCHIVE`);

		const parent = archiveRow(page, 'arch-03');
		await expect(parent).toBeVisible();
		const subjectCell = parent.locator('[data-cell-target][data-col="3"]');
		await subjectCell.focus();

		await subjectCell.press('ArrowRight');
		await expect(parent).toHaveAttribute('aria-expanded', 'true');
		await expect(archiveMember(page, 'arch-02')).toBeVisible();
		// Deliberately unlike the toggle button, which steps into the thread: this is
		// the WAI-ARIA treegrid contract, where expanding a node leaves focus on it
		// and a second ArrowRight is what walks into the children. The sighted
		// keyboard user gets the spec; browse mode, which never delivers this key,
		// gets the shortcut.
		await expect(subjectCell).toBeFocused();

		await parent.locator('[data-cell-target][data-col="3"]').press('ArrowLeft');
		await expect(parent).toHaveAttribute('aria-expanded', 'false');
		await expect(anyArchiveRow(page, 'arch-02')).toHaveCount(0);
	});

	test('fokus nese buňka předmětu konverzace, ne odkaz uvnitř ní', async ({ page }) => {
		/*
		 * The same structural fault the flat list carried: the subject cell is the
		 * only one in the row holding both text and a focusable element with that
		 * same text, so a roving tabindex on the link made a screen reader
		 * announce the cell it had entered and then the link inside it — the
		 * subject twice per arrow key. Heard in grouped mode with NVDA. It is
		 * structural rather than timing, so counting focus events cannot catch
		 * it; the invariant that keeps it away is that the cell takes focus while
		 * the anchor stays out of the tab order with href and link role intact.
		 */
		await openApp(page, `/mail/${accountId}/ARCHIVE`);

		const parent = archiveRow(page, 'arch-03');
		await expect(parent).toBeVisible();

		/*
		 * Asserted through what the roving treegrid actually moves focus to. A
		 * locator naming the gridcell directly fails with "element not found"
		 * once the target moves back onto the link — true, but silent about why
		 * it matters.
		 */
		const rovingTarget = parent.locator('[data-cell-target][data-col="3"]');
		await expect(rovingTarget).toHaveAttribute('role', 'gridcell');
		await rovingTarget.focus();
		await expect(rovingTarget).toBeFocused();

		// The anchor keeps href and role so browse mode still activates it, but
		// stays out of the tab order so the cell is what gets announced.
		const link = rovingTarget.locator('a');
		await expect(link).toHaveAttribute('tabindex', '-1');
		await expect(link).toHaveRole('link');
	});

	test('buňky konverzace i člena kreslí focus ring se stejným zaoblením', async ({ page }) => {
		/*
		 * `focusRingInset` is an inset box-shadow, so the ring's corners are
		 * whatever `border-radius` sits on the element drawing it. The subject
		 * cell had lost that class to the link inside it when the roving tabindex
		 * moved out, so one cell per row flashed a square ring while its
		 * neighbours flashed rounded ones.
		 *
		 * The cross-folder member is the row that makes this worth asserting
		 * twice: select, expand and actions are empty divs there — nothing to
		 * tick, nothing to expand, nothing to act on — so those three draw the
		 * ring themselves instead of handing it to a native control.
		 */
		await setMockFlags(page, { inboxThreadMember: true });
		await openApp(page, `/mail/${accountId}/ARCHIVE`);

		const parent = archiveRow(page, 'arch-03');
		await expect(parent).toBeVisible();
		await parent.locator('[data-expand-toggle]').click();

		const member = archiveMember(page, 'inbox-plan-01');
		await expect(member).toBeVisible();

		const radiiOf = (row: Locator, cols: number[]) =>
			row.evaluate(
				(el, wanted) =>
					wanted.map((col) => {
						const cell = el.querySelector(`[data-cell-target][data-col="${col}"]`);
						return cell ? getComputedStyle(cell).borderRadius : null;
					}),
				cols
			);

		// A conversation row: status, subject, sender, date. Its select, expand
		// and actions columns hold a checkbox, a toggle and a menu trigger, each
		// carrying a radius of its own — those are not this rule's business.
		const parentRadii = await radiiOf(parent, [2, 3, 4, 5]);
		expect(parentRadii).not.toContain(null);
		expect(new Set(parentRadii).size).toBe(1);
		// Equality alone would also hold with every corner square, which is the
		// state this test exists to keep out.
		expect(parentRadii[0]).not.toBe('0px');

		// The cross-folder member: every one of its seven columns is a cell that
		// draws its own ring.
		const memberRadii = await radiiOf(member, [0, 1, 2, 3, 4, 5, 6]);
		expect(memberRadii).not.toContain(null);
		expect(new Set(memberRadii).size).toBe(1);
		expect(memberRadii[0]).not.toBe('0px');
	});

	test('fokus nese buňka odesílatele členské řádky, ne odkaz uvnitř ní', async ({ page }) => {
		/*
		 * The third row of the same structure the two subject columns already
		 * dropped, and the one that outlived both fixes. A member row is read
		 * through its SENDER cell — readingAnchorCol sends the cursor there,
		 * because a member's subject cell is deliberately empty — and that cell
		 * holds the counterpart's name together with a link carrying the same
		 * name, which is exactly the pair that made a screen reader announce the
		 * cell and then the element inside it.
		 *
		 * Unlike the two subject columns, this one was NOT heard: it is derived
		 * from a structure whose fix listening settled twice. So what is asserted
		 * here is the structure and the activation, never the announcement.
		 */
		await openApp(page, `/mail/${accountId}/ARCHIVE`);

		const parent = archiveRow(page, 'arch-03');
		await expect(parent).toBeVisible();
		await parent.locator('[data-expand-toggle]').click();

		const member = archiveMember(page, 'arch-02');
		await expect(member).toBeVisible();

		/*
		 * Through what the treegrid actually moves focus to, like the two tests
		 * above: naming the gridcell directly fails with "element not found" the
		 * moment the target slips back onto the link, which is true and silent
		 * about why it matters.
		 */
		const rovingTarget = member.locator('[data-cell-target][data-col="4"]');
		await expect(rovingTarget).toHaveAttribute('role', 'gridcell');
		await rovingTarget.focus();
		await expect(rovingTarget).toBeFocused();

		// The anchor keeps href and role so browse mode still activates it, and
		// stays out of the tab order so the cell is what gets announced.
		const link = rovingTarget.locator('a');
		await expect(link).toHaveAttribute('tabindex', '-1');
		await expect(link).toHaveRole('link');

		// And the cell that now holds focus still opens the member's message:
		// moving the tabindex must not cost the row its activation.
		await rovingTarget.press('Enter');
		await page.waitForURL(`**/mail/${accountId}/ARCHIVE/arch-02`);
	});

	test('otevření člena rozbaleného vlákna přejde na jeho zprávu', async ({ page }) => {
		await openApp(page, `/mail/${accountId}/ARCHIVE`);

		const parent = archiveRow(page, 'arch-03');
		await expect(parent).toBeVisible();
		await parent.locator('[data-expand-toggle]').click();

		const member = archiveMember(page, 'arch-02');
		await expect(member).toBeVisible();
		// Member rows follow the same click model as the parents: a click opens.
		await member.click();

		await page.waitForURL(`**/mail/${accountId}/ARCHIVE/arch-02`);
	});

	test('rozbalovací tlačítko vlákno jen rozbalí, konverzaci neotevře', async ({ page }) => {
		await openApp(page, `/mail/${accountId}/ARCHIVE`);

		// The one exemption from "a click opens": the toggle is a real control and
		// keeps its click to itself, or expanding a thread would navigate away.
		const parent = archiveRow(page, 'arch-03');
		await expect(parent).toBeVisible();
		await parent.locator('[data-expand-toggle]').click();

		await expect(parent).toHaveAttribute('aria-expanded', 'true');
		await expect(archiveMember(page, 'arch-02')).toBeVisible();
		await expect(page).toHaveURL(new RegExp(`/mail/${accountId}/ARCHIVE$`));
	});

	test('smazaný člen vlákna se nepočítá do odznaku ani se nezobrazí mezi členy', async ({
		page
	}) => {
		// The badge and the expanded rows come from one server-side scope, so a
		// trashed reply must be absent from both. Without the seed the trash
		// fixtures share no subject with a live thread and this stays untested.
		await setMockFlags(page, { trashThreadMember: true });
		await openApp(page, `/mail/${accountId}/ARCHIVE`);

		const parent = archiveRow(page, 'arch-03');
		await expect(parent).toBeVisible();
		// Still 3 — the two trashed replies do not raise the count.
		await expect(parent.getByText('konverzace, 3 zprávy')).toBeAttached();

		await parent.locator('[data-expand-toggle]').click();
		await expect(parent).toHaveAttribute('aria-expanded', 'true');

		await expect(archiveMember(page, 'arch-01')).toBeVisible();
		await expect(anyArchiveRow(page, 'trash-plan-01')).toHaveCount(0);
		await expect(anyArchiveRow(page, 'trash-plan-02')).toHaveCount(0);
	});

	test('vlastní odpověď z Odeslaných není součástí konverzace', async ({ page }) => {
		// Sent is one of the folders a conversation never spans: a thread in a
		// receiving folder is about the mail that arrived, and a folder-scoped bulk
		// action could not reach the sent copy anyway. sent-plan-01 belongs to this
		// very thread, so it is the seam that proves the rule.
		await openApp(page, `/mail/${accountId}/ARCHIVE`);

		const parent = archiveRow(page, 'arch-03');
		await expect(parent.getByText('konverzace, 3 zprávy')).toBeAttached();
		await parent.locator('[data-expand-toggle]').click();
		await expect(parent).toHaveAttribute('aria-expanded', 'true');

		await expect(anyArchiveRow(page, 'sent-plan-01')).toHaveCount(0);

		// And the Sent view itself stays folder-scoped, like Trash and Junk: the
		// reply groups with the sent messages, never with the archived thread.
		await openApp(page, `/mail/${accountId}/SENT`);

		const sentRow = conversationGrid(page).locator(
			'[role="row"][data-row-kind="conversation"][data-stable-id="sent-plan-01"]'
		);
		await expect(sentRow).toBeVisible();
		await expect(sentRow.getByText('konverzace,')).toHaveCount(0);
		await expect(page.locator('[role="row"][data-stable-id="arch-03"]')).toHaveCount(0);
	});

	test('konverzace v koši zůstane folder-scoped a neukáže členy z jiných složek', async ({
		page
	}) => {
		// The Trash view must only ever show what is actually in the trash —
		// expanding a conversation there must not pull the live members of the same
		// thread in behind it.
		await setMockFlags(page, { trashThreadMember: true });
		await openApp(page, `/mail/${accountId}/TRASH`);

		const grid = conversationGrid(page);
		const parent = grid.locator(
			'[role="row"][data-row-kind="conversation"][data-stable-id="trash-plan-02"]'
		);
		await expect(parent).toBeVisible();
		// Folder-scoped count: the two trashed replies, nothing else.
		await expect(parent.getByText('konverzace, 2 zprávy')).toBeAttached();

		await parent.locator('[data-expand-toggle]').click();
		await expect(parent).toHaveAttribute('aria-expanded', 'true');

		await expect(
			grid.locator('[role="row"][data-row-kind="member"][data-stable-id="trash-plan-01"]')
		).toBeVisible();
		await expect(grid.locator('[role="row"][data-stable-id="arch-01"]')).toHaveCount(0);
		await expect(grid.locator('[role="row"][data-stable-id="arch-02"]')).toHaveCount(0);
		await expect(grid.locator('[role="row"][data-stable-id="arch-03"]')).toHaveCount(0);
		await expect(grid.locator('[role="row"][data-stable-id="sent-plan-01"]')).toHaveCount(0);
	});

	test('člen z jiné složky se ukáže označený složkou a otevře se pod ní', async ({ page }) => {
		// The route folder is what the layout header, the back link and the move
		// control read. Opening the received reply as .../ARCHIVE/inbox-plan-01
		// would describe it as an archived message and offer moving Inbox -> Inbox.
		await setMockFlags(page, { inboxThreadMember: true });
		await openApp(page, `/mail/${accountId}/ARCHIVE`);

		const parent = archiveRow(page, 'arch-03');
		await expect(parent).toBeVisible();
		// The inbox copy is a member of the conversation, so it raises the badge.
		await expect(parent.getByText('konverzace, 4 zprávy')).toBeAttached();
		await parent.locator('[data-expand-toggle]').click();

		const inboxMember = archiveMember(page, 'inbox-plan-01');
		await expect(inboxMember).toBeVisible();
		await expect(inboxMember).toHaveAttribute('aria-level', '2');
		await expect(inboxMember.getByText('Ve složce Doručené')).toBeAttached();

		await inboxMember.click();

		await page.waitForURL(`**/mail/${accountId}/INBOX/inbox-plan-01`);
	});
});

test.describe('Výběr jednotlivých zpráv ve vlákně', () => {
	const memberBox = (page: Page, stableId: string) =>
		archiveMember(page, stableId).locator('input[type="checkbox"]');
	const parentBox = (page: Page, stableId: string) =>
		archiveRow(page, stableId).locator('input[type="checkbox"]');

	test('člen vlákna má vlastní políčko, kopie z jiné složky říká proč ne', async ({ page }) => {
		// Every message of a thread carries the same subject, so the member boxes
		// are named by counterpart and timestamp. arch-01 and arch-03 are both from
		// Petr Marek on one day, which the list date format collapses to a single
		// string — the clock is what keeps their labels apart, so the assertion is
		// that the three differ, not what any one of them says (the rendered time
		// follows the runner's timezone). The inbox copy is out of a folder-scoped
		// action's reach and says so instead of leaving a silent, empty cell where
		// a screen reader expects a checkbox.
		await setMockFlags(page, { inboxThreadMember: true });
		await openApp(page, `/mail/${accountId}/ARCHIVE`);

		const parent = archiveRow(page, 'arch-03');
		await parent.locator('[data-expand-toggle]').click();
		await expect(archiveMember(page, 'arch-02')).toBeVisible();

		await expect(
			page.getByRole('checkbox', { name: /^Vybrat zprávu od Jana Novak <jana@example\.com>, / })
		).toBeVisible();
		const memberLabels = await page
			.locator('[role="row"][data-row-kind="member"] input[type="checkbox"]')
			.evaluateAll((boxes) => boxes.map((box) => box.getAttribute('aria-label') ?? ''));
		expect(memberLabels).toHaveLength(3);
		expect(
			memberLabels.every((label) => /^Vybrat zprávu od .+, .*\d{1,2}:\d{2}$/.test(label))
		).toBe(true);
		expect(new Set(memberLabels).size).toBe(3);
		// The row shows the same timestamp the label announces.
		const memberDate = await archiveMember(page, 'arch-01').locator('[data-col="5"]').innerText();
		expect(memberLabels.some((label) => label.endsWith(memberDate.trim()))).toBe(true);
		await expect(memberBox(page, 'inbox-plan-01')).toHaveCount(0);
		await expect(
			archiveMember(page, 'inbox-plan-01').getByRole('gridcell', {
				name: 'Vybrat nelze, zpráva je ve složce Doručené'
			})
		).toBeVisible();
	});

	test('nejnovější zpráva má vlastní řádek pod konverzací', async ({ page }) => {
		// The parent row shows the newest message but stands for the conversation.
		// Without a child row of its own it was the one message that could not be
		// ticked alone, so an expanded thread lists it like the rest.
		await openApp(page, `/mail/${accountId}/ARCHIVE`);

		const parent = archiveRow(page, 'arch-03');
		await parent.locator('[data-expand-toggle]').click();

		const own = archiveMember(page, 'arch-03');
		await expect(own).toBeVisible();
		await expect(own).toHaveAttribute('aria-level', '2');
		// The badge counts three, and three child rows now account for it.
		await expect(parent.getByText('konverzace, 3 zprávy')).toBeAttached();

		await memberBox(page, 'arch-03').check();

		const toolbar = page.getByRole('toolbar', { name: 'Hromadné akce' });
		await expect(toolbar.getByText('1 vybraná zpráva')).toBeVisible();
		await expect(parentBox(page, 'arch-03')).toHaveAttribute('aria-checked', 'mixed');

		await toolbar.getByRole('button', { name: 'Smazat vybrané' }).click();

		// Only the newest message goes; arch-02 becomes the row the thread shows.
		await expect(archiveRow(page, 'arch-02')).toBeVisible();
		await expect(archiveRow(page, 'arch-03')).toHaveCount(0);
	});

	test('zaškrtnutí jednoho člena vybere jen jeho a smaže jen jeho', async ({ page }) => {
		await openApp(page, `/mail/${accountId}/ARCHIVE`);

		const parent = archiveRow(page, 'arch-03');
		await parent.locator('[data-expand-toggle]').click();
		await expect(archiveMember(page, 'arch-02')).toBeVisible();
		await memberBox(page, 'arch-02').check();

		const toolbar = page.getByRole('toolbar', { name: 'Hromadné akce' });
		await expect(toolbar.getByText('1 vybraná zpráva')).toBeVisible();
		// Part of the thread ticked — the parent must not claim the whole of it.
		await expect(parentBox(page, 'arch-03')).toHaveAttribute('aria-checked', 'mixed');

		await toolbar.getByRole('button', { name: 'Smazat vybrané' }).click();

		// Only the ticked reply leaves the folder; the thread stays, one shorter,
		// with its other members untouched.
		await expect(archiveMember(page, 'arch-02')).toHaveCount(0);
		await expect(archiveRow(page, 'arch-03')).toBeVisible();
		await expect(archiveRow(page, 'arch-03').getByText('konverzace, 2 zprávy')).toBeAttached();
		await expect(archiveMember(page, 'arch-01')).toBeVisible();
	});

	test('odškrtnutí člena vybrané konverzace nechá zbytek vlákna vybraný', async ({ page }) => {
		await openApp(page, `/mail/${accountId}/ARCHIVE`);

		const parent = archiveRow(page, 'arch-03');
		await parent.locator('[data-expand-toggle]').click();
		await expect(archiveMember(page, 'arch-01')).toBeVisible();
		await parentBox(page, 'arch-03').check();

		// Selecting the conversation ticks its members in the folder in view.
		await expect(memberBox(page, 'arch-01')).toBeChecked();
		await expect(memberBox(page, 'arch-02')).toBeChecked();
		await expect(memberBox(page, 'arch-03')).toBeChecked();

		await memberBox(page, 'arch-01').uncheck();

		const toolbar = page.getByRole('toolbar', { name: 'Hromadné akce' });
		// The newest message plus the reply that stayed ticked.
		await expect(toolbar.getByText('2 vybrané zprávy')).toBeVisible();
		await expect(parentBox(page, 'arch-03')).toHaveAttribute('aria-checked', 'mixed');

		await toolbar.getByRole('button', { name: 'Smazat vybrané' }).click();

		// The unticked message is the one left behind, and it becomes the row.
		await expect(archiveRow(page, 'arch-01')).toBeVisible();
		await expect(archiveRow(page, 'arch-03')).toHaveCount(0);
		await expect(archiveMember(page, 'arch-02')).toHaveCount(0);
	});

	test('opětovné zaškrtnutí všech členů se sloučí zpět na výběr konverzace', async ({ page }) => {
		// A fully ticked thread has to fall back to the conversation-level
		// selection: that is the state a collapsed row can still show, and what the
		// toolbar counts.
		await openApp(page, `/mail/${accountId}/ARCHIVE`);

		const parent = archiveRow(page, 'arch-03');
		await parent.locator('[data-expand-toggle]').click();
		await expect(archiveMember(page, 'arch-01')).toBeVisible();
		await parentBox(page, 'arch-03').check();
		await memberBox(page, 'arch-01').uncheck();

		const toolbar = page.getByRole('toolbar', { name: 'Hromadné akce' });
		await expect(toolbar.getByText('2 vybrané zprávy')).toBeVisible();

		await memberBox(page, 'arch-01').check();

		await expect(toolbar.getByText('1 vybraná konverzace')).toBeVisible();
		await expect(parentBox(page, 'arch-03')).toBeChecked();
		await expect(parentBox(page, 'arch-03')).toHaveAttribute('aria-checked', 'true');
	});
});

test.describe('Hromadné akce nad konverzacemi', () => {
	test('smazání vybrané konverzace zasáhne členy v aktuální složce, kopii jinde nechá být', async ({
		page
	}) => {
		// The inbox copy is a counted member of this conversation, which is what
		// makes it the right probe: a bulk delete fired from ARCHIVE still must not
		// reach it, even though the row it was fired from counts it.
		await setMockFlags(page, { inboxThreadMember: true });
		await openApp(page, `/mail/${accountId}/ARCHIVE`);

		const parent = archiveRow(page, 'arch-03');
		await expect(parent).toBeVisible();
		await parent.locator('input[type="checkbox"]').check();

		const toolbar = page.getByRole('toolbar', { name: 'Hromadné akce' });
		await expect(toolbar.getByText('1 vybraná konverzace')).toBeVisible();
		await toolbar.getByRole('button', { name: 'Smazat vybrané' }).click();

		// ARCHIVE is not the trash folder, so the delete moves the conversation's
		// ARCHIVE members out — the folder empties and the treegrid unmounts.
		await expect(conversationGrid(page)).toHaveCount(0);
		await expect(page.locator('[role="row"][data-stable-id="arch-03"]')).toHaveCount(0);

		// Folder-scoped semantics: the received reply in the same thread must
		// survive a bulk delete fired from the ARCHIVE view.
		await openApp(page, `/mail/${accountId}/INBOX`);
		await expect(
			conversationGrid(page).locator('[role="row"][data-stable-id="inbox-plan-01"]')
		).toBeVisible();
	});

	test('přesun vybrané konverzace přesune členy v aktuální složce', async ({ page }) => {
		await openApp(page, `/mail/${accountId}/ARCHIVE`);

		const parent = archiveRow(page, 'arch-03');
		await expect(parent).toBeVisible();
		await parent.locator('input[type="checkbox"]').check();

		const toolbar = page.getByRole('toolbar', { name: 'Hromadné akce' });
		await toolbar.getByRole('button', { name: 'Přesunout vybrané' }).click();
		await page.getByRole('menuitem', { name: 'Spam', exact: true }).click();

		await expect(conversationGrid(page)).toHaveCount(0);
		await expect(page.locator('[role="row"][data-stable-id="arch-03"]')).toHaveCount(0);
	});
});

test.describe('Řádkové menu Akce v seskupeném režimu', () => {
	test('konverzace má menu Akce a smazání z něj zasáhne členy v aktuální složce', async ({
		page
	}) => {
		// Same probe as the bulk-bar test above: the inbox copy is a counted member
		// of this conversation, so it proves the row menu keeps the same
		// folder-scoped semantics the bulk bar has instead of reaching the whole
		// thread.
		await setMockFlags(page, { inboxThreadMember: true });
		await openApp(page, `/mail/${accountId}/ARCHIVE`);

		const parent = archiveRow(page, 'arch-03');
		await expect(parent).toBeVisible();

		// A conversation row names itself as such — "Akce pro zprávu" would promise
		// the menu acts on one message, which for read/move/delete it does not.
		await parent
			.getByRole('button', { name: 'Akce pro konverzaci Re: Plán vydání', exact: true })
			.click();
		await page.getByRole('menu').getByRole('menuitem', { name: 'Smazat' }).click();

		await expect(conversationGrid(page)).toHaveCount(0);
		await expect(page.locator('[role="row"][data-stable-id="arch-03"]')).toHaveCount(0);

		await openApp(page, `/mail/${accountId}/INBOX`);
		await expect(
			conversationGrid(page).locator('[role="row"][data-stable-id="inbox-plan-01"]')
		).toBeVisible();
	});

	test('člen vlákna má vlastní menu a smaže jen sebe', async ({ page }) => {
		await openApp(page, `/mail/${accountId}/ARCHIVE`);

		const parent = archiveRow(page, 'arch-03');
		await expect(parent).toBeVisible();
		await parent.locator('[data-expand-toggle]').click();

		const member = archiveMember(page, 'arch-01');
		await expect(member).toBeVisible();
		await member.getByRole('button', { name: /^Akce pro zprávu/ }).click();
		await page.getByRole('menu').getByRole('menuitem', { name: 'Smazat' }).click();

		// Only that message goes; the conversation and its other members stay.
		await expect(archiveMember(page, 'arch-01')).toHaveCount(0);
		await expect(archiveRow(page, 'arch-03')).toBeVisible();
	});

	test('člen z jiné složky menu nemá a buňka říká proč', async ({ page }) => {
		await setMockFlags(page, { inboxThreadMember: true });
		await openApp(page, `/mail/${accountId}/ARCHIVE`);

		const parent = archiveRow(page, 'arch-03');
		await expect(parent).toBeVisible();
		await parent.locator('[data-expand-toggle]').click();

		// Actions here are folder-scoped, so a member living elsewhere gets no
		// menu — but the cell is named rather than left as a silent gap, the same
		// way its selection cell is.
		const inboxMember = archiveMember(page, 'inbox-plan-01');
		await expect(inboxMember).toBeVisible();
		await expect(inboxMember.getByRole('button', { name: /^Akce pro/ })).toHaveCount(0);
		await expect(
			inboxMember.getByRole('gridcell', { name: 'Bez akcí, zpráva je ve složce Doručené' })
		).toBeVisible();
	});
});

test.describe('Řádkové menu Akce v koši', () => {
	test('smazání konverzace z řádkového menu v koši se potvrzuje', async ({ page }) => {
		/*
		 * The destructive path this menu must not shortcut. In the trash a delete
		 * is an expunge, and the confirmation is driven by the folder role the
		 * grouped view knows — which is exactly why these actions go through
		 * conversationBulk instead of the flat pipeline the row menu uses
		 * elsewhere.
		 */
		await setMockFlags(page, { trashThreadMember: true });
		await openApp(page, `/mail/${accountId}/TRASH`);

		const parent = conversationGrid(page).locator(
			'[role="row"][data-row-kind="conversation"][data-stable-id="trash-plan-02"]'
		);
		await expect(parent).toBeVisible();

		await parent.getByRole('button', { name: /^Akce pro konverzaci/ }).click();
		await page.getByRole('menu').getByRole('menuitem', { name: 'Smazat' }).click();

		const dialog = page.getByRole('dialog', { name: 'Trvalé smazání' });
		await expect(dialog).toBeVisible();
		// Both trashed members are counted, not just the representative.
		await expect(dialog.getByText('Trvale smazat 2 zprávy z koše?')).toBeVisible();

		await dialog.getByRole('button', { name: 'Smazat trvale' }).click();
		await expect(page.locator('[role="row"][data-stable-id="trash-plan-02"]')).toHaveCount(0);
	});
});

test('klávesnice dojde na buňku akcí a Enter tam otevře menu místo konverzace', async ({
	page
}) => {
	// The reason the column exists: without it the row's actions were reachable
	// only by opening the message. End jumps to the last column, and Enter there
	// must open the menu — the row-open handler has to stay out of that cell.
	await openApp(page, `/mail/${accountId}/ARCHIVE`);

	const parent = archiveRow(page, 'arch-03');
	await expect(parent).toBeVisible();
	await parent.locator('[data-cell-target][data-col="3"]').focus();

	await page.keyboard.press('End');
	await expect(parent.locator('[data-cell-target]:focus')).toHaveAttribute('data-col', '6');

	await page.keyboard.press('Enter');
	await expect(page.getByRole('menu')).toBeVisible();
	await expect(page).toHaveURL(/\/mail\/1\/ARCHIVE$/);
});

test('samostatná zpráva má menu pojmenované jako zpráva, ne jako konverzace', async ({ page }) => {
	// Same rule as the checkbox label: a row holding one message is a message.
	// Calling it a conversation promises a thread that is not there and tells a
	// screen-reader user that read/move/delete will reach more than one mail.
	await openApp(page, `/mail/${accountId}/${encodeURIComponent(folderName)}`);

	await expect(
		page.getByRole('button', { name: 'Akce pro zprávu Projektové podklady', exact: true })
	).toBeVisible();
	await expect(page.getByRole('button', { name: /^Akce pro konverzaci/ })).toHaveCount(0);

	// The ARCHIVE row really is a 4-message thread, so there it stays a conversation.
	await openApp(page, `/mail/${accountId}/ARCHIVE`);
	await expect(
		page.getByRole('button', { name: 'Akce pro konverzaci Re: Plán vydání', exact: true })
	).toBeVisible();
});

test.describe('Fokus po řádkové akci v seskupeném režimu', () => {
	test('smazání konverzace z menu posadí fokus na sousední řádek, ne na body', async ({ page }) => {
		// The whole point of the column is keyboard reach; a delete that drops the
		// reading cursor on <body> takes it away again. Same contract the flat list
		// keeps via listFocusRestore.
		await openApp(page, `/mail/${accountId}/${encodeURIComponent(folderName)}`);

		const grid = conversationGrid(page);
		const rows = rowsOf(grid);
		const firstId = await rows.first().getAttribute('data-stable-id');
		const secondId = await rows.nth(1).getAttribute('data-stable-id');
		if (!firstId || !secondId) throw new Error('Fixture musí mít aspoň dva řádky.');

		await grid
			.locator(`[role="row"][data-stable-id="${firstId}"]`)
			.getByRole('button', { name: /^Akce pro/ })
			.click();
		await page.getByRole('menu').getByRole('menuitem', { name: 'Smazat' }).click();

		await expect(grid.locator(`[role="row"][data-stable-id="${firstId}"]`)).toHaveCount(0);

		// Focus lands on the neighbouring row, on its subject cell — the reading
		// anchor, not the actions column of some other message.
		const focused = page.locator('[data-cell-target]:focus');
		await expect(focused).toHaveCount(1);
		await expect(focused).toHaveAttribute('data-col', '3');
		await expect(
			grid.locator(`[role="row"][data-stable-id="${secondId}"] [data-cell-target]:focus`)
		).toHaveCount(1);
	});

	test('hvězdička z menu členského řádku nechá fokus na tom řádku', async ({ page }) => {
		// The member rows unmount for a moment on every reload (the member cache is
		// cleared before the expanded threads refetch), so even a non-removing
		// action costs the focused trigger.
		await openApp(page, `/mail/${accountId}/ARCHIVE`);

		const parent = archiveRow(page, 'arch-03');
		await expect(parent).toBeVisible();
		await parent.locator('[data-expand-toggle]').click();

		const member = archiveMember(page, 'arch-01');
		await expect(member).toBeVisible();
		await member.getByRole('button', { name: /^Akce pro zprávu/ }).click();
		await page.getByRole('menu').getByRole('menuitem', { name: 'Označit hvězdičkou' }).click();

		// The row survives, so focus comes back to it — and stays in the actions
		// column the user was in, because it still names the same message.
		await expect(archiveMember(page, 'arch-01').locator('[data-cell-target]:focus')).toHaveCount(1);
		await expect(page.locator('[data-cell-target]:focus')).toHaveAttribute('data-col', '6');
	});
});
