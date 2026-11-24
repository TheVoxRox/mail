/**
 * Text folded for matching what a user types against words in any language:
 * lowercased, with combining marks removed ("ñ" → "n", "ü" → "u"), and the
 * letters that Unicode decomposition keeps whole written as their base letters
 * ("ł" → "l", "ø" → "o", "ß" → "ss"), so a word typed without its diacritics
 * still matches. Both sides of a match go through it: the command palette's
 * query and texts, and the attachment reminder's body and words.
 */

const COMBINING_MARKS = /[̀-ͯ]/g;

/** Latin letters with a stroke, ligatures and the like, which NFD does not split. */
const UNDECOMPOSED_LETTERS: Readonly<Record<string, string>> = {
	ł: 'l',
	ŀ: 'l',
	ø: 'o',
	đ: 'd',
	ð: 'd',
	ħ: 'h',
	ı: 'i',
	ß: 'ss',
	æ: 'ae',
	œ: 'oe',
	þ: 'th'
};

const UNDECOMPOSED = new RegExp(`[${Object.keys(UNDECOMPOSED_LETTERS).join('')}]`, 'g');

export function foldText(text: string): string {
	return text
		.normalize('NFD')
		.replace(COMBINING_MARKS, '')
		.toLowerCase()
		.replace(UNDECOMPOSED, (letter) => UNDECOMPOSED_LETTERS[letter]);
}
