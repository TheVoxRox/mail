import { execFileSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import path from 'node:path';
import process from 'node:process';
import { codeOnly, withoutComments } from './lib/source-text.mjs';

/*
 * Two ways a test stops testing what its name says, both invisible to a green
 * suite because the test still passes — or no longer runs at all.
 *
 *   1. A duplicate. Two tests in one file whose bodies are identical assert
 *      the same thing twice under different names, and the second name is a
 *      claim nothing backs. #307 found `sameEmailOtherAccountAllowed` byte for
 *      byte identical to the neighbour eight lines above it: a refactor had
 *      removed the account parameter that used to make them differ, and the
 *      suite stayed green through all of it.
 *   2. A test switched off. `@Disabled`, `it.skip`, `describe.only` — the
 *      suite reports "skipped" in a line nobody reads, and the coverage the
 *      test was written for is gone without a single failure.
 *
 * Scope decisions:
 *
 *   - Duplicates are compared **within one file**. The Google and Microsoft
 *     token suites share sixteen identical bodies on purpose: same contract,
 *     two providers. Across files, sameness is the point; inside one file, it
 *     is a copy nobody finished editing.
 *   - Conditional skips pass. `it.skipIf(!canSymlink)` states its condition in
 *     the call and reads as "this machine cannot run me", which is a fact, not
 *     a deferral. An unconditional skip states nothing.
 *   - A skip may be kept with a justification: `@Disabled("reason")` in Java,
 *     or a `test-skip:` comment on the line above in TypeScript.
 *
 * Usage: node scripts/check-test-claims.mjs
 */

const repoRoot = path.join(process.cwd(), '..');

const TEST_PATHSPECS = [
	'backend/src/test/*.java',
	'frontend/src/*.test.ts',
	'frontend/src/*.e2e.ts',
	'frontend/scripts/*.test.mjs'
];

/** Bodies below this are boilerplate (a single call, a single expect). */
const MIN_BODY_LENGTH = 60;

const JAVA_TEST =
	/@(?:Test|ParameterizedTest|RepeatedTest)\b[\s\S]{0,600}?\b(?:void)\s+(\w+)\s*\([^)]*\)[^{;]*\{/g;
const TS_TEST =
	/\b(?:it|test)\s*\(\s*(['"`])((?:[^\\]|\\.)*?)\1\s*,\s*(?:async\s*)?\([^)]*\)\s*=>\s*\{/g;

const JAVA_SKIP = /@Disabled\b(?:\(\s*(["'])((?:[^\\]|\\.)*?)\1\s*\))?/g;
/*
 * The trailing `\b` is what lets `skipIf` through: `skip` is followed by a
 * word character there, so the boundary never matches. Written as `\.skip\w*`
 * this rule would report every conditional skip in the gate suites.
 */
const TS_SKIP = /\b(?:it|test|describe)\.(skip|only|todo|fixme)\b/g;

function git(args) {
	return execFileSync('git', args, {
		cwd: repoRoot,
		encoding: 'utf8',
		maxBuffer: 64 * 1024 * 1024
	});
}

/** The block starting at `start`, by brace matching over comment-free text. */
function blockAt(text, start) {
	let depth = 0;
	for (let index = start; index < text.length; index += 1) {
		if (text[index] === '{') depth += 1;
		else if (text[index] === '}') {
			depth -= 1;
			if (depth === 0) return text.slice(start, index + 1);
		}
	}
	return null;
}

const lineOf = (text, index) => text.slice(0, index).split('\n').length;

const files = git(['ls-files', '--', ...TEST_PATHSPECS])
	.split('\n')
	.map((file) => file.trim())
	.filter(Boolean);

const duplicates = [];
const skips = [];

for (const file of files) {
	const raw = readFileSync(path.join(repoRoot, file), 'utf8');
	/*
	 * Comments out, strings in. Two tests differing only in the URL they reject
	 * differ only inside their string literals, and blanking those would make
	 * them read as copies — which is exactly the false report this gate must
	 * not produce.
	 */
	const text = withoutComments(raw);
	const rawLines = raw.split('\n');
	const isJava = file.endsWith('.java');

	const bodies = new Map();
	const pattern = isJava ? JAVA_TEST : TS_TEST;
	pattern.lastIndex = 0;
	let match;
	while ((match = pattern.exec(text)) !== null) {
		const open = text.indexOf('{', match.index + match[0].length - 1);
		const body = blockAt(text, open);
		if (!body) continue;
		const normalized = body.replace(/\s+/g, ' ').trim();
		if (normalized.length < MIN_BODY_LENGTH) continue;
		const name = isJava ? match[1] : match[2];
		const entry = { name, line: lineOf(text, match.index) };
		const seen = bodies.get(normalized);
		if (seen) duplicates.push({ file, first: seen, second: entry });
		else bodies.set(normalized, entry);
	}

	/*
	 * The two rules need different views of the same file. Duplicates compare
	 * bodies, so string contents have to survive. A skip is a call, so string
	 * contents have to go — otherwise the sentence "it.skip is not allowed"
	 * inside an assertion reports as a switched-off test. Java keeps its
	 * strings here because `@Disabled("reason")` carries the justification in
	 * one, and it is read a few lines below.
	 */
	const skipSource = isJava ? text : codeOnly(raw);
	const skipPattern = isJava ? JAVA_SKIP : TS_SKIP;
	skipPattern.lastIndex = 0;
	while ((match = skipPattern.exec(skipSource)) !== null) {
		const line = lineOf(skipSource, match.index);
		if (isJava) {
			// @Disabled("...") carries its reason as the annotation argument.
			if (match[2]?.trim()) continue;
			skips.push({ file, line, what: '@Disabled' });
			continue;
		}
		const above = rawLines[line - 2] ?? '';
		if (/test-skip:\s*\S/.test(above)) continue;
		skips.push({ file, line, what: `.${match[1]}` });
	}
}

if (duplicates.length === 0 && skips.length === 0) {
	console.log(
		`Test claims OK: ${files.length} test file(s), no duplicate bodies, none switched off.`
	);
	process.exit(0);
}

if (duplicates.length > 0) {
	console.error('Tests whose body is identical to another test in the same file:\n');
	for (const hit of duplicates) {
		console.error(`  ${hit.file}`);
		console.error(`      ${hit.second.line}  ${hit.second.name}`);
		console.error(`      ${hit.first.line}  ${hit.first.name}  (same body)`);
	}
	console.error(
		'\n  Two names, one assertion: the second name claims something no code backs.\n' +
			'  Either make the bodies differ, or delete the one that stopped saying anything.\n'
	);
}

if (skips.length > 0) {
	console.error('Tests switched off without saying why:\n');
	for (const hit of skips) console.error(`  ${hit.file}:${hit.line}  ${hit.what}`);
	console.error(
		'\n  A skipped test is absent coverage that reports as a pass.\n' +
			'  Give the reason — @Disabled("…") in Java, a `test-skip: …` comment above\n' +
			'  the call in TypeScript — or use skipIf, which states its condition.\n'
	);
}

process.exitCode = 1;
