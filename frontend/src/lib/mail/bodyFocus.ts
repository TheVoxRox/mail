/**
 * Declares *why* a message was opened, so the body can decide whether to take
 * focus.
 *
 * Opening a message deliberately (Enter/Space or a click on a list row, a
 * search result, a deep link) puts the reading cursor on the body — the
 * Outlook model, Esc restores focus to the list row. A row change that merely
 * *follows focus* in a split reading pane must NOT: there the user is still
 * navigating the list, and stealing focus into the pane strands them (the body
 * lives in a sandboxed iframe, so the next Arrow key never reaches the grid).
 *
 * Default (no intent recorded) is to focus the body, which covers every entry
 * that is an open by definition — a deep link, a reload, the detail route
 * mounting on its own. Only the follow-focus path has to speak up, and it is
 * the one path that knows.
 */
import { writable } from 'svelte/store';

export type BodyFocusIntent = {
	stableId: string;
	/** `open` = deliberate open, `follow` = the roving selection moved here. */
	mode: 'open' | 'follow';
};

const intent = writable<BodyFocusIntent | null>(null);

export const bodyFocusIntent = {
	subscribe: intent.subscribe
};

/**
 * Deliberate open — the body takes focus once it renders, even if the message
 * is already showing (Enter on the row whose message the pane already holds).
 */
export function requestBodyFocus(stableId: string): void {
	intent.set({ stableId, mode: 'open' });
}

/** The selection followed the roving focus — the body must stay untouched. */
export function suppressBodyFocus(stableId: string): void {
	intent.set({ stableId, mode: 'follow' });
}

/** Consumed by the body once it acted on the intent. */
export function clearBodyFocusIntent(): void {
	intent.set(null);
}

/**
 * Whether the body that just rendered should take focus.
 *
 * A pure rule rather than a condition inside the effect, because the case that
 * broke it is invisible from the call site: an intent naming a **different**
 * message is not the same thing as no intent at all, and reading the store as
 * `intent?.stableId === stableId ? intent.mode : null` made them identical.
 *
 * How that happens: the intent is single-use and cleared by whoever consumes
 * it, so one that still names another message means that message's body never
 * rendered — the user paged past it. Two navigations are in flight, the earlier
 * one's content arrives late, and it used to land on the default ("no intent,
 * so this is a deep link, focus the body") and drag the reading cursor into the
 * sandboxed iframe, out of the grid, mid-navigation. Measured before the fix
 * with 40 fast Home/End/PageDown/PageUp cycles in split mode: focus left the
 * grid for the iframe on 2 of them, and it is the same signature as the CI
 * flake on a11y.e2e.ts ("MessageList podporuje Home, End, PageDown a PageUp",
 * activeElement outside the grid for the full five-second poll).
 *
 * So a stale intent means "paging", not "deep link". Only a genuinely empty
 * store is an entry with no history behind it — a deep link, a reload, the
 * detail route mounting on its own — and that is still the case the default is
 * for.
 */
export function shouldFocusBody(
	current: BodyFocusIntent | null,
	stableId: string,
	firstRender: boolean
): boolean {
	// Stale: recorded for another message, so navigations overlapped.
	if (current && current.stableId !== stableId) return false;
	const mode = current?.mode ?? null;
	// A deliberate open wins even on a re-render (Enter on the row whose message
	// the pane already shows); otherwise only a first render moves focus, and
	// never one the roving selection dragged in.
	if (mode === 'open') return true;
	return firstRender && mode !== 'follow';
}
