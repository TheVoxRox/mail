import { expect, test, type Page } from '@playwright/test';
import { waitForShell } from '../e2e-helpers';

const accountId = 1;
const folderName = 'INBOX';

test.beforeEach(async ({ page }) => {
	await page.addInitScript(() => {
		window.localStorage.setItem('mail.locale', 'cs');
		// Opt into the conversation-grouped folder view (Phase 2).
		window.localStorage.setItem('mail.messageGrouping', 'grouped');
	});
});

test.describe('Konverzační seskupení', () => {
	test('seskupený režim vykreslí seznam konverzací místo plochého seznamu', async ({ page }) => {
		await page.goto(`/mail/${accountId}/${encodeURIComponent(folderName)}`);
		await waitForShell(page);

		const grid = page.getByRole('treegrid', { name: 'Seznam konverzací' });
		await expect(grid).toBeVisible();
		// The flat message list must not be mounted at the same time.
		await expect(page.getByRole('grid', { name: 'Seznam zpráv' })).toHaveCount(0);
		await expect(grid.locator('[role="row"][data-stable-id]').first()).toBeVisible();
	});

	test('zaškrtávací políčko říká zpráva u samostatné zprávy a konverzace až u vlákna', async ({
		page
	}) => {
		// A one-message row is a message; calling it a conversation promises a
		// thread that is not there and misdescribes what a bulk action will reach.
		await page.goto(`/mail/${accountId}/${encodeURIComponent(folderName)}`);
		await waitForShell(page);

		await expect(
			page.getByRole('checkbox', { name: 'Vybrat zprávu Projektové podklady', exact: true })
		).toBeVisible();
		await expect(page.getByRole('checkbox', { name: /^Vybrat konverzaci/ })).toHaveCount(0);

		// The ARCHIVE row really is a 4-message conversation.
		await page.goto(`/mail/${accountId}/ARCHIVE`);
		await waitForShell(page);
		await expect(
			page.getByRole('checkbox', { name: 'Vybrat konverzaci Re: Plán vydání', exact: true })
		).toBeVisible();
	});

	test('jednoklik otevře konverzaci na reprezentativní zprávě', async ({ page }) => {
		await page.goto(`/mail/${accountId}/${encodeURIComponent(folderName)}`);
		await waitForShell(page);

		const grid = page.getByRole('treegrid', { name: 'Seznam konverzací' });
		const firstRow = grid.locator('[role="row"][data-stable-id]').first();
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
		await page.addInitScript(() => {
			window.localStorage.setItem('mail.readingPane', 'off');
		});
		await page.goto(`/mail/${accountId}/${encodeURIComponent(folderName)}`);
		await waitForShell(page);

		const grid = page.getByRole('treegrid', { name: 'Seznam konverzací' });
		const firstRow = grid.locator('[role="row"][data-stable-id]').first();
		await expect(firstRow).toBeVisible();
		const stableId = await firstRow.getAttribute('data-stable-id');
		const subjectLink = firstRow.locator('[data-cell-target][data-col="3"]');
		await subjectLink.focus();

		// The reader keeps Enter for browse-mode navigation and activates the
		// subject link instead; the click it sends may carry no click count.
		await subjectLink.evaluate((el: HTMLElement) => el.click());

		await page.waitForURL(
			`**/mail/${accountId}/${encodeURIComponent(folderName)}/${encodeURIComponent(stableId ?? '')}`
		);
	});

	test('zaškrtávátko vybere konverzaci a neotevře ji', async ({ page }) => {
		await page.addInitScript(() => {
			window.localStorage.setItem('mail.readingPane', 'off');
		});
		await page.goto(`/mail/${accountId}/${encodeURIComponent(folderName)}`);
		await waitForShell(page);

		const grid = page.getByRole('treegrid', { name: 'Seznam konverzací' });
		const firstRow = grid.locator('[role="row"][data-stable-id]').first();
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
	page
		.getByRole('treegrid', { name: 'Seznam konverzací' })
		.locator(`[role="row"][data-row-kind="conversation"][data-stable-id="${stableId}"]`);

const archiveMember = (page: Page, stableId: string) =>
	page
		.getByRole('treegrid', { name: 'Seznam konverzací' })
		.locator(`[role="row"][data-row-kind="member"][data-stable-id="${stableId}"]`);

/** Either kind — for asserting a message has no row in the view at all. */
const anyArchiveRow = (page: Page, stableId: string) =>
	page
		.getByRole('treegrid', { name: 'Seznam konverzací' })
		.locator(`[role="row"][data-stable-id="${stableId}"]`);

test.describe('Rozbalení konverzace', () => {
	test('kliknutí na šipku rozbalí a sbalí členy vlákna', async ({ page }) => {
		await page.goto(`/mail/${accountId}/ARCHIVE`);
		await waitForShell(page);

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
		await page.goto(`/mail/${accountId}/ARCHIVE`);
		await waitForShell(page);

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
		// The label tracks the state, so a screen reader is told what the key did.
		const collapse = page.getByRole('button', { name: 'Sbalit konverzaci Re: Plán vydání' });
		await expect(collapse).toBeFocused();

		await collapse.press(' ');

		await expect(parent).toHaveAttribute('aria-expanded', 'false');
		await expect(anyArchiveRow(page, 'arch-02')).toHaveCount(0);
		await expect(page).toHaveURL(new RegExp(`/mail/${accountId}/ARCHIVE$`));
	});

	test('šipky na předmětu rozbalí a sbalí konverzaci', async ({ page }) => {
		await page.goto(`/mail/${accountId}/ARCHIVE`);
		await waitForShell(page);

		const parent = archiveRow(page, 'arch-03');
		await expect(parent).toBeVisible();
		const subjectCell = parent.locator('[data-cell-target][data-col="3"]');
		await subjectCell.focus();

		await subjectCell.press('ArrowRight');
		await expect(parent).toHaveAttribute('aria-expanded', 'true');
		await expect(archiveMember(page, 'arch-02')).toBeVisible();

		await parent.locator('[data-cell-target][data-col="3"]').press('ArrowLeft');
		await expect(parent).toHaveAttribute('aria-expanded', 'false');
		await expect(anyArchiveRow(page, 'arch-02')).toHaveCount(0);
	});

	test('otevření člena rozbaleného vlákna přejde na jeho zprávu', async ({ page }) => {
		await page.goto(`/mail/${accountId}/ARCHIVE`);
		await waitForShell(page);

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
		await page.goto(`/mail/${accountId}/ARCHIVE`);
		await waitForShell(page);

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
		await page.addInitScript(() => {
			window.localStorage.setItem('mail.e2e.trashThreadMember', '1');
		});
		await page.goto(`/mail/${accountId}/ARCHIVE`);
		await waitForShell(page);

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
		await page.goto(`/mail/${accountId}/ARCHIVE`);
		await waitForShell(page);

		const parent = archiveRow(page, 'arch-03');
		await expect(parent.getByText('konverzace, 3 zprávy')).toBeAttached();
		await parent.locator('[data-expand-toggle]').click();
		await expect(parent).toHaveAttribute('aria-expanded', 'true');

		await expect(anyArchiveRow(page, 'sent-plan-01')).toHaveCount(0);

		// And the Sent view itself stays folder-scoped, like Trash and Junk: the
		// reply groups with the sent messages, never with the archived thread.
		await page.goto(`/mail/${accountId}/SENT`);
		await waitForShell(page);

		const sentRow = page
			.getByRole('treegrid', { name: 'Seznam konverzací' })
			.locator('[role="row"][data-row-kind="conversation"][data-stable-id="sent-plan-01"]');
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
		await page.addInitScript(() => {
			window.localStorage.setItem('mail.e2e.trashThreadMember', '1');
		});
		await page.goto(`/mail/${accountId}/TRASH`);
		await waitForShell(page);

		const grid = page.getByRole('treegrid', { name: 'Seznam konverzací' });
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
		await page.addInitScript(() => {
			window.localStorage.setItem('mail.e2e.inboxThreadMember', '1');
		});
		await page.goto(`/mail/${accountId}/ARCHIVE`);
		await waitForShell(page);

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
		await page.addInitScript(() => {
			window.localStorage.setItem('mail.e2e.inboxThreadMember', '1');
		});
		await page.goto(`/mail/${accountId}/ARCHIVE`);
		await waitForShell(page);

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
		await page.goto(`/mail/${accountId}/ARCHIVE`);
		await waitForShell(page);

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
		await page.goto(`/mail/${accountId}/ARCHIVE`);
		await waitForShell(page);

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
		await page.goto(`/mail/${accountId}/ARCHIVE`);
		await waitForShell(page);

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
		await page.goto(`/mail/${accountId}/ARCHIVE`);
		await waitForShell(page);

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
		await page.addInitScript(() => {
			window.localStorage.setItem('mail.e2e.inboxThreadMember', '1');
		});
		await page.goto(`/mail/${accountId}/ARCHIVE`);
		await waitForShell(page);

		const parent = archiveRow(page, 'arch-03');
		await expect(parent).toBeVisible();
		await parent.locator('input[type="checkbox"]').check();

		const toolbar = page.getByRole('toolbar', { name: 'Hromadné akce' });
		await expect(toolbar.getByText('1 vybraná konverzace')).toBeVisible();
		await toolbar.getByRole('button', { name: 'Smazat vybrané' }).click();

		// ARCHIVE is not the trash folder, so the delete moves the conversation's
		// ARCHIVE members out — the folder empties and the treegrid unmounts.
		await expect(page.getByRole('treegrid', { name: 'Seznam konverzací' })).toHaveCount(0);
		await expect(page.locator('[role="row"][data-stable-id="arch-03"]')).toHaveCount(0);

		// Folder-scoped semantics: the received reply in the same thread must
		// survive a bulk delete fired from the ARCHIVE view.
		await page.goto(`/mail/${accountId}/INBOX`);
		await waitForShell(page);
		await expect(
			page
				.getByRole('treegrid', { name: 'Seznam konverzací' })
				.locator('[role="row"][data-stable-id="inbox-plan-01"]')
		).toBeVisible();
	});

	test('přesun vybrané konverzace přesune členy v aktuální složce', async ({ page }) => {
		await page.goto(`/mail/${accountId}/ARCHIVE`);
		await waitForShell(page);

		const parent = archiveRow(page, 'arch-03');
		await expect(parent).toBeVisible();
		await parent.locator('input[type="checkbox"]').check();

		const toolbar = page.getByRole('toolbar', { name: 'Hromadné akce' });
		await toolbar.getByRole('button', { name: 'Přesunout vybrané' }).click();
		await page.getByRole('menuitem', { name: 'Spam', exact: true }).click();

		await expect(page.getByRole('treegrid', { name: 'Seznam konverzací' })).toHaveCount(0);
		await expect(page.locator('[role="row"][data-stable-id="arch-03"]')).toHaveCount(0);
	});
});
