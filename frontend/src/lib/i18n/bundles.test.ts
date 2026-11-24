import { describe, expect, it } from 'vitest';
import { BUNDLES, splitWordList, wordsInEveryLocale } from './bundles.js';

/*
 * The word lists are data inside the message files, so nothing but these tests
 * reads them as lists: check:i18n sees each one as a message that parses, and a
 * translator who writes a sentence where a list belongs gets no warning there.
 */

type Tree = { [key: string]: unknown };

function childKeys(messages: Tree, key: string): string[] {
	const value = messages[key];
	return value && typeof value === 'object' ? Object.keys(value as Tree) : [];
}

describe('splitWordList', () => {
	it('splits on commas and drops blanks around and between the words', () => {
		expect(splitWordList(' tisk ,vytisknout,, print ')).toEqual(['tisk', 'vytisknout', 'print']);
		expect(splitWordList('')).toEqual([]);
	});
});

describe('wordsInEveryLocale', () => {
	it('lists the given locale first and every word once', () => {
		expect(wordsInEveryLocale('commandKeywords.print', 'en')).toEqual([
			'print',
			'tisk',
			'vytisknout'
		]);
		expect(wordsInEveryLocale('commandKeywords.print', 'cs')).toEqual([
			'tisk',
			'vytisknout',
			'print'
		]);
	});

	it('yields nothing for a key no file has', () => {
		expect(wordsInEveryLocale('commandKeywords.noSuchCommand', 'cs')).toEqual([]);
	});
});

describe('word lists in the message files', () => {
	/*
	 * The palette pairs synonyms with a command by name, so a synonym entry left
	 * behind by a renamed or removed command would be searched for nothing.
	 */
	it.each(BUNDLES.map(([locale, messages]) => [locale, messages as Tree]))(
		'%s: every commandKeywords entry names a command title',
		(_locale, messages) => {
			const titles = new Set(childKeys(messages, 'command'));
			const orphans = childKeys(messages, 'commandKeywords').filter((name) => !titles.has(name));
			expect(orphans).toEqual([]);
		}
	);

	it.each(BUNDLES.map(([locale, messages]) => [locale, messages as Tree]))(
		'%s: attachment words are words or stems, not sentences',
		(_locale, messages) => {
			const compose = messages.compose as Tree;
			const words = splitWordList(String(compose.attachmentWords ?? ''));
			expect(words.length).toBeGreaterThan(0);
			for (const word of words) {
				expect(word).toMatch(/^[\p{L}\p{N}' -]+\*?$/u);
			}
		}
	);
});
