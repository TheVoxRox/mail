import AxeBuilder from '@axe-core/playwright';
import { expect, type Locator, type Page } from '@playwright/test';
import { CHECK_OPTIONS, FORCED_RULES, WCAG_TAGS } from './a11y-target.js';
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
 * (see routes/+page.svelte). That redirect mutates `page.url.pathname` and
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
 * Wait until `target` is the document's active element.
 *
 * Preferred over `toBeFocused()` anywhere the message body is in play: that
 * matcher additionally requires `document.hasFocus()`, which headless Chromium
 * reports false on the outer document while the programmatically focused
 * sandbox `<iframe>` holds focus (flaky in CI). Comparing `activeElement` has
 * no such dependency.
 *
 * What makes it a helper rather than a workaround is the waiting half. Opening
 * a message parks focus in the body frame a frame later — MessageContent defers
 * it so the move lands after SvelteKit's own post-navigation focus reset — so a
 * visible frame says nothing about focus having arrived. A test that takes
 * focus away inside that gap has it stolen back, and because the frame forwards
 * keystrokes to the parent (lib/mail/mailFrame.ts) the key still lands: the test
 * passes while covering something other than what it claims (#284, #293). Wait
 * for focus to settle, then assert who holds it before sending a key — both
 * halves are this one call.
 */
export async function waitForFocus(target: Locator): Promise<void> {
	await expect.poll(() => target.evaluate((el) => el === document.activeElement)).toBe(true);
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
	/** Cuts the folder listing at this many messages, as the backend does past its local window. */
	mailListingDepth?: number;
	noAccounts?: boolean;
	/** Adds a Gmail account a Google sign-in owns (`oauth.user@gmail.com`). */
	oauthAccount?: boolean;
	/** Adds a password account on hand-typed servers, id 4, with no provider. */
	customAccount?: boolean;
	readinessDelayMs?: number;
	readinessFailures?: number;
	sessionDelayMs?: number;
	/** `exit:<code>` fails once the way a backend exiting with that code does. */
	sidecarFailure?: 'once' | 'always' | `exit:${number}`;
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
 * The message-body iframe (MessageContent), by its accessible title — never by
 * tag: the page can hold a second frame (the CSP probe in shortcuts builds one),
 * and a bare `iframe` locator turns that into a strict-mode failure in tests
 * that have nothing to do with it.
 */
export const bodyFrame = (page: Page): Locator => page.getByTitle('Obsah zprávy');

/**
 * The data rows under `root`, in render order — never the sr-only header row,
 * which carries no `data-stable-id`. `root` is usually one of the grids above;
 * passing the page counts every row on screen, which is what a couple of specs
 * want when they assert a page size.
 */
export const rowsOf = (root: Page | Locator): Locator =>
	root.locator('[role="row"][data-stable-id]');

/**
 * An axe run configured for the conformance target in a11y-target.ts. Every
 * scan in the repository goes through here, and `a11y-target.test.ts` fails a
 * spec that builds its own — a scan pinned to a narrower tag list is invisible
 * in a green suite, which is how one of them sat on the 2.0 baseline after the
 * rest moved to 2.2.
 *
 * Returns the builder rather than the results so a caller can still narrow the
 * context (`.include('[role="dialog"]')`) before calling `.analyze()`, which
 * first waits for running animations to end (`waitForAnimations`). Judge what
 * it returns with `expectNoFindings`.
 *
 * Order matters: `options()` replaces the whole options object, so it has to
 * come before `withTags()`, which writes `runOnly` into it.
 */
export const wcagScan = (page: Page): AxeBuilder =>
	new SettledAxeBuilder(page).options(scanOptions()).withTags(WCAG_TAGS);

class SettledAxeBuilder extends AxeBuilder {
	constructor(private readonly target: Page) {
		super({ page: target });
	}

	override async analyze(): Promise<AxeResults> {
		await waitForAnimations(this.target);
		return super.analyze();
	}
}

type AxeResults = Awaited<ReturnType<AxeBuilder['analyze']>>;
type AxeNode = AxeResults['incomplete'][number]['nodes'][number];

/**
 * Fails on what axe found, and on what it could not decide unless that has
 * been judged against the criterion and passes.
 *
 * axe files a result it cannot settle under `incomplete`, and a scan that reads
 * only `violations` passes it. That is not a corner case here: a label on a
 * paragraph, which the browser dropped so a screen reader heard a bare
 * "Vlastní", came back as an incomplete `aria-prohibited-attr` and every scan
 * stayed green (#680). So an incomplete fails the test until a case in
 * `REVIEWED_INCOMPLETE` covers it, and a case says why the criterion is met,
 * narrowly enough that a different element with the same rule still fails.
 *
 * It does not make axe see more than it does. The 16px contact checkbox inside
 * a clickable row failed 2.5.8 without axe reporting it at all, the row not
 * being focusable, so that one is held by its own geometry test (#681).
 */
export async function expectNoFindings(page: Page, results: AxeResults): Promise<void> {
	expect(results.violations).toEqual([]);
	const undecided: string[] = [];
	for (const rule of results.incomplete) {
		for (const node of rule.nodes) {
			const covered = await Promise.all(
				REVIEWED_INCOMPLETE.filter((judged) => judged.rule === rule.id).map((judged) =>
					judged.covers(page, node)
				)
			);
			if (!covered.includes(true)) {
				undecided.push(`${rule.id} ${node.target.join(' ')}: ${node.failureSummary ?? ''}`);
			}
		}
	}
	expect(undecided).toEqual([]);
}

/**
 * Whether the text of the element at `selector` meets WCAG 1.4.3 over its own
 * background, for the case axe gives up on. Runs in the page through
 * `page.evaluate`, so it refers to nothing outside itself.
 *
 * Strict where it cannot see: a background image, an opacity below 1 or a
 * foreign element between the text and the first opaque background (sampled
 * at nine points across the element) all make it answer false, and the result
 * then stays undecided and fails the scan.
 */
function meetsContrastOverOwnBackground(selector: unknown): boolean {
	type Rgba = [number, number, number, number];
	const el = typeof selector === 'string' ? document.querySelector(selector) : null;
	if (!el) return false;

	// Any CSS colour, oklch() included, resolved by painting one pixel.
	const canvas = document.createElement('canvas');
	canvas.width = 1;
	canvas.height = 1;
	const ctx = canvas.getContext('2d', { willReadFrequently: true });
	if (!ctx) return false;
	const rgba = (css: string): Rgba => {
		ctx.clearRect(0, 0, 1, 1);
		ctx.fillStyle = css;
		ctx.fillRect(0, 0, 1, 1);
		const [r, g, b, a] = ctx.getImageData(0, 0, 1, 1).data;
		return [r, g, b, a / 255];
	};
	const over = (top: Rgba, bottom: Rgba): Rgba => [
		top[0] * top[3] + bottom[0] * (1 - top[3]),
		top[1] * top[3] + bottom[1] * (1 - top[3]),
		top[2] * top[3] + bottom[2] * (1 - top[3]),
		1
	];

	const layers: Rgba[] = [];
	let opaque: Element | null = null;
	for (let node: Element | null = el; node; node = node.parentElement) {
		const style = getComputedStyle(node);
		if (style.backgroundImage !== 'none' || Number(style.opacity) < 1) return false;
		const colour = rgba(style.backgroundColor);
		if (colour[3] > 0) layers.push(colour);
		if (colour[3] === 1) {
			opaque = node;
			break;
		}
	}
	if (!opaque) return false;

	const box = el.getBoundingClientRect();
	for (const fx of [0.05, 0.5, 0.95]) {
		for (const fy of [0.25, 0.5, 0.75]) {
			const stack = document.elementsFromPoint(
				box.left + box.width * fx,
				box.top + box.height * fy
			);
			const at = stack.indexOf(el);
			if (at < 0) return false;
			for (const below of stack.slice(at + 1)) {
				if (!below.contains(el)) return false;
				if (below === opaque) break;
			}
		}
	}

	let background = layers[layers.length - 1];
	for (let i = layers.length - 2; i >= 0; i--) background = over(layers[i], background);
	const style = getComputedStyle(el);
	const text = over(rgba(style.color), background);
	const luminance = ([r, g, b]: Rgba) => {
		const [lr, lg, lb] = [r, g, b].map((channel) => {
			const c = channel / 255;
			return c <= 0.04045 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4;
		});
		return 0.2126 * lr + 0.7152 * lg + 0.0722 * lb;
	};
	const [light, dark] = [luminance(text), luminance(background)].sort((a, b) => b - a);
	const ratio = (light + 0.05) / (dark + 0.05);
	const size = parseFloat(style.fontSize);
	const large = size >= 24 || (Number(style.fontWeight) >= 700 && size >= 18.66);
	return ratio >= (large ? 3 : 4.5);
}

/** The axe check data of a node, by check: what `messageKey` it carries. */
const messageKeys = (node: AxeNode): string[] =>
	[...node.any, ...node.all, ...node.none].flatMap((check) => {
		const data = check.data as { messageKey?: unknown } | null | undefined;
		return typeof data?.messageKey === 'string' ? [data.messageKey] : [];
	});

const REVIEWED_INCOMPLETE: ReadonlyArray<{
	rule: string;
	/** Why the criterion is met. */
	reason: string;
	covers: (page: Page, node: AxeNode) => Promise<boolean>;
}> = [
	{
		rule: 'color-contrast',
		reason:
			'The element holds a lone symbol, not text: the ▾ of a select, the ★ of a badge, both ' +
			'aria-hidden beside text that says the same. 1.4.3 is about text.',
		covers: async (_page, node) => messageKeys(node).includes('nonBmp')
	},
	{
		rule: 'color-contrast',
		reason:
			'axe could not settle the background because the element overlaps others (a dialog ' +
			'over the page). Decided in the page instead: at points across the element, everything ' +
			'between it and the first opaque background must be its own ancestors, and the ratio ' +
			'against those backgrounds must meet 1.4.3.',
		covers: (page, node) =>
			messageKeys(node).includes('elmPartiallyObscuring')
				? page.evaluate(meetsContrastOverOwnBackground, node.target[0])
				: Promise.resolve(false)
	},
	{
		rule: 'label-content-name-mismatch',
		reason:
			'The visible label holds no letter or digit (the × of a toast), and 2.5.3 concerns ' +
			'labels made of text; there is no visible word for the spoken name to contain.',
		covers: (page, node) =>
			page.evaluate((selector) => {
				const el = typeof selector === 'string' ? document.querySelector(selector) : null;
				if (!el) return false;
				const walker = document.createTreeWalker(el, NodeFilter.SHOW_TEXT);
				let visible = '';
				for (let text = walker.nextNode(); text; text = walker.nextNode()) {
					const box = text.parentElement?.getBoundingClientRect();
					// sr-only text is clipped to a 1px box: spoken, not seen.
					if (box && box.width > 1 && box.height > 1) visible += text.textContent ?? '';
				}
				return visible.trim() !== '' && !/[\p{L}\p{N}]/u.test(visible);
			}, node.target[0])
	},
	{
		rule: 'target-size',
		reason:
			'Decided by geometry in the page, per element (WCAG 2.5.8). A grid cell is a focus ' +
			'position of the roving tabindex, not a pointer target: a click on it reaches the row. ' +
			'A checkbox inside a label has the label as its target, which axe does not merge; it ' +
			'passes at 24 × 24. A link inside a data row passes as Equivalent: a click on the row ' +
			'background opens what the link opens (rowActivation.ts), and the row is at least 24px tall.',
		covers: (page, node) =>
			page.evaluate((selector) => {
				const el = typeof selector === 'string' ? document.querySelector(selector) : null;
				if (!el) return false;
				if (el.matches('[role="gridcell"], [role="rowheader"]')) return true;
				const label = el.matches('input') ? el.closest('label') : null;
				if (label) {
					const box = label.getBoundingClientRect();
					return box.width >= 24 && box.height >= 24;
				}
				const row = el.matches('a[href]') ? el.closest('[role="row"], tr') : null;
				return row !== null && row.getBoundingClientRect().height >= 24;
			}, node.target[0])
	}
];

/**
 * A fresh object per scan, since `withTags()` writes into it. axe reads
 * `checks` from the run options (`getCheckOption` in axe-core) though its
 * `RunOptions` type leaves the key out, and a returned object, unlike a
 * literal at the call, is not refused for the extra key.
 */
const scanOptions = () => ({ rules: FORCED_RULES, checks: CHECK_OPTIONS });

/**
 * Wait for every finite animation and CSS transition on the page to end.
 *
 * axe reads colours as computed at the moment it looks, and a control that has
 * just changed state is still on its way between two palettes: Send in compose
 * turns from the muted disabled look to the primary one over `transition-all`,
 * and a scan on a loaded machine once read both its colours about 83% of the
 * way there, at 3.43:1. That is a colour the user sees only in passing, so the
 * scan waits for the end state. Infinite animations (a spinner) are left out:
 * they never finish.
 */
async function waitForAnimations(page: Page): Promise<void> {
	await page.evaluate(() =>
		Promise.all(
			document
				.getAnimations()
				.filter((animation) => animation.effect?.getComputedTiming().endTime !== Infinity)
				.map((animation) => animation.finished.catch(() => undefined))
		)
	);
}
