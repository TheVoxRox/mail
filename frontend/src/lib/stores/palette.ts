import { browser } from '$app/environment';
import { writable } from 'svelte/store';

const paletteOpenState = writable(false);

let previouslyFocusedElement: HTMLElement | null = null;

export const paletteOpen = {
	subscribe: paletteOpenState.subscribe
};

export function openPalette(): void {
	if (browser && document.activeElement instanceof HTMLElement) {
		previouslyFocusedElement = document.activeElement;
	}
	paletteOpenState.set(true);
}

export function closePalette(options?: { restoreFocus?: boolean }): void {
	const restoreFocus = options?.restoreFocus ?? true;
	paletteOpenState.set(false);

	if (!restoreFocus || !browser) return;

	const target = previouslyFocusedElement;
	previouslyFocusedElement = null;
	if (!target) return;

	queueMicrotask(() => {
		target.focus();
	});
}
