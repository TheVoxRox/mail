/**
 * The message files of every interface language, one per locale, named by
 * their locale code (`messages/cs.json`). index.ts registers them with
 * svelte-i18n. This module also serves the entries that are data rather than
 * sentences: the word lists that the command palette and the attachment
 * reminder read in every language at once, because what a user types into the
 * palette or writes in a message need not be in the language of the interface.
 *
 * Adding a language is adding its file here and its backend bundle; nothing in
 * the code names a language. docs/TRANSLATING.md walks through it.
 */

type I18nValue = string | I18nDictionary | Array<string | I18nDictionary> | null;

type I18nDictionary = { [key: string]: I18nValue };
export type AppMessages = I18nDictionary & {
	app: { title: string; localeLabel: string; [key: string]: I18nValue };
};

const messageModules = import.meta.glob('./messages/*.json', {
	eager: true,
	import: 'default'
}) as Record<string, AppMessages>;

/** Locale code and messages, ordered by code. */
export const BUNDLES: ReadonlyArray<readonly [string, AppMessages]> = Object.entries(messageModules)
	.map(([path, value]) => {
		const match = /\/([^/]+)\.json$/.exec(path);
		if (!match) {
			throw new Error(`Cannot infer locale from ${path}`);
		}
		return [match[1], value] as const;
	})
	.sort(([a], [b]) => a.localeCompare(b));

if (BUNDLES.length === 0) {
	throw new Error('No i18n message files found.');
}

/** The raw entry under a dotted key in one locale's file, if it is a string. */
function entryIn(messages: AppMessages, key: string): string | undefined {
	let value: I18nValue | undefined = messages;
	for (const part of key.split('.')) {
		if (value === null || typeof value !== 'object' || Array.isArray(value)) return undefined;
		value = value[part];
	}
	return typeof value === 'string' ? value : undefined;
}

/** A comma-separated word list split into its trimmed, non-empty words. */
export function splitWordList(list: string): string[] {
	return list
		.split(',')
		.map((word) => word.trim())
		.filter((word) => word.length > 0);
}

/**
 * The words of a word-list entry in every language that has it, `first`'s
 * words first and each word once. A key no file has yields no words.
 */
export function wordsInEveryLocale(key: string, first?: string | null): string[] {
	const ordered = [...BUNDLES].sort(([a], [b]) => Number(b === first) - Number(a === first));
	const words = new Set<string>();
	for (const [, messages] of ordered) {
		const entry = entryIn(messages, key);
		if (entry !== undefined) {
			for (const word of splitWordList(entry)) words.add(word);
		}
	}
	return [...words];
}
