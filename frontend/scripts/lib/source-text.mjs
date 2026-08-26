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
 * cannot hold that state. Errs towards *keeping* code — an unterminated quoted
 * literal blanks to the end of its line only, and a backtick with nothing
 * closing it is left as an ordinary character, so one stray quote cannot hide
 * the rest of a file from a gate.
 *
 * Java text blocks (`"""`) get their own branch, and they need it: treated as
 * three ordinary quotes, the opener closes itself on its own line and every
 * line of SQL below it reads as code. That is not theoretical — a `@Query`
 * holding `substr(c.email, …)` made the caller gate believe the repository
 * declared a method called `substr`.
 *
 * Template literals get one for the same reason and one more. They span lines,
 * so cutting them at the first newline the way a quoted string is cut left
 * every line below the opener reading as code. And they are not wholly string
 * either: a `${…}` is code, and a name that appears ONLY inside one — a helper
 * called from an interpolation and nowhere else — has to stay visible, or
 * check:rename-residue reads it as a symbol that left the codebase and fails a
 * change that renamed nothing. So the text is blanked, the interpolations are
 * kept, and the walk tracks brace depth to tell where each one ends.
 */
export function codeOnly(source) {
	return scan(source, true);
}

/**
 * Blanks comments but keeps string literals intact — for callers that compare
 * code by content, where the strings are most of what distinguishes one line
 * from another.
 *
 * It has to be this scanner rather than a regex over `//`, and the reason is
 * concrete: `'http://example.com'` contains `//`, so a regex pass deletes the
 * rest of the line. Two test bodies asserting different URLs then normalize to
 * the same text and read as copies of each other.
 */
export function withoutComments(source) {
	return scan(source, false);
}

function scan(source, blankStrings) {
	let out = '';
	let index = 0;
	/*
	 * One entry per template literal the walk is currently inside, innermost
	 * last. `inExpression` says which half of the literal the walk is in — the
	 * text, which is a string, or a `${…}`, which is code — and `braceDepth`
	 * records the depth that expression opened at, so the `}` that closes it is
	 * the one that comes back to it rather than the first one seen.
	 */
	const templates = [];
	let braceDepth = 0;

	/** Blanks only when asked; `withoutComments` keeps every literal verbatim. */
	const maybeBlank = (text) => (blankStrings ? blankKeepingNewlines(text) : text);

	while (index < source.length) {
		const char = source[index];
		const inner = templates.at(-1);

		// Inside the text of a template literal: a string that spans lines.
		if (inner && !inner.inExpression) {
			if (char === '\\') {
				out += maybeBlank(source.slice(index, index + 2));
				index += 2;
			} else if (char === '`') {
				out += maybeBlank(char);
				templates.pop();
				index += 1;
			} else if (char === '$' && source[index + 1] === '{') {
				out += maybeBlank('${');
				inner.inExpression = true;
				inner.braceDepth = braceDepth;
				index += 2;
			} else {
				out += maybeBlank(char);
				index += 1;
			}
			continue;
		}

		if (source.startsWith('//', index)) {
			const end = source.indexOf('\n', index);
			const stop = end === -1 ? source.length : end;
			out += ' '.repeat(stop - index);
			index = stop;
		} else if (source.startsWith('/*', index)) {
			const end = source.indexOf('*/', index + 2);
			const stop = end === -1 ? source.length : end + 2;
			out += blankKeepingNewlines(source.slice(index, stop));
			index = stop;
		} else if (source.startsWith('<!--', index)) {
			const end = source.indexOf('-->', index + 4);
			const stop = end === -1 ? source.length : end + 3;
			out += blankKeepingNewlines(source.slice(index, stop));
			index = stop;
		} else if (source.startsWith('"""', index)) {
			const end = source.indexOf('"""', index + 3);
			const stop = end === -1 ? source.length : end + 3;
			out += maybeBlank(source.slice(index, stop));
			index = stop;
		} else if (char === '`' && source.indexOf('`', index + 1) !== -1) {
			/*
			 * Opens a template literal — but only when something closes it. A lone
			 * backtick is prose (a Svelte markup line quoting a command, a stray one
			 * in a fixture), and treating it as an opener would blank the rest of
			 * the file, which is the one failure mode this scanner must not have.
			 */
			templates.push({ inExpression: false, braceDepth });
			out += maybeBlank(char);
			index += 1;
		} else if (char === '"' || char === "'") {
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
			out += maybeBlank(source.slice(index, stop));
			index = stop;
		} else {
			if (char === '{') {
				braceDepth += 1;
			} else if (char === '}') {
				if (inner?.inExpression && braceDepth === inner.braceDepth) {
					// Closes the interpolation: back into the literal's text.
					inner.inExpression = false;
					out += maybeBlank(char);
					index += 1;
					continue;
				}
				braceDepth -= 1;
			}
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
