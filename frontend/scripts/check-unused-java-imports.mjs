import { execFileSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import path from 'node:path';

/*
 * Refuses an unused import in a tracked Java file.
 *
 * Nothing else in the build reports one. Spotless would — `removeUnusedImports`
 * is one of its steps — but this project deliberately leaves it off, because it
 * does not yet understand JEP 511 `import module` and rewrites those files
 * wrongly (see the note next to the plugin in backend/pom.xml). Error Prone and
 * NullAway have opinions about unused *variables*, not imports, and javac has
 * no lint for it at all. So the leftovers accumulate silently: at the time this
 * gate was written a dead-code cleanup had just orphaned two imports in
 * MailSyncService and `mvn clean verify` passed on them, and six more files
 * still carried leftovers from the commit that took `accountId` out of the
 * address book, months earlier. The only thing that had ever reported any of
 * this was a maintainer's IDE, which sees the files that happen to be open.
 *
 * TypeScript and Svelte are not in scope: `@typescript-eslint/no-unused-vars`
 * already covers them, and it does it with a real parser.
 *
 * Usage:
 *   node scripts/check-unused-java-imports.mjs
 */

const repoRoot = execFileSync('git', ['rev-parse', '--show-toplevel'], { encoding: 'utf8' }).trim();

/**
 * What a Java source file means, minus the parts that cannot reference a type.
 *
 * Scanned rather than regexed. A `//` inside a string literal does not start a
 * comment and a `"` inside a comment does not start a string, and no single
 * regex over the whole file keeps those two straight — the first version of
 * this check was a regex and reported every import in the repository as unused.
 */
const CODE = 0;
const LINE_COMMENT = 1;
const BLOCK_COMMENT = 2;
const STRING = 3;
const CHAR = 4;
const TEXT_BLOCK = 5;

function splitCodeAndComments(src) {
	let state = CODE;
	let code = '';
	let comments = '';
	let i = 0;

	while (i < src.length) {
		const c = src[i];
		const next = src[i + 1];

		if (state === CODE) {
			if (c === '/' && next === '/') {
				state = LINE_COMMENT;
				i += 2;
			} else if (c === '/' && next === '*') {
				state = BLOCK_COMMENT;
				i += 2;
			} else if (c === '"' && next === '"' && src[i + 2] === '"') {
				state = TEXT_BLOCK;
				i += 3;
			} else if (c === '"') {
				state = STRING;
				i += 1;
			} else if (c === "'") {
				state = CHAR;
				i += 1;
			} else {
				code += c;
				i += 1;
			}
			continue;
		}

		if (state === LINE_COMMENT) {
			if (c === '\n') {
				state = CODE;
				// Keep the newline so line-oriented handling downstream still works.
				code += c;
			} else {
				comments += c;
			}
			i += 1;
			continue;
		}

		if (state === BLOCK_COMMENT) {
			if (c === '*' && next === '/') {
				state = CODE;
				i += 2;
			} else {
				comments += c;
				i += 1;
			}
			continue;
		}

		if (state === TEXT_BLOCK) {
			if (c === '\\') {
				i += 2;
			} else if (c === '"' && next === '"' && src[i + 2] === '"') {
				state = CODE;
				i += 3;
			} else {
				i += 1;
			}
			continue;
		}

		// STRING or CHAR: contents are text, never a type reference.
		const closer = state === STRING ? '"' : "'";
		if (c === '\\') {
			i += 2;
		} else if (c === closer || c === '\n') {
			state = CODE;
			i += 1;
		} else {
			i += 1;
		}
	}

	return { code, comments };
}

/*
 * Javadoc tags that take a type or member the file must import to resolve.
 * Prose in a comment is deliberately NOT a usage: an import mentioned only in
 * a sentence is exactly the leftover this gate is looking for, and Java does
 * not need it. A {@link} does need it, so those targets count.
 */
const JAVADOC_REFERENCE_TAGS =
	/\{@(?:link|linkplain|value)\s+([^}]*)\}|@(?:see|throws|exception)\s+([^\s*]+)/g;

function javadocReferences(comments) {
	let refs = '';
	for (const m of comments.matchAll(JAVADOC_REFERENCE_TAGS)) {
		refs += ' ' + (m[1] ?? m[2] ?? '');
	}
	return refs;
}

/**
 * The single-type imports of a file, as {line, symbol}. Wildcard imports and
 * JEP 511 `import module java.base;` carry no single symbol to look for and are
 * left alone — reporting on what this check cannot see would be a guess.
 */
const SINGLE_TYPE_IMPORT = /^import\s+(?:static\s+)?[\w.]+\.(\w+)\s*;\s*$/;

function importsOf(lines) {
	const found = [];
	lines.forEach((line, index) => {
		const m = SINGLE_TYPE_IMPORT.exec(line);
		if (m) found.push({ line: index + 1, symbol: m[1] });
	});
	return found;
}

function unusedImports(src) {
	const lines = src.split('\n');
	const imports = importsOf(lines);
	if (imports.length === 0) return [];

	// The import block itself is not a usage of what it imports.
	const withoutImports = lines.filter((l) => !SINGLE_TYPE_IMPORT.test(l)).join('\n');
	const { code, comments } = splitCodeAndComments(withoutImports);
	const usage = code + javadocReferences(comments);

	return imports.filter(({ symbol }) => !new RegExp(`\\b${symbol}\\b`).test(usage));
}

const files = execFileSync('git', ['ls-files', '-z', '*.java'], {
	cwd: repoRoot,
	encoding: 'utf8',
	maxBuffer: 64 * 1024 * 1024
})
	.split('\0')
	.filter(Boolean);

const offenders = [];
for (const file of files) {
	const src = readFileSync(path.join(repoRoot, file), 'utf8');
	for (const hit of unusedImports(src)) {
		offenders.push({ file, ...hit });
	}
}

if (offenders.length > 0) {
	console.error('Unused imports:');
	for (const { file, line, symbol } of offenders) {
		console.error(`  - ${file}:${line} ${symbol}`);
	}
	console.error('');
	console.error('Nothing else in the build reports these: Spotless runs without');
	console.error('removeUnusedImports here (it does not yet support `import module`),');
	console.error('and javac has no lint for it. Delete the lines, or — if a symbol is');
	console.error('genuinely referenced — say so in code or in a javadoc tag, because');
	console.error('a mention in prose does not keep an import alive.');
	process.exitCode = 1;
} else {
	console.log(`Unused-import check OK: ${files.length} Java file(s), every import referenced.`);
}
