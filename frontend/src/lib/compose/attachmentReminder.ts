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
 *   the backend builds for replies / forwards ("---------- Původní zpráva
 *   ----------"), so a quoted message or a corporate signature footer
 *   mentioning attachments does not trigger the reminder.
 * - Diacritic-insensitive: keywords match on a lowercased, diacritic-stripped
 *   copy, so Czech typed without diacritics ("v priloze") still counts. Stems
 *   that become ambiguous once stripped (přikládám vs. příklad) are matched as
 *   full words only.
 *
 * Kept free of Svelte/DOM so the ComposeForm wiring stays unit-testable.
 */

/** RFC 3676 signature separator line ("-- "), tolerating a missing space. */
const SIGNATURE_SEPARATOR = /^-- ?\r?$/m;

/**
 * Dash-framed quote header line: the backend's "---------- Původní zpráva
 * ----------" / "---------- Přeposlaná zpráva ----------" as well as the
 * Outlook-style "-----Original Message-----". A bare dash rule ("---") does
 * not match — it needs framed content.
 */
const QUOTE_HEADER = /^-{3,}.*\S.*-{3,}[ \t\r]*$/m;

/** Matched against the normalized (lowercased, diacritic-stripped) body. */
const KEYWORD_PATTERNS: readonly RegExp[] = [
	/\bpriloh\w*/, // příloha and its inflections (v příloze, přílohy, přílohou…)
	/\bpriloz\w*/, // přiložit family (přiložil, přiloženo, přiložte…)
	/\bprikladam(e)?\b/, // přikládám(e) — the stem would collide with "příklad"
	/\battach\w*/, // attach, attached, attachment(s), attaching
	/\benclos\w*/ // enclosed, enclosing, enclosure
];

function normalized(text: string): string {
	return text.normalize('NFD').replace(/[̀-ͯ]/g, '').toLowerCase();
}

/** The part of the body the user authored: everything before the first
 * signature separator or quote header. */
function authoredRegion(body: string): string {
	const boundaries = [body.search(SIGNATURE_SEPARATOR), body.search(QUOTE_HEADER)].filter(
		(index) => index >= 0
	);
	return boundaries.length === 0 ? body : body.slice(0, Math.min(...boundaries));
}

/** Whether the authored part of the body mentions an attachment (cs / en). */
export function mentionsAttachment(body: string): boolean {
	const text = normalized(authoredRegion(body));
	return KEYWORD_PATTERNS.some((pattern) => pattern.test(text));
}
