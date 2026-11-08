import { createRequire } from 'node:module';
import path from 'node:path';

/**
 * Tells a change to comments and layout from a change to code, for the
 * languages where that can be answered exactly, so that check:audits does not
 * ask a reviewer to defend a javadoc edit as if it were a code change.
 *
 * The direction of error is the whole design. Calling a comment edit "code" costs
 * a paragraph in docs/audit-freshness.json, the same cost as before this file
 * existed. Calling a code edit "a comment" hides it from the gate that exists
 * to make it visible. So every function below answers `null`, meaning "cannot
 * tell", for anything it does not understand exactly, and the gate treats
 * `null` as drift.
 *
 * That rules out the generic scanner in ./source-text.mjs, which is built to
 * err the other way, towards keeping code: it has no notion of a TypeScript
 * regex literal, so it reads the `//` inside `/^https?:\/\//` as a line comment
 * and blanks everything after it on the line. That is harmless where it is
 * used. Here it would let a change to the rest of that line through as a comment.
 *
 * Covered: Java, TypeScript and JavaScript, and .properties. Everything else
 * (Svelte, Rust, YAML, HTML, JSON) is compared byte for byte, as before. A
 * YAML comment and a `#` line inside a `run: |` block look the same to
 * anything short of a YAML parser, and the repo has no YAML parser as a direct
 * dependency.
 */

const require = createRequire(import.meta.url);
let typescript;

/**
 * The file's code with comments and layout normalized away, or `null` when
 * this file's language is not covered or its text cannot be read exactly.
 * Two versions of a file differ only in comments when both are non-null and
 * equal.
 */
export function codeWithoutComments(filePath, text) {
	switch (path.extname(filePath).toLowerCase()) {
		case '.java':
			return javaCode(text);
		case '.ts':
		case '.mts':
		case '.cts':
			return scriptCode(filePath, text, 'TS');
		case '.js':
		case '.mjs':
		case '.cjs':
			return scriptCode(filePath, text, 'JS');
		case '.properties':
			return propertiesCode(text);
		default:
			return null;
	}
}

/** Whether `before` and `after` are the same code, read as their compiler reads it. */
export function differsOnlyInComments(filePath, before, after) {
	const left = codeWithoutComments(filePath, before);
	if (left === null) return false;
	const right = codeWithoutComments(filePath, after);
	return right !== null && left === right;
}

/*
 * Java's grammar makes a lexer exact where it is approximate for JavaScript:
 * there are no regex literals and no string interpolation, so a `/` outside a
 * literal or a comment is always an operator. Comments become one space and
 * runs of whitespace between tokens become one space, which is all javac sees
 * of them. Literals stay verbatim.
 */
const JAVA_UNICODE_ESCAPE = /\\+u+([0-9a-fA-F]{4})/g;
/*
 * javac decodes \uXXXX before it lexes anything, so `// \u000a code` is a
 * comment followed by code, and a decoded quote or slash can open or close a
 * literal or a comment. A lexer that does not decode would read those wrong,
 * so a file with an escape that decodes to one of these characters is not
 * judged at all.
 */
const JAVA_STRUCTURAL = new Set(['\n', '\r', '"', "'", '\\', '/', '*']);

function javaCode(source) {
	for (const match of source.matchAll(JAVA_UNICODE_ESCAPE)) {
		if (JAVA_STRUCTURAL.has(String.fromCharCode(Number.parseInt(match[1], 16)))) return null;
	}

	const pieces = [];
	let code = '';
	const flushCode = () => {
		pieces.push(code.replace(/\s+/g, ' '));
		code = '';
	};

	let index = 0;
	while (index < source.length) {
		const char = source[index];
		if (source.startsWith('//', index)) {
			index = lineEnd(source, index);
			code += ' ';
		} else if (source.startsWith('/*', index)) {
			const end = source.indexOf('*/', index + 2);
			if (end === -1) return null;
			index = end + 2;
			code += ' ';
		} else if (source.startsWith('"""', index)) {
			let scan = index + 3;
			while (scan < source.length && !source.startsWith('"""', scan)) {
				scan += source[scan] === '\\' ? 2 : 1;
			}
			if (scan >= source.length) return null;
			flushCode();
			pieces.push(source.slice(index, scan + 3));
			index = scan + 3;
		} else if (char === '"' || char === "'") {
			let scan = index + 1;
			while (scan < source.length && source[scan] !== char) {
				if (source[scan] === '\n' || source[scan] === '\r') return null;
				scan += source[scan] === '\\' ? 2 : 1;
			}
			if (scan >= source.length) return null;
			flushCode();
			pieces.push(source.slice(index, scan + 1));
			index = scan + 1;
		} else {
			code += char;
			index += 1;
		}
	}
	flushCode();
	return pieces.join('').trim();
}

/** The index of the first line terminator at or after `from`, or the length. */
function lineEnd(source, from) {
	const ends = [source.indexOf('\n', from), source.indexOf('\r', from)].filter((at) => at !== -1);
	return ends.length > 0 ? Math.min(...ends) : source.length;
}

/*
 * For TypeScript and JavaScript, the parser and the printer of the compiler
 * the repo already builds with: parse, then print the tree with comments
 * removed. A regex literal, a template literal holding `//` and automatic
 * semicolon insertion are the compiler's to get right, not a scanner's.
 *
 * `setParentNodes` is what keeps literals as written. Without parent links the
 * printer re-renders a literal from its value, so `0x10` and `16` print the
 * same. They are the same value, but that is a weaker claim than "only
 * comments changed", and this function makes only that one.
 *
 * Two of the comments a TypeScript compiler reads are not prose: pragmas such
 * as `/// <reference>` and `@jsx`. A file carrying any is not judged.
 * `parseDiagnostics` and `pragmas` are internal fields, and a compiler that
 * stops providing them answers `null` for every file. That is the safe
 * failure, and the gate's suite catches it, since its comment-only cases then
 * go red.
 */
function scriptCode(filePath, source, kind) {
	typescript ??= require('typescript');
	const ts = typescript;
	const file = ts.createSourceFile(
		filePath,
		source,
		ts.ScriptTarget.Latest,
		true,
		kind === 'TS' ? ts.ScriptKind.TS : ts.ScriptKind.JS
	);
	if (!Array.isArray(file.parseDiagnostics) || file.parseDiagnostics.length > 0) return null;
	if (!(file.pragmas instanceof Map) || file.pragmas.size > 0) return null;
	return ts.createPrinter({ removeComments: true }).printFile(file);
}

/*
 * java.util.Properties: a natural line whose first non-blank character is `#`
 * or `!` is a comment, and a blank line is nothing. Two exceptions keep a line
 * that looks like a comment. A line continuing a value whose previous line
 * ended in an odd number of backslashes is part of that value. And Spring
 * Boot reads `#---` and `!---` as a document separator, which changes which
 * keys apply together.
 *
 * Every other line is kept verbatim, trailing whitespace included, because
 * that whitespace is part of the value. The blank characters are the three
 * Properties itself skips: a no-break space before a `#` makes the line a key.
 */
function propertiesCode(source) {
	const kept = [];
	let continuing = false;
	for (const line of source.split(/\r\n|\r|\n/)) {
		if (!continuing) {
			const content = line.replace(/^[ \t\f]+/, '');
			if (content === '') continue;
			if (/^[#!]/.test(content) && !/^[#!]---/.test(content)) continue;
		}
		kept.push(line);
		continuing = /(?:^|[^\\])(?:\\\\)*\\$/.test(line);
	}
	return kept.join('\n');
}
