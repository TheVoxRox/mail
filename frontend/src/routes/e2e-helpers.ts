import type { Locator, Page } from '@playwright/test';
import type { MessageBodyView, MessageGrouping, ReadingPane } from '$lib/stores/uiLayout.js';
import type { TextSize } from '$lib/stores/textSize.js';
import type { ThemePreference } from '$lib/stores/theme.js';

/**
 * The layout renders `<main>` only after i18n has loaded, so waiting for it
 * is what keeps an assertion — or an axe scan — off the "…" placeholder.
 */
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

/**
 * Go to `path` and wait for the shell — what all but two navigations in the
 * suite do, and what the two exceptions deliberately do not: the boot specs
 * navigate to observe the pre-shell loading state, so they still call
 * `page.goto` themselves and the difference now reads as intent rather than
 * as an omission.
 *
 * Worth a helper because forgetting the wait does not fail: the assertion that
 * follows races the mount and passes on a fast machine, then flakes in CI.
 */
export async function openApp(page: Page, path: string): Promise<void> {
	await page.goto(path);
	await waitForShell(page);
}

/**
 * Persisted app preferences, seeded before the app boots.
 *
 * The value types come from the stores themselves rather than being restated
 * here, so a preference that gains or loses a value breaks the tests that set
 * it at compile time instead of silently falling back to the default.
 */
export interface AppPrefs {
	locale?: 'cs' | 'en';
	readingPane?: ReadingPane;
	messageGrouping?: MessageGrouping;
	messageBodyView?: MessageBodyView;
	theme?: ThemePreference;
	textSize?: TextSize;
	activeAccountId?: number;
}

/**
 * Switches the e2e build's mock backend reads (`src/test-fixtures/msw`, plus
 * the sidecar and boot stores). Named exhaustively on purpose: a mistyped flag
 * is invisible at runtime — the mock simply stays on its default and the test
 * goes on to assert the unswitched behaviour, passing for the wrong reason.
 */
export interface MockFlags {
	/** Master switch some fixtures gate on. */
	e2e?: boolean;
	bootSlowMs?: number;
	bootVerySlowMs?: number;
	bootTimeoutMs?: number;
	connectionTestAuthFailure?: boolean;
	contactsBrokenRow?: boolean;
	contactsLegacyShape?: boolean;
	folderAuthFailure?: boolean;
	inboxThreadMember?: boolean;
	trashThreadMember?: boolean;
	mailPageSize?: number;
	noAccounts?: boolean;
	readinessDelayMs?: number;
	readinessFailures?: number;
	sessionDelayMs?: number;
	sidecarFailure?: 'once' | 'always';
}

/** Booleans go in as the '1' the readers check for; everything else as-is. */
function serialize(value: string | number | boolean): string {
	return typeof value === 'boolean' ? '1' : String(value);
}

async function seed(page: Page, entries: [string, string][]): Promise<void> {
	// addInitScript serializes the callback, so the values travel as an
	// argument rather than being closed over. Its Disposable return is dropped
	// on purpose — these seeds live for the whole test.
	await page.addInitScript((pairs: [string, string][]) => {
		for (const [key, value] of pairs) window.localStorage.setItem(key, value);
	}, entries);
}

/**
 * Seeds preferences before the app boots. Call it in `test.beforeEach` (or at
 * the top of a single test) — after `page.goto` it is too late.
 *
 * `locale: 'cs'` is what almost every spec wants, because the assertions are
 * against the Czech UI; it is still written out at each call site rather than
 * defaulted here, so a spec that reads in Czech says so.
 */
export function setPrefs(page: Page, prefs: AppPrefs): Promise<void> {
	const entries = Object.entries(prefs)
		.filter(([, value]) => value !== undefined)
		.map(([key, value]) => [`mail.${key}`, serialize(value)] as [string, string]);
	return seed(page, entries);
}

/** The mock-backend counterpart of `setPrefs`, under the `mail.e2e.` prefix. */
export function setMockFlags(page: Page, flags: MockFlags): Promise<void> {
	const entries = Object.entries(flags)
		.filter(([, value]) => value !== undefined && value !== false)
		.map(
			([key, value]) =>
				[key === 'e2e' ? 'mail.e2e' : `mail.e2e.${key}`, serialize(value)] as [string, string]
		);
	return seed(page, entries);
}

/*
 * The lists, by their accessible names. Deliberately still Czech: the suite
 * runs under `mail.locale = 'cs'` and asserts against the real UI text, and
 * making the selectors locale-agnostic is a separate task (see the policy note
 * in docs/translation-whitelist.txt). One place per list is the point — a
 * renamed grid used to mean a sweep across two dozen spec files.
 */

/** The flat message list (MessageList). */
export const messageGrid = (page: Page): Locator =>
	page.getByRole('grid', { name: 'Seznam zpráv' });

/** The conversation treegrid (ConversationList), in grouped mode. */
export const conversationGrid = (page: Page): Locator =>
	page.getByRole('treegrid', { name: 'Seznam konverzací' });

/** The search results grid (SearchResultsGrid). */
export const searchResultsGrid = (page: Page): Locator =>
	page.getByRole('grid', { name: 'Výsledky' });

/** The contact list (ContactList) — a native table carrying grid roles. */
export const contactGrid = (page: Page): Locator =>
	page.getByRole('grid', { name: 'Seznam kontaktů' });

/**
 * The data rows under `root`, in render order — never the sr-only header row,
 * which carries no `data-stable-id`. `root` is usually one of the grids above;
 * passing the page counts every row on screen, which is what a couple of specs
 * want when they assert a page size.
 */
export const rowsOf = (root: Page | Locator): Locator =>
	root.locator('[role="row"][data-stable-id]');
