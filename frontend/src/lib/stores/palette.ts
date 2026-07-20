import { browser } from '$app/environment';
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

export function closePalette(options?: { restoreFocus?: boolean }): void {
	const restoreFocus = options?.restoreFocus ?? true;
	paletteOpenState.set(false);

	const target = previouslyFocusedElement;
	previouslyFocusedElement = null;
	if (!restoreFocus || !browser || !target) return;

	queueMicrotask(() => {
		// The target may have been removed while the palette was open
		// (e.g. the command deleted the focused row); focusing a detached
		// element is a silent no-op, so skip it instead of pretending.
		if (target.isConnected) target.focus();
	});
}
