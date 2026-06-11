import type { IconName } from '$lib/components/Icon.svelte';
import type { AppLocale } from '$lib/i18n/index.js';
import type { WorkspaceMode } from '$lib/stores/workspaceMode.js';

export type CommandGroup = 'nav' | 'mail' | 'account' | 'view' | 'other';

export interface Command {
	id: string;
	titleKey: string;
	groupKey: CommandGroup;
	titleValues?: Record<string, string | number>;
	keywords?: string[];
	shortcut?: string;
	icon?: IconName;
	contexts?: WorkspaceMode[];
	routePrefixes?: string[];
	priority?: number;
	available: () => boolean;
	run: () => void | Promise<unknown>;
}

export function localeKeywords(locale: AppLocale | null, cs: string[], en: string[]): string[] {
	return locale === 'en' ? [...en, ...cs] : [...cs, ...en];
}
