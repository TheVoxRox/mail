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
import { persistedStore } from './persisted.js';

export const TEXT_SIZES = ['small', 'medium', 'large'] as const;
export type TextSize = (typeof TEXT_SIZES)[number];

/** Root font-size per step. `medium` matches the browser default (16px). */
const ROOT_FONT_SIZE: Record<TextSize, string> = {
	small: '15px',
	medium: '16px',
	large: '18px'
};

export const textSize = persistedStore<TextSize>('mail.textSize', TEXT_SIZES, 'medium');

export function setTextSize(next: TextSize): void {
	textSize.set(next);
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
