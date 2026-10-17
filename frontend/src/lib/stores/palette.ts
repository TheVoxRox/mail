import { browser } from '$app/env';
import { get, writable } from 'svelte/store';

const paletteOpenState = writable(false);

let previouslyFocusedElement: HTMLElement | null = null;

export const paletteOpen = {
	subscribe: paletteOpenState.subscribe
};

export function openPalette(): void {
	// Re-invoking Ctrl+K while the palette is open must not overwrite the
	// remembered focus target with the palette's own input — the dialog
	// unmounts on close, so the restore would silently drop focus to <body>.
	if (get(paletteOpenState)) return;
	if (browser && document.activeElement instanceof HTMLElement) {
		previouslyFocusedElement = document.activeElement;
	}
	paletteOpenState.set(true);
}

/** The element Ctrl+K was pressed on, kept for the dialog's own close (below). */
let restoreTarget: HTMLElement | null = null;

export function closePalette(options?: { restoreFocus?: boolean }): void {
	const restoreFocus = options?.restoreFocus ?? true;
	paletteOpenState.set(false);

	const target = previouslyFocusedElement;
	previouslyFocusedElement = null;
	restoreTarget = target;
	if (!restoreFocus || !browser || !target) return;

	queueMicrotask(() => {
		// The target may have been removed while the palette was open
		// (e.g. the command deleted the focused row); focusing a detached
		// element is a silent no-op, so skip it instead of pretending.
		if (target.isConnected) target.focus();
	});
}

/**
 * The palette dialog's close-autofocus. bits-ui moves focus once more when the
 * dialog's content unmounts, after the restore above, to whatever was focused
 * when its focus scope mounted. That is the element Ctrl+K was pressed on only
 * if the scope mounted before the palette's input took focus through its
 * autofocus; the other way round it is the input, which is gone a moment
 * later, and focus fell to <body>. The order is not ours to fix, so this
 * does bits-ui's move with the element meant: the one Ctrl+K was pressed on,
 * whether or not closePalette restored to it. A command that runs after the
 * close (restoreFocus: false) starts from there as it always did, and printing
 * relies on it to leave focus where the palette was opened.
 */
export function paletteCloseAutoFocus(event: Event): void {
	event.preventDefault();
	const target = restoreTarget;
	restoreTarget = null;
	if (target?.isConnected) target.focus();
}
