import { expect, test } from '@playwright/test';
import { waitForShell } from './e2e-helpers';

interface InitialLoadMetrics {
	shellVisibleMs: number;
	appReadyMs: number;
	loadEventMs: number;
	domContentLoadedMs: number;
	totalResourceBytes: number;
	scriptResourceBytes: number;
	stylesheetResourceBytes: number;
	resourceCount: number;
}

const budgets = {
	shellVisibleMs: 3000,
	appReadyMs: 4000,
	loadEventMs: 5000,
	totalResourceBytes: 2_500_000,
	scriptResourceBytes: 1_500_000,
	stylesheetResourceBytes: 300_000,
	resourceCount: 120
} as const;

test.beforeEach(async ({ page }) => {
	await page.addInitScript(() => {
		window.localStorage.setItem('mail.locale', 'cs');
		window.localStorage.setItem('mail.readingPane', 'right');
	});
});

test.describe('Performance baseline', () => {
	test('initial load stays within baseline budgets', async ({ page }, testInfo) => {
		const startedAt = Date.now();

		await page.goto('/');
		await waitForShell(page);
		const shellVisibleMs = Date.now() - startedAt;

		await page.waitForURL('**/mail/1/INBOX');
		await expect(page.getByRole('heading', { name: 'Doručené' })).toBeVisible();
		await expect(page.locator('[role="row"][data-stable-id="msg-01"]')).toBeVisible();
		const appReadyMs = Date.now() - startedAt;

		const browserMetrics = await page.evaluate(() => {
			const navigation = performance.getEntriesByType(
				'navigation'
			)[0] as PerformanceNavigationTiming;
			const resources = performance.getEntriesByType('resource') as PerformanceResourceTiming[];

			const bytesFor = (initiatorType: string) =>
				resources
					.filter((entry) => entry.initiatorType === initiatorType)
					.reduce((total, entry) => total + entry.transferSize, 0);

			return {
				loadEventMs: Math.round(navigation.loadEventEnd - navigation.startTime),
				domContentLoadedMs: Math.round(navigation.domContentLoadedEventEnd - navigation.startTime),
				totalResourceBytes: resources.reduce((total, entry) => total + entry.transferSize, 0),
				scriptResourceBytes: bytesFor('script'),
				stylesheetResourceBytes: bytesFor('css') + bytesFor('link'),
				resourceCount: resources.length
			};
		});

		const metrics: InitialLoadMetrics = {
			shellVisibleMs,
			appReadyMs,
			...browserMetrics
		};

		await testInfo.attach('initial-load-performance.json', {
			body: JSON.stringify({ budgets, metrics }, null, 2),
			contentType: 'application/json'
		});

		expect(metrics.shellVisibleMs).toBeLessThanOrEqual(budgets.shellVisibleMs);
		expect(metrics.appReadyMs).toBeLessThanOrEqual(budgets.appReadyMs);
		expect(metrics.loadEventMs).toBeLessThanOrEqual(budgets.loadEventMs);
		expect(metrics.totalResourceBytes).toBeLessThanOrEqual(budgets.totalResourceBytes);
		expect(metrics.scriptResourceBytes).toBeLessThanOrEqual(budgets.scriptResourceBytes);
		expect(metrics.stylesheetResourceBytes).toBeLessThanOrEqual(budgets.stylesheetResourceBytes);
		expect(metrics.resourceCount).toBeLessThanOrEqual(budgets.resourceCount);
	});
});
