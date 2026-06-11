// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { get } from 'svelte/store';

/*
 * Each test re-imports ./index.js after vi.resetModules() (see freshModule()).
 * That module eagerly `import.meta.glob`s both messages/*.json bundles and runs
 * svelte-i18n registration synchronously on load — no timers, no I/O, just a
 * transform-heavy module graph evaluated ~16× per run. Under full-suite
 * parallel worker contention the Vite transform of those re-imports
 * occasionally overruns the 5 s default and times out (non-deterministically),
 * even though every test passes in isolation. Give this file generous headroom;
 * the slowness is CPU scheduling, not a hang.
 */
vi.setConfig({ testTimeout: 30000, hookTimeout: 30000 });

const { browserMock, isTauriMock, setTitleMock, getCurrentWindowMock } = vi.hoisted(() => ({
	browserMock: { value: true },
	isTauriMock: vi.fn<() => boolean>(),
	setTitleMock: vi.fn<(title: string) => Promise<void>>(),
	getCurrentWindowMock: vi.fn()
}));

vi.mock('$app/environment', () => ({
	get browser() {
		return browserMock.value;
	},
	dev: false,
	building: false,
	version: 'test'
}));
vi.mock('@tauri-apps/api/core', () => ({ isTauri: isTauriMock }));
vi.mock('@tauri-apps/api/window', () => ({ getCurrentWindow: getCurrentWindowMock }));

type I18nModule = typeof import('./index.js');

function installLocalStorageStub(initial: Record<string, string> = {}): Map<string, string> {
	const store = new Map<string, string>(Object.entries(initial));
	const stub: Storage = {
		getItem: (key: string) => store.get(key) ?? null,
		setItem: (key: string, value: string) => {
			store.set(key, value);
		},
		removeItem: (key: string) => {
			store.delete(key);
		},
		clear: () => {
			store.clear();
		},
		get length() {
			return store.size;
		},
		key: (index: number) => Array.from(store.keys())[index] ?? null
	};
	Object.defineProperty(window, 'localStorage', {
		value: stub,
		writable: true,
		configurable: true
	});
	return store;
}

function setNavigatorLanguage(value: string | undefined): void {
	Object.defineProperty(window.navigator, 'language', {
		value,
		writable: true,
		configurable: true
	});
}

async function freshModule(): Promise<I18nModule> {
	vi.resetModules();
	return await import('./index.js');
}

beforeEach(() => {
	browserMock.value = true;
	isTauriMock.mockReturnValue(false);
	setTitleMock.mockReset().mockResolvedValue(undefined);
	getCurrentWindowMock.mockReset().mockReturnValue({ setTitle: setTitleMock });
	installLocalStorageStub();
	setNavigatorLanguage('en-US');
	// jsdom resets document on each describe, but document.title persists per
	// process — clear it so assertions don't catch a value from the prior test.
	document.title = '';
	document.documentElement.lang = '';
});

afterEach(() => {
	vi.unstubAllEnvs();
});

describe('SUPPORTED_LOCALES / LOCALE_LABELS — registered from messages/*.json', () => {
	it('exposes cs and en as supported locales', async () => {
		const mod = await freshModule();
		expect(mod.SUPPORTED_LOCALES).toEqual(expect.arrayContaining(['cs', 'en']));
	});

	it('LOCALE_LABELS carries the human-readable label for each supported locale', async () => {
		const mod = await freshModule();
		expect(mod.LOCALE_LABELS.cs).toBeTruthy();
		expect(mod.LOCALE_LABELS.en).toBeTruthy();
		expect(typeof mod.LOCALE_LABELS.cs).toBe('string');
	});
});

describe('detectInitialLocale — fallback chain', () => {
	it('returns DEFAULT_LOCALE (cs) when browser=false (SSR / unit test stub)', async () => {
		browserMock.value = false;
		const mod = await freshModule();
		expect(get(mod.appLocale)).toBe('cs');
	});

	it('honours the value stored in localStorage when it is supported', async () => {
		installLocalStorageStub({ 'mail.locale': 'en' });
		const mod = await freshModule();
		expect(get(mod.appLocale)).toBe('en');
	});

	it('ignores an unsupported value in localStorage and falls back to navigator.language', async () => {
		installLocalStorageStub({ 'mail.locale': 'fr' });
		setNavigatorLanguage('en-GB');
		const mod = await freshModule();
		expect(get(mod.appLocale)).toBe('en');
	});

	it('falls back to navigator.language short code when localStorage is empty', async () => {
		setNavigatorLanguage('cs-CZ');
		const mod = await freshModule();
		expect(get(mod.appLocale)).toBe('cs');
	});

	it('falls back to DEFAULT_LOCALE when neither store nor navigator yield a supported value', async () => {
		setNavigatorLanguage('fr-FR');
		const mod = await freshModule();
		expect(get(mod.appLocale)).toBe('cs');
	});

	it('survives a localStorage throw (private-mode browser) and continues the fallback chain', async () => {
		const throwingStorage: Storage = {
			getItem: () => {
				throw new Error('SecurityError');
			},
			setItem: () => {
				throw new Error('SecurityError');
			},
			removeItem: () => {},
			clear: () => {},
			length: 0,
			key: () => null
		};
		Object.defineProperty(window, 'localStorage', {
			value: throwingStorage,
			writable: true,
			configurable: true
		});
		setNavigatorLanguage('en-AU');

		const mod = await freshModule();

		expect(get(mod.appLocale)).toBe('en');
	});
});

describe('setLocale — persistence + DOM side-effects', () => {
	// svelte-i18n holds the locale store as a global module singleton that
	// survives vi.resetModules — so we can't assume "fresh state = cs" across
	// tests. We assert on the *transition* instead of on absolute values.
	it('updates appLocale derived store on transition', async () => {
		const mod = await freshModule();

		mod.setLocale('cs');
		expect(get(mod.appLocale)).toBe('cs');

		mod.setLocale('en');
		expect(get(mod.appLocale)).toBe('en');
	});

	it('writes the active locale to localStorage under the "mail.locale" key', async () => {
		const store = installLocalStorageStub();
		const mod = await freshModule();

		mod.setLocale('en');

		expect(store.get('mail.locale')).toBe('en');
	});

	it('sets <html lang> for screen-reader pronunciation engine routing', async () => {
		const mod = await freshModule();

		mod.setLocale('en');

		expect(document.documentElement.lang).toBe('en');

		mod.setLocale('cs');
		expect(document.documentElement.lang).toBe('cs');
	});

	it('sets document.title to a non-empty string on locale change', async () => {
		const mod = await freshModule();

		// svelte-i18n's locale store is a global singleton across vi.resetModules;
		// a set() with the same value doesn't always re-fire subscribers, so we
		// force a transition by toggling cs → en (or en → cs).
		mod.setLocale('cs');
		document.title = '';
		mod.setLocale('en');

		expect(document.title).toBeTruthy();
	});
});

describe('syncNativeWindowTitle — Tauri-only side-effect', () => {
	it('does not call getCurrentWindow().setTitle when not running under Tauri', async () => {
		isTauriMock.mockReturnValue(false);
		const mod = await freshModule();

		mod.setLocale('en');

		expect(setTitleMock).not.toHaveBeenCalled();
	});

	it('calls getCurrentWindow().setTitle with the localised title under Tauri', async () => {
		isTauriMock.mockReturnValue(true);
		const mod = await freshModule();

		mod.setLocale('en');

		expect(setTitleMock).toHaveBeenCalled();
		const calledWith = setTitleMock.mock.calls.at(-1)?.[0];
		expect(typeof calledWith).toBe('string');
		expect(calledWith).toBeTruthy();
	});

	it('swallows a rejected setTitle (e2e / non-Tauri preview) — does not throw', async () => {
		isTauriMock.mockReturnValue(true);
		setTitleMock.mockRejectedValue(new Error('no window'));
		const mod = await freshModule();

		expect(() => mod.setLocale('en')).not.toThrow();
	});
});

describe('initI18n — idempotent public bootstrap', () => {
	it('can be called multiple times without re-initialising', async () => {
		const mod = await freshModule();

		await expect(mod.initI18n()).resolves.toBeUndefined();
		await expect(mod.initI18n()).resolves.toBeUndefined();
	});
});
