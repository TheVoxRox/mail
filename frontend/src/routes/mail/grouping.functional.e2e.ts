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

// The ARCHIVE fixture holds one thread: three ARCHIVE messages (arch-03 newest
// = the representative; arch-01/arch-02 revealed on expand) plus the user's
// own reply sent-plan-01 living in SENT — the cross-folder seam. The grouped
// view counts it and reveals it tagged with its folder; folder-scoped bulk
// actions must leave it untouched. Two further members of the same thread are
// deliberately out of scope: junk-plan-01 (JUNK) and draft-plan-01 (DRAFTS) —
// neither may be counted nor revealed. No other test asserts ARCHIVE, so it is
// a free multi-message seam.
const archiveRow = (page: Page, stableId: string) =>
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
		// Cross-folder count: 3 ARCHIVE messages + the sent reply.
		await expect(parent.getByText('konverzace, 4 zprávy')).toBeAttached();
		// Members are hidden while collapsed.
		await expect(archiveRow(page, 'arch-01')).toHaveCount(0);
		await expect(archiveRow(page, 'arch-02')).toHaveCount(0);
		await expect(archiveRow(page, 'sent-plan-01')).toHaveCount(0);

		await parent.locator('[data-expand-toggle]').click();

		await expect(parent).toHaveAttribute('aria-expanded', 'true');
		const member = archiveRow(page, 'arch-02');
		await expect(member).toBeVisible();
		await expect(member).toHaveAttribute('aria-level', '2');
		await expect(archiveRow(page, 'arch-01')).toBeVisible();
		// The user's own reply from SENT shows inline, tagged with its folder.
		const sentMember = archiveRow(page, 'sent-plan-01');
		await expect(sentMember).toBeVisible();
		await expect(sentMember).toHaveAttribute('aria-level', '2');
		await expect(sentMember.getByText('Ve složce Odeslané')).toBeAttached();
		// Junked and still-unsent members of the same thread stay out — the badge
		// counts 4, so the expanded list must show exactly 4 rows.
		await expect(archiveRow(page, 'junk-plan-01')).toHaveCount(0);
		await expect(archiveRow(page, 'draft-plan-01')).toHaveCount(0);

		await parent.locator('[data-expand-toggle]').click();

		await expect(parent).toHaveAttribute('aria-expanded', 'false');
		await expect(archiveRow(page, 'arch-02')).toHaveCount(0);
		await expect(archiveRow(page, 'sent-plan-01')).toHaveCount(0);
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
		await expect(archiveRow(page, 'arch-02')).toBeVisible();
		// Enter on the toggle must not also open the message: the row's own
		// Enter handler has to yield to the button.
		await expect(page).toHaveURL(new RegExp(`/mail/${accountId}/ARCHIVE$`));
		// The label tracks the state, so a screen reader is told what the key did.
		const collapse = page.getByRole('button', { name: 'Sbalit konverzaci Re: Plán vydání' });
		await expect(collapse).toBeFocused();

		await collapse.press(' ');

		await expect(parent).toHaveAttribute('aria-expanded', 'false');
		await expect(archiveRow(page, 'arch-02')).toHaveCount(0);
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
		await expect(archiveRow(page, 'arch-02')).toBeVisible();

		await parent.locator('[data-cell-target][data-col="3"]').press('ArrowLeft');
		await expect(parent).toHaveAttribute('aria-expanded', 'false');
		await expect(archiveRow(page, 'arch-02')).toHaveCount(0);
	});

	test('otevření člena rozbaleného vlákna přejde na jeho zprávu', async ({ page }) => {
		await page.goto(`/mail/${accountId}/ARCHIVE`);
		await waitForShell(page);

		const parent = archiveRow(page, 'arch-03');
		await expect(parent).toBeVisible();
		await parent.locator('[data-expand-toggle]').click();

		const member = archiveRow(page, 'arch-02');
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
		await expect(archiveRow(page, 'arch-02')).toBeVisible();
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
		// Still 4 — the two trashed replies do not raise the count.
		await expect(parent.getByText('konverzace, 4 zprávy')).toBeAttached();

		await parent.locator('[data-expand-toggle]').click();
		await expect(parent).toHaveAttribute('aria-expanded', 'true');

		await expect(archiveRow(page, 'sent-plan-01')).toBeVisible();
		await expect(archiveRow(page, 'trash-plan-01')).toHaveCount(0);
		await expect(archiveRow(page, 'trash-plan-02')).toHaveCount(0);
	});

	test('konverzace v koši zůstane folder-scoped a neukáže členy z jiných složek', async ({
		page
	}) => {
		// The Trash view must only ever show what is actually in the trash —
		// expanding a conversation there must not pull the live ARCHIVE/SENT
		// members in behind it.
		await page.addInitScript(() => {
			window.localStorage.setItem('mail.e2e.trashThreadMember', '1');
		});
		await page.goto(`/mail/${accountId}/TRASH`);
		await waitForShell(page);

		const grid = page.getByRole('treegrid', { name: 'Seznam konverzací' });
		const parent = grid.locator('[role="row"][data-stable-id="trash-plan-02"]');
		await expect(parent).toBeVisible();
		// Folder-scoped count: the two trashed replies, nothing else.
		await expect(parent.getByText('konverzace, 2 zprávy')).toBeAttached();

		await parent.locator('[data-expand-toggle]').click();
		await expect(parent).toHaveAttribute('aria-expanded', 'true');

		await expect(grid.locator('[role="row"][data-stable-id="trash-plan-01"]')).toBeVisible();
		await expect(grid.locator('[role="row"][data-stable-id="arch-01"]')).toHaveCount(0);
		await expect(grid.locator('[role="row"][data-stable-id="arch-02"]')).toHaveCount(0);
		await expect(grid.locator('[role="row"][data-stable-id="arch-03"]')).toHaveCount(0);
		await expect(grid.locator('[role="row"][data-stable-id="sent-plan-01"]')).toHaveCount(0);
	});

	test('člen z jiné složky se otevře pod svou složkou, ne pod zobrazenou', async ({ page }) => {
		// The route folder is what the layout header, the back link and the move
		// control read. Opening the sent reply as .../ARCHIVE/sent-plan-01 would
		// describe it as an archived message and offer moving Sent -> Sent.
		await page.goto(`/mail/${accountId}/ARCHIVE`);
		await waitForShell(page);

		const parent = archiveRow(page, 'arch-03');
		await expect(parent).toBeVisible();
		await parent.locator('[data-expand-toggle]').click();

		const sentMember = archiveRow(page, 'sent-plan-01');
		await expect(sentMember).toBeVisible();
		await sentMember.click();

		await page.waitForURL(`**/mail/${accountId}/SENT/sent-plan-01`);
	});
});

test.describe('Hromadné akce nad konverzacemi', () => {
	test('smazání vybrané konverzace zasáhne členy v aktuální složce, odeslanou kopii nechá být', async ({
		page
	}) => {
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

		// Folder-scoped semantics: the user's sent reply in the same thread must
		// survive a bulk delete fired from the ARCHIVE view.
		await page.goto(`/mail/${accountId}/SENT`);
		await waitForShell(page);
		await expect(
			page
				.getByRole('treegrid', { name: 'Seznam konverzací' })
				.locator('[role="row"][data-stable-id="sent-plan-01"]')
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
