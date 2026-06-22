import type { Page } from '@playwright/test';

export async function waitForShell(page: Page): Promise<void> {
	await page.waitForSelector('main', { state: 'attached' });
}

/**
 * The root route ('/') redirects to the default mailbox once accounts load
 * (see routes/+page.svelte). That redirect mutates `$page.url.pathname` and
 * fills the folders store asynchronously, both of which re-derive the command
 * list (lib/stores/commands.ts). Interacting with order-sensitive UI — e.g.
 * moving the command-palette selection with ArrowDown — before the redirect
 * settles lets the late re-derive run the palette's reset effect and snap the
 * active item back to the first command. `waitForShell` only guarantees the
 * shell is mounted (accounts ready), not that the redirect has landed, so wait
 * for the mailbox URL before dispatching such interactions.
 */
export async function waitForRootRedirect(page: Page): Promise<void> {
	await page.waitForURL('**/mail/**');
}
