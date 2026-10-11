/**
 * The workspace pane — the sidebar left of `<main>` — and whether it is docked
 * in the row or folded away (WCAG 1.4.10).
 *
 * The rail (64px) and a docked pane (272px) take 336 CSS px whatever the
 * window. Below 48rem, which is 768 CSS px — a 1024px window at 150% zoom, or
 * a 1280px one at 200% — that left the content too little room, and at the
 * 320 CSS px of 400% zoom none at all. Below it the pane leaves the row and
 * slides in over the content on demand, as Outlook's folder pane does in a
 * narrow window: the toggle at the top of the rail, or Ctrl+Y, opens it.
 *
 * rem in the query is the browser default, not the root font size the text
 * size setting moves, so the line falls at the same window width at every
 * text size. It is the same line as Tailwind's `max-md:`, which the boot
 * skeleton uses for the same pane.
 */
import { browser } from '$app/environment';
import { get, readable, writable } from 'svelte/store';

export const NARROW_PANE_QUERY = '(width < 48rem)';

/** The pane's region, which the toggle controls and Ctrl+Y moves into. */
export const WORKSPACE_PANE_ID = 'workspace-pane';
/** Where focus goes back to when the folded pane is closed from the keyboard. */
export const WORKSPACE_PANE_TOGGLE_ID = 'workspace-pane-toggle';

/** Whether the window is narrow enough for the pane to fold away. */
export const paneNarrow = readable(false, (set) => {
	if (!browser || typeof window.matchMedia !== 'function') return;
	const query = window.matchMedia(NARROW_PANE_QUERY);
	set(query.matches);
	const onChange = (event: MediaQueryListEvent) => set(event.matches);
	query.addEventListener('change', onChange);
	return () => query.removeEventListener('change', onChange);
});

const openState = writable(false);

/** Whether the folded pane is out over the content. Meaningless while docked. */
export const paneOpen = { subscribe: openState.subscribe };

function paneElement(): HTMLElement | null {
	return browser ? document.getElementById(WORKSPACE_PANE_ID) : null;
}

/**
 * Moves focus into the pane: to the item for the page in view, as Outlook's
 * folder pane lands on the current folder, or else to its first control.
 */
export function focusPane(): void {
	const pane = paneElement();
	if (!pane) return;
	const target =
		pane.querySelector<HTMLElement>('[aria-current="page"]') ??
		pane.querySelector<HTMLElement>(
			'a[href], button:not(:disabled), input:not(:disabled), select:not(:disabled), [tabindex="0"]'
		);
	target?.focus();
}

/** Opens the folded pane and moves focus into it once it is shown. */
export function openPane(): void {
	openState.set(true);
	queueMicrotask(() => requestAnimationFrame(focusPane));
}

/**
 * Folds the pane away. `returnFocus` sends focus back to the toggle, for a
 * close the keyboard asked for; without it focus is left where the user put
 * it, which is outside the pane already.
 */
export function closePane(options?: { returnFocus?: boolean }): void {
	if (!get(openState)) return;
	const pane = paneElement();
	// A control about to be hidden keeps focus in Chromium until something
	// moves it, and the landing after a navigation only claims focus that
	// nothing holds. Let go of it first.
	if (pane?.contains(document.activeElement)) (document.activeElement as HTMLElement).blur();
	openState.set(false);
	if (options?.returnFocus) document.getElementById(WORKSPACE_PANE_TOGGLE_ID)?.focus();
}

/**
 * Keeps the open state in step with the window. Docking resets it, so the
 * next fold starts closed. Folding the pane while focus is in it would hide
 * the focused control and drop focus to `<body>`, which is what zooming in
 * from the folder list did; the pane comes out over the content instead.
 */
export function syncPaneToWidth(narrow: boolean): void {
	if (!narrow) openState.set(false);
	else if (paneElement()?.contains(document.activeElement)) openState.set(true);
}

/** Ctrl+Y, Outlook's "go to the folder pane": opens the pane first when it is folded. */
export function goToPane(): void {
	if (get(paneNarrow)) openPane();
	else focusPane();
}
