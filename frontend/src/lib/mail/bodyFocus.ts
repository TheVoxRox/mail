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
