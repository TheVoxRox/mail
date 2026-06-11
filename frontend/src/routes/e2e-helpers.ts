import type { Page } from '@playwright/test';

export async function waitForShell(page: Page): Promise<void> {
	await page.waitForSelector('main', { state: 'attached' });
}
