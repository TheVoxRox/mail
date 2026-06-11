/**
 * Actions that do not change mailbox contents — theme, language, reading
 * pane layout. Extracted out of `mail/actions.ts`, where they used to live
 * only because of a single import path from the command palette; logically
 * they are not mail actions.
 */
import { setReadingPane, type ReadingPane } from '$lib/stores/uiLayout.js';
import { setLocale, type AppLocale } from '$lib/i18n/index.js';
import { setThemePreference, type ThemePreference } from '$lib/stores/theme.js';

function nextThemePreference(current: ThemePreference): ThemePreference {
	switch (current) {
		case 'system':
			return 'light';
		case 'light':
			return 'dark';
		case 'dark':
		default:
			return 'system';
	}
}

export function cycleTheme(current: ThemePreference): void {
	setThemePreference(nextThemePreference(current));
}

export function switchLanguage(locale: AppLocale): void {
	setLocale(locale);
}

export function setReadingPaneMode(value: ReadingPane): void {
	setReadingPane(value);
}
