/**
 * Source-text helpers shared by the gates that have to tell code from prose.
 *
 * Both of them ask the same question in the end — does this name still exist
 * where a compiler can see it — and neither can answer it by matching
 * declarations, because a comment naming a symbol looks exactly like the
 * symbol. Blanking the parts a compiler ignores is what makes the question
 * answerable at all.
 */

/**
 * Blanks comments and string literals, keeping every other character in place
 * so line and column numbers still line up with the original.
 *
 * Deliberately a scanner and not a regex: `"// not a comment"` and
 * `// a "quote"` each turn the other's opener into content, and a replace pass
 * cannot hold that state. Errs towards *keeping* code — an unterminated
 * literal blanks to the end of its line only, so one stray quote cannot hide
 * the rest of a file from a gate.
 *
 * Java text blocks (`"""`) get their own branch, and they need it: treated as
 * three ordinary quotes, the opener closes itself on its own line and every
 * line of SQL below it reads as code. That is not theoretical — a `@Query`
 * holding `substr(c.email, …)` made the caller gate believe the repository
 * declared a method called `substr`.
 */
export function codeOnly(source) {
	let out = '';
	let index = 0;
	while (index < source.length) {
		const rest = source.slice(index);
		const char = source[index];
		if (rest.startsWith('//')) {
			const end = source.indexOf('\n', index);
			const stop = end === -1 ? source.length : end;
			out += ' '.repeat(stop - index);
			index = stop;
		} else if (rest.startsWith('/*')) {
			const end = source.indexOf('*/', index + 2);
			const stop = end === -1 ? source.length : end + 2;
			out += blankKeepingNewlines(source.slice(index, stop));
			index = stop;
		} else if (rest.startsWith('<!--')) {
			const end = source.indexOf('-->', index + 4);
			const stop = end === -1 ? source.length : end + 3;
			out += blankKeepingNewlines(source.slice(index, stop));
			index = stop;
		} else if (rest.startsWith('"""')) {
			const end = source.indexOf('"""', index + 3);
			const stop = end === -1 ? source.length : end + 3;
			out += blankKeepingNewlines(source.slice(index, stop));
			index = stop;
		} else if (char === '"' || char === "'" || char === '`') {
			let scan = index + 1;
			while (scan < source.length) {
				if (source[scan] === '\\') {
					scan += 2;
					continue;
				}
				if (source[scan] === char || source[scan] === '\n') break;
				scan += 1;
			}
			const stop = Math.min(scan + 1, source.length);
			out += blankKeepingNewlines(source.slice(index, stop));
			index = stop;
		} else {
			out += char;
			index += 1;
		}
	}
	return out;
}

/** Spaces, except newlines, which stay so line numbers survive. */
function blankKeepingNewlines(text) {
	return text.replace(/[^\n]/g, ' ');
}

/**
 * Blanks Java annotations, including their arguments.
 *
 * They sit between the modifier and the type — `public @Nullable String
 * getFoo()` — exactly where a declaration matcher reads a type, so leaving
 * them in makes a declaration look like something else entirely.
 */
export function withoutAnnotations(text) {
	return text.replace(/@\w+(?:\([^)]*\))?/g, ' ');
}
