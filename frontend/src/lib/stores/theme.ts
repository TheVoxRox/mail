/**
 * Theme preference (light / dark / system).
 *
 * Persisted in `localStorage` under the key `mail.theme`. The resulting
 * `.dark` class is applied on `<html>` in the root layout – shadcn-svelte
 * design tokens from `app.css` react to it and switch the OKLCH variables.
 */

import { browser } from '$app/environment';
import { writable, type Readable, derived } from 'svelte/store';

export type ThemePreference = 'light' | 'dark' | 'system';

const STORAGE_KEY = 'mail.theme';
const DEFAULT_PREF: ThemePreference = 'system';

function readInitial(): ThemePreference {
	if (!browser) return DEFAULT_PREF;
	try {
		const stored = window.localStorage.getItem(STORAGE_KEY);
		if (stored === 'light' || stored === 'dark' || stored === 'system') return stored;
	} catch {
		// localStorage unavailable – private mode etc.
	}
	return DEFAULT_PREF;
}

function prefersDark(): boolean {
	if (!browser) return false;
	return window.matchMedia?.('(prefers-color-scheme: dark)').matches ?? false;
}

export const themePreference = writable<ThemePreference>(readInitial());

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
	if (browser) {
		try {
			window.localStorage.setItem(STORAGE_KEY, next);
		} catch {
			// ignore
		}
	}
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
