/**
 * The form text the user types is stored in, the rule `docs/DECISIONS.md`
 * records under "Single-line text is stored without surrounding whitespace,
 * the rest as written". The backend's `StoredText` applies the same rule, and
 * both run the vectors in
 * `backend/src/test/resources/text/stored-text-vectors.json`, so the value a
 * form shows after saving is the value the backend keeps.
 *
 * Whitespace is what `String.prototype.trim()` removes, which is also what a
 * regular expression's `\s` matches: every space separator (the no-break
 * spaces included), tab, vertical tab, form feed, U+FEFF and the line
 * terminators. The backend names that set explicitly, as Java's own
 * `String.strip()` is a different one.
 */

const LINE_TERMINATOR = /[\n\r\u2028\u2029]/u;
const WHITESPACE = /\s/u;

/** A required single-line value without the whitespace around it; blank stays blank. */
export function strip(value: string): string {
	return value.trim();
}

/** An optional single-line value without the whitespace around it, `null` when nothing is left. */
export function stripToNull(value: string | null | undefined): string | null {
	if (value == null) return null;
	const stripped = value.trim();
	return stripped === '' ? null : stripped;
}

/**
 * A multi-line value (a note, a signature) without the blank lines it starts
 * with and the whitespace it ends with, `null` when nothing is left. The
 * indentation of its first line stays, in line with the lines below.
 */
export function stripBlankLinesToNull(value: string | null | undefined): string | null {
	if (value == null) return null;
	const end = value.trimEnd();
	if (end === '') return null;
	let start = 0;
	for (let i = 0; i < end.length && WHITESPACE.test(end[i]); i++) {
		if (LINE_TERMINATOR.test(end[i])) start = i + 1;
	}
	return end.slice(start);
}
