import { LOCALE_LABELS, SUPPORTED_LOCALES } from '#lib/i18n/index.js';
import { cycleTheme, setReadingPaneMode, switchLanguage } from '#lib/actions/view.js';
import type { ThemePreference } from '#lib/stores/theme.js';
import type { Command } from '#lib/commands/shared.js';

/*
 * The title names the target language in the interface's own language ("Switch
 * language to Czech" in English, the Czech word for English in Czech), not by
 * its own name. Built from the endonym, the Czech title ended in the English
 * word "English": read with the Czech voice, and a palette option has no way to
 * mark one word of its name as another language (WCAG 3.1.2). The endonym
 * stays a keyword, so typing "english" still finds it; the language select
 * keeps endonyms, each marked with its own `lang`. Every message file needs a
 * title for every language, which check:i18n enforces.
 */
const languageCommands: Command[] = SUPPORTED_LOCALES.map((targetLocale) => ({
	id: `view.locale.${targetLocale}`,
	titleKey: `command.switchLanguageTo.${targetLocale}`,
	groupKey: 'view',
	keywords: [targetLocale, LOCALE_LABELS[targetLocale].toLocaleLowerCase()],
	contexts: ['settings'],
	routePrefixes: ['/settings/language'],
	priority: 22,
	restoreFocus: true,
	available: () => true,
	run: () => switchLanguage(targetLocale)
}));

export function createViewCommands(themePreference: ThemePreference): Command[] {
	return [
		{
			id: 'view.theme',
			titleKey: 'command.toggleTheme',
			groupKey: 'view',
			icon: 'cog',
			contexts: ['settings'],
			routePrefixes: ['/settings/appearance'],
			priority: 28,
			restoreFocus: true,
			available: () => true,
			run: () => cycleTheme(themePreference)
		},
		...languageCommands,
		{
			id: 'view.reading.right',
			titleKey: 'command.readingPaneRight',
			groupKey: 'view',
			contexts: ['mail', 'settings'],
			routePrefixes: ['/mail/', '/settings/appearance'],
			priority: 14,
			restoreFocus: true,
			available: () => true,
			run: () => setReadingPaneMode('right')
		},
		{
			id: 'view.reading.bottom',
			titleKey: 'command.readingPaneBottom',
			groupKey: 'view',
			contexts: ['mail', 'settings'],
			routePrefixes: ['/mail/', '/settings/appearance'],
			priority: 13,
			restoreFocus: true,
			available: () => true,
			run: () => setReadingPaneMode('bottom')
		},
		{
			id: 'view.reading.off',
			titleKey: 'command.readingPaneOff',
			groupKey: 'view',
			contexts: ['mail', 'settings'],
			routePrefixes: ['/mail/', '/settings/appearance'],
			priority: 12,
			restoreFocus: true,
			available: () => true,
			run: () => setReadingPaneMode('off')
		}
	];
}
