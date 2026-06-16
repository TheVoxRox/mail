/**
 * Text size preference (small / medium / large).
 *
 * Persisted in `localStorage` under the key `mail.textSize`. Applied as the
 * root `font-size` on `<html>` from the root layout. Because the whole UI is
 * sized in `rem` (text, padding, control heights, gaps, radius), changing the
 * root font-size scales the interface proportionally — a built-in zoom rather
 * than a text-only bump that would crowd fixed-height controls. Mirrors the
 * `theme` store pattern.
 */

import { browser } from '$app/environment';
import { writable } from 'svelte/store';

export type TextSize = 'small' | 'medium' | 'large';

const STORAGE_KEY = 'mail.textSize';
const DEFAULT_PREF: TextSize = 'medium';

/** Root font-size per step. `medium` matches the browser default (16px). */
const ROOT_FONT_SIZE: Record<TextSize, string> = {
	small: '15px',
	medium: '16px',
	large: '18px'
};

function readInitial(): TextSize {
	if (!browser) return DEFAULT_PREF;
	try {
		const stored = window.localStorage.getItem(STORAGE_KEY);
		if (stored === 'small' || stored === 'medium' || stored === 'large') return stored;
	} catch {
		// localStorage unavailable – private mode etc.
	}
	return DEFAULT_PREF;
}

export const textSize = writable<TextSize>(readInitial());

export function setTextSize(next: TextSize): void {
	textSize.set(next);
	if (browser) {
		try {
			window.localStorage.setItem(STORAGE_KEY, next);
		} catch {
			// ignore
		}
	}
}

/**
 * Wires textSize into `<html style="font-size">`. Call once from the layout.
 */
export function initTextSizeSideEffects(): () => void {
	if (!browser) return () => {};
	const unsubscribe = textSize.subscribe((value) => {
		document.documentElement.style.fontSize = ROOT_FONT_SIZE[value];
	});
	return unsubscribe;
}
