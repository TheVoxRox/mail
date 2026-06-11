import { LOCALE_LABELS, SUPPORTED_LOCALES, type AppLocale } from '$lib/i18n/index.js';
import { cycleTheme, setReadingPaneMode, switchLanguage } from '$lib/actions/view.js';
import type { ThemePreference } from '$lib/stores/theme.js';
import { localeKeywords, type Command } from '$lib/commands/shared.js';

const languageCommands: Command[] = SUPPORTED_LOCALES.map((targetLocale) => ({
	id: `view.locale.${targetLocale}`,
	titleKey: 'command.switchLanguage',
	titleValues: { language: LOCALE_LABELS[targetLocale] },
	groupKey: 'view',
	keywords: [targetLocale, LOCALE_LABELS[targetLocale].toLocaleLowerCase(), 'jazyk', 'language'],
	contexts: ['settings'],
	routePrefixes: ['/settings/language'],
	priority: 22,
	available: () => true,
	run: () => switchLanguage(targetLocale)
}));

export function createViewCommands(
	locale: AppLocale | null,
	themePreference: ThemePreference
): Command[] {
	return [
		{
			id: 'view.theme',
			titleKey: 'command.toggleTheme',
			groupKey: 'view',
			keywords: localeKeywords(locale, ['tema', 'vzhled'], ['theme', 'appearance']),
			icon: 'cog',
			contexts: ['settings'],
			routePrefixes: ['/settings/appearance'],
			priority: 28,
			available: () => true,
			run: () => cycleTheme(themePreference)
		},
		...languageCommands,
		{
			id: 'view.reading.right',
			titleKey: 'command.readingPaneRight',
			groupKey: 'view',
			keywords: localeKeywords(
				locale,
				['podokno vpravo', 'reading pane vpravo'],
				['reading pane right', 'split right']
			),
			contexts: ['mail', 'settings'],
			routePrefixes: ['/mail/', '/settings/appearance'],
			priority: 14,
			available: () => true,
			run: () => setReadingPaneMode('right')
		},
		{
			id: 'view.reading.bottom',
			titleKey: 'command.readingPaneBottom',
			groupKey: 'view',
			keywords: localeKeywords(
				locale,
				['podokno dole', 'reading pane dole'],
				['reading pane bottom', 'split bottom']
			),
			contexts: ['mail', 'settings'],
			routePrefixes: ['/mail/', '/settings/appearance'],
			priority: 13,
			available: () => true,
			run: () => setReadingPaneMode('bottom')
		},
		{
			id: 'view.reading.off',
			titleKey: 'command.readingPaneOff',
			groupKey: 'view',
			keywords: localeKeywords(
				locale,
				['podokno skryte', 'reading pane skryte'],
				['reading pane hidden', 'reading pane off']
			),
			contexts: ['mail', 'settings'],
			routePrefixes: ['/mail/', '/settings/appearance'],
			priority: 12,
			available: () => true,
			run: () => setReadingPaneMode('off')
		}
	];
}
