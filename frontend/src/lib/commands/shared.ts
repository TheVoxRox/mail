import type { IconName } from '#lib/components/Icon.svelte';
import { wordsInEveryLocale } from '#lib/i18n/bundles.js';
import type { AppLocale } from '#lib/i18n/index.js';
import type { WorkspaceMode } from '#lib/stores/workspaceMode.js';

export type CommandGroup = 'nav' | 'mail' | 'account' | 'view' | 'other';

export interface Command {
	id: string;
	titleKey: string;
	groupKey: CommandGroup;
	titleValues?: Record<string, string | number>;
	/** Search words no translation carries; translated ones: {@link translatedKeywords}. */
	keywords?: string[];
	shortcut?: string;
	icon?: IconName;
	contexts?: WorkspaceMode[];
	routePrefixes?: string[];
	priority?: number;
	/**
	 * Restore focus to the pre-palette element after this command succeeds.
	 * Set on commands that only change state in place (toggles, sync);
	 * navigation commands and commands that manage focus themselves (delete,
	 * move) leave it unset, otherwise the restore would fight their own
	 * focus handling.
	 */
	restoreFocus?: boolean;
	available: () => boolean;
	run: () => void | Promise<unknown>;
}

const TITLE_PREFIX = 'command.';

/**
 * The synonyms a command is found by: the `commandKeywords` entry that shares
 * its title's name (`command.openSearch` → `commandKeywords.openSearch`), in
 * every language at once, the interface language first. What a user types is
 * the word they think of, which need not be in the interface language, and a
 * new language brings its synonyms in its own message file, with no change
 * here. `Command.keywords` holds what no translation can: addresses, folder
 * names, locale codes.
 */
export function translatedKeywords(titleKey: string, locale: AppLocale | null): string[] {
	if (!titleKey.startsWith(TITLE_PREFIX)) return [];
	return wordsInEveryLocale(`commandKeywords.${titleKey.slice(TITLE_PREFIX.length)}`, locale);
}
