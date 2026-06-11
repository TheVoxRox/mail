/**
 * I18n initialisation built on `svelte-i18n`.
 *
 * Called once from the root layout (before `children`). The locale is
 * persisted in `localStorage` under the `mail.locale` key and on every
 * change is synchronised to the `<html lang>` attribute – critical for
 * screen readers to pick the correct pronunciation engine / voice
 * language.
 */

import { browser } from '$app/environment';
import { derived, type Readable } from 'svelte/store';
import { addMessages, init, locale } from 'svelte-i18n';
import { isTauri } from '@tauri-apps/api/core';
import { getCurrentWindow } from '@tauri-apps/api/window';

type I18nValue = string | I18nDictionary | Array<string | I18nDictionary> | null;
type I18nDictionary = {
	[key: string]: I18nValue;
};

type AppMessages = I18nDictionary & {
	app: {
		title: string;
		localeLabel: string;
		[key: string]: I18nValue;
	};
};

const messageModules = import.meta.glob('./messages/*.json', {
	eager: true,
	import: 'default'
}) as Record<string, AppMessages>;

const messages = Object.entries(messageModules)
	.map(([path, value]) => {
		const match = /\/([^/]+)\.json$/.exec(path);
		if (!match) {
			throw new Error(`Cannot infer locale from ${path}`);
		}
		return [match[1], value] as const;
	})
	.sort(([a], [b]) => a.localeCompare(b));

if (messages.length === 0) {
	throw new Error('No i18n message files found.');
}

export const SUPPORTED_LOCALES = messages.map(([value]) => value);
export type AppLocale = (typeof SUPPORTED_LOCALES)[number];

export const LOCALE_LABELS = Object.fromEntries(
	messages.map(([value, message]) => [value, message.app.localeLabel])
) as Record<AppLocale, string>;

const STORAGE_KEY = 'mail.locale';
const DEFAULT_LOCALE = (
	SUPPORTED_LOCALES.includes('cs') ? 'cs' : SUPPORTED_LOCALES[0]
) as AppLocale;
const APP_TITLES = Object.fromEntries(
	messages.map(([value, message]) => [value, message.app.title])
) as Record<AppLocale, string>;

function isSupported(value: string | null | undefined): value is AppLocale {
	return !!value && (SUPPORTED_LOCALES as readonly string[]).includes(value);
}

function detectInitialLocale(): AppLocale {
	if (!browser) return DEFAULT_LOCALE;
	try {
		const stored = window.localStorage.getItem(STORAGE_KEY);
		if (isSupported(stored)) return stored;
	} catch {
		// ignore: localStorage may be unavailable (private mode etc.)
	}
	const navLang = window.navigator?.language?.split('-')[0]?.toLowerCase();
	if (isSupported(navLang)) return navLang;
	return DEFAULT_LOCALE;
}

let initialized = false;
let persistenceStarted = false;

function syncNativeWindowTitle(value: AppLocale): void {
	if (!isTauri()) return;

	void getCurrentWindow()
		.setTitle(APP_TITLES[value])
		.catch(() => {
			// Browser/e2e contexts do not have a native Tauri window to update.
		});
}

function startLocalePersistence(): void {
	if (!browser || persistenceStarted) return;
	persistenceStarted = true;

	/*
	 * Synchronise <html lang> with the current locale – screen readers
	 * use it to pick the pronunciation engine / voice language.
	 */
	locale.subscribe((value) => {
		if (!isSupported(value)) return;
		document.documentElement.lang = value;
		document.title = APP_TITLES[value];
		syncNativeWindowTitle(value);
		try {
			window.localStorage.setItem(STORAGE_KEY, value);
		} catch {
			// ignore
		}
	});
}

function ensureI18nInitialized(): void {
	if (initialized) return;
	initialized = true;

	for (const [value, message] of messages) {
		addMessages(value, message);
	}

	// svelte-i18n's init returns a promise that only does async work when the
	// initial locale loads messages lazily — ours are registered synchronously
	// above, so fire-and-forget is safe and intentional.
	void init({
		fallbackLocale: DEFAULT_LOCALE,
		initialLocale: detectInitialLocale()
	});

	startLocalePersistence();
}

ensureI18nInitialized();

export async function initI18n(): Promise<void> {
	ensureI18nInitialized();
}

/** Sets the active locale. Persistence happens via the subscribe in `initI18n`. */
export function setLocale(next: AppLocale): void {
	// locale.set resolves immediately for synchronously registered messages —
	// see the note on init() above.
	void locale.set(next);
}

/** Current locale as `AppLocale | null` (null before initialisation). */
export const appLocale: Readable<AppLocale | null> = derived(locale, ($l) =>
	isSupported($l) ? $l : null
);

export { _, _ as t, locale } from 'svelte-i18n';
