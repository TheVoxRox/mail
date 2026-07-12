/**
 * Svelte writable persisted in localStorage and validated against a fixed
 * list of allowed values. Shared by the preference stores (theme, text size,
 * reading pane, update channel, …) that previously each hand-rolled the same
 * read-initial + browser-guard + try/catch idiom.
 *
 * Persistence is best-effort (private mode etc.) and happens only on explicit
 * `set`/`update` — the default is never written back, so users who never
 * chose a value pick up a changed default on upgrade.
 */

import { browser } from '$app/environment';
import { writable, type Writable } from 'svelte/store';

export function persistedStore<T extends string>(
	key: string,
	allowed: readonly T[],
	defaultValue: T
): Writable<T> {
	function readInitial(): T {
		if (!browser) return defaultValue;
		try {
			const stored = window.localStorage.getItem(key);
			if (stored !== null && (allowed as readonly string[]).includes(stored)) {
				return stored as T;
			}
		} catch {
			// localStorage unavailable – private mode etc.
		}
		return defaultValue;
	}

	function persist(value: T): void {
		if (!browser) return;
		try {
			window.localStorage.setItem(key, value);
		} catch {
			// ignore – persistence is best-effort
		}
	}

	const store = writable<T>(readInitial());

	return {
		subscribe: store.subscribe,
		set(value: T) {
			store.set(value);
			persist(value);
		},
		update(updater: (value: T) => T) {
			store.update((current) => {
				const next = updater(current);
				persist(next);
				return next;
			});
		}
	};
}
