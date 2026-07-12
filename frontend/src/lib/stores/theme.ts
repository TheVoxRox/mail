/**
 * Theme preference (light / dark / system).
 *
 * Persisted in `localStorage` under the key `mail.theme`. The resulting
 * `.dark` class is applied on `<html>` in the root layout – shadcn-svelte
 * design tokens from `app.css` react to it and switch the OKLCH variables.
 */

import { browser } from '$app/environment';
import { type Readable, derived } from 'svelte/store';
import { persistedStore } from './persisted.js';

export const THEME_PREFERENCES = ['light', 'dark', 'system'] as const;
export type ThemePreference = (typeof THEME_PREFERENCES)[number];

function prefersDark(): boolean {
	if (!browser) return false;
	return window.matchMedia?.('(prefers-color-scheme: dark)').matches ?? false;
}

export const themePreference = persistedStore<ThemePreference>(
	'mail.theme',
	THEME_PREFERENCES,
	'system'
);

/** Actual theme after evaluating `system`. */
const resolvedTheme: Readable<'light' | 'dark'> = derived(
	themePreference,
	($pref, set) => {
		if (!browser) {
			set($pref === 'dark' ? 'dark' : 'light');
			return;
		}
		const apply = () => set($pref === 'system' ? (prefersDark() ? 'dark' : 'light') : $pref);
		apply();
		if ($pref !== 'system') return;
		const mq = window.matchMedia('(prefers-color-scheme: dark)');
		const handler = () => apply();
		mq.addEventListener('change', handler);
		return () => mq.removeEventListener('change', handler);
	},
	'light' as 'light' | 'dark'
);

export function setThemePreference(next: ThemePreference): void {
	themePreference.set(next);
}

/**
 * Wires resolvedTheme into `<html class="dark">`. Call once from the layout.
 */
export function initThemeSideEffects(): () => void {
	if (!browser) return () => {};
	const unsubscribe = resolvedTheme.subscribe((value) => {
		const root = document.documentElement;
		if (value === 'dark') root.classList.add('dark');
		else root.classList.remove('dark');
	});
	return unsubscribe;
}
