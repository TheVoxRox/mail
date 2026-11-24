/**
 * Attachment-reminder heuristic (Gmail parity): flags a compose body that talks
 * about an attachment so the send path can ask "send anyway?" when nothing is
 * attached.
 *
 * Scope decisions:
 * - Body only. Subjects inherit "Re: Faktura v příloze" from the original
 *   message, which carried the attachment — checking them would flag every
 *   reply in such a thread.
 * - Only the text the user authored: scanning stops at the RFC 3676 signature
 *   separator (`"-- "`, see signature.ts) and at the dash-framed quote header
 *   the backend builds for replies / forwards in the interface language
 *   ("---------- Původní zpráva ----------", "---------- Original message
 *   ----------"), so a quoted message or a corporate signature footer
 *   mentioning attachments does not trigger the reminder.
 * - The words come from every language's message file at once
 *   (`compose.attachmentWords`): what decides is the language the message is
 *   written in, not the interface's, and a new language brings its words with
 *   its translation. A word ending in `*` is a stem and takes any ending
 *   ("příloh*": příloha, v příloze, přílohy). A word without one matches whole
 *   only, for a stem that would catch an unrelated word once diacritics are
 *   gone: "přikládám" stays whole because "přiklád-" stripped is "priklad",
 *   the start of "příklad" (example).
 * - Diacritic-insensitive: the words and the body both go through foldText,
 *   so Czech typed without diacritics ("v priloze") counts, and so does a
 *   Polish "l" typed for "ł".
 *
 * Kept free of Svelte/DOM so the ComposeForm wiring stays unit-testable.
 */

import { wordsInEveryLocale } from '#lib/i18n/bundles.js';
import { foldText } from '#lib/i18n/foldText.js';

/** RFC 3676 signature separator line ("-- "), tolerating a missing space. */
const SIGNATURE_SEPARATOR = /^-- ?\r?$/m;

/**
 * Dash-framed quote header line: the backend's, whose label follows the
 * interface language ("---------- Původní zpráva ----------", "----------
 * Forwarded message ----------"), as well as the Outlook-style "-----Original
 * Message-----". A bare dash rule ("---") does not match — it needs framed
 * content. The label is never matched, only the frame, so a new language needs
 * nothing here.
 */
const QUOTE_HEADER = /^-{3,}.*\S.*-{3,}[ \t\r]*$/m;

/** A letter or digit in any script; what a word boundary is drawn against. */
const WORD_CHARACTER = '[\\p{L}\\p{N}]';

function escapeRegExp(text: string): string {
	return text.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

/** "příloh*" → a stem matched at a word start; "přikládám" → a whole word. */
function attachmentWordPattern(word: string): RegExp {
	const stem = word.endsWith('*');
	const text = escapeRegExp(foldText(stem ? word.slice(0, -1) : word));
	const end = stem ? '' : `(?!${WORD_CHARACTER})`;
	return new RegExp(`(?<!${WORD_CHARACTER})${text}${end}`, 'u');
}

/** Matched against the normalized (lowercased, diacritic-stripped) body. */
const KEYWORD_PATTERNS: readonly RegExp[] =
	wordsInEveryLocale('compose.attachmentWords').map(attachmentWordPattern);

/** The part of the body the user authored: everything before the first
 * signature separator or quote header. */
function authoredRegion(body: string): string {
	const boundaries = [body.search(SIGNATURE_SEPARATOR), body.search(QUOTE_HEADER)].filter(
		(index) => index >= 0
	);
	return boundaries.length === 0 ? body : body.slice(0, Math.min(...boundaries));
}

/** Whether the authored part of the body mentions an attachment, in any language the app has. */
export function mentionsAttachment(body: string): boolean {
	const text = foldText(authoredRegion(body));
	return KEYWORD_PATTERNS.some((pattern) => pattern.test(text));
}
