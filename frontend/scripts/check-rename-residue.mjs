import { execFileSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import path from 'node:path';
import process from 'node:process';
import { codeOnly, withoutAnnotations } from './lib/source-text.mjs';

/*
 * Fails a change that deletes or renames a symbol and leaves its old name
 * behind in source no compiler reads: a comment, a `@DisplayName`, a test name.
 *
 * The other dead-code gates look at the tree and ask what nothing calls any
 * more. This one looks at the *change* and asks what the change orphaned —
 * the only moment the answer is cheap. `c947dea` made the address book
 * application-wide and renamed `countByAccountIdGroupedByLabel` to
 * `countGroupedByLabel`; the javadoc naming the old method, seven
 * `@DisplayName` strings and two now-meaningless helper parameters survived
 * it, and were found two months later, by hand, in #299 and #307.
 *
 * How it decides, and why this way round: a candidate is a name a declaration
 * in the diff stopped declaring **and that was code at the base revision**,
 * and it is reported only when the name no longer appears **in code anywhere**
 * — code being the file with comments and string literals stripped out. Both
 * halves have to agree on what code is, or a name that only ever lived inside
 * a string literal is a declaration going out and invisible coming back in.
 * The obvious alternative, matching declarations
 * across the tree, is the fragile half: Spring Data repository methods carry
 * no access modifier, annotations sit between the modifier and the type, and
 * signatures wrap across lines, so every miss turns a live symbol into a false
 * "declared nowhere any more". Asking whether the name survives in code at all
 * cannot miss that way — a declaration IS code — and it also lets a name whose
 * declaration moved into an interface or a supertype pass, as it should.
 *
 * What is left, then, is a mention no compiler can reach: if it were a real
 * call, javac or tsc would already have failed on the missing symbol.
 *
 * Two scope decisions, both deliberate:
 *
 *   - Only source. Markdown is where this repo *records* removals by name;
 *     CHANGELOG.md and the audit change logs would fire on every commit.
 *   - Only names worth navigating by: at least five characters and a capital
 *     or an underscore, so `size`, `close` and `value` stay out.
 *
 * Usage: node scripts/check-rename-residue.mjs --base <ref>
 */

const repoRoot = path.join(process.cwd(), '..');

/*
 * Source the compiler does not police for us: comments, strings, test names.
 *
 * `dir/*.ext`, never `dir/**\/*.ext`: in a git pathspec the `**\/` form needs an
 * intervening directory, so it silently skips the files sitting directly in
 * `dir` — written the other way this list reached 10 of the 65 scripts.
 */
const SOURCE_PATHSPECS = [
	'backend/src/*.java',
	'frontend/src/*.ts',
	'frontend/src/*.svelte',
	'frontend/scripts/*.mjs'
];

/** Generated files: their names come from the schema, not from our renames. */
const GENERATED = ['frontend/src/lib/api/generated.ts', 'frontend/src/lib/api/schema.d.ts'];

/*
 * Candidate extraction, run over removed lines only. Generosity is close to
 * free here: a name that is not really a declaration is dropped either by the
 * "was it code at base" filter or by the "still in code" test a step later,
 * while a shape missed here is a rename nobody catches.
 */
const DECLARATIONS = [
	// Java and TypeScript type declarations.
	/\b(?:class|interface|enum|record)\s+([A-Za-z_$][\w$]*)/g,
	// Java members with an access modifier: type, name, opening paren.
	/\b(?:public|protected|private)\s+(?:static\s+|final\s+|synchronized\s+|abstract\s+|default\s+|native\s+)*(?:<[^>]+>\s*)?[\w$.<>[\],?\s]+?\s+([A-Za-z_$][\w$]*)\s*\(/g,
	// Java fields and constants.
	/\b(?:public|protected|private)\s+(?:static\s+|final\s+|volatile\s+|transient\s+)*[\w$.<>[\],?]+\s+([A-Za-z_$][\w$]*)\s*[=;]/g,
	// Abstract methods, which carry no modifier: Spring Data repositories and
	// plain interfaces. A type token, a name, arguments, a semicolon.
	/(?:^|[;{}])\s*[\w$.<>[\],?]+\s+([A-Za-z_$][\w$]*)\s*\([^;{}]*\)\s*(?:throws[\w$.,\s]+?)?;/gm,
	// TypeScript exports.
	/\bexport\s+(?:async\s+)?(?:function|const|let|class|interface|type|enum)\s+([A-Za-z_$][\w$]*)/g,
	// TypeScript re-export aliases: export { _ as t }.
	/\bexport\s*\{[^}]*?\bas\s+([A-Za-z_$][\w$]*)/g
];

/**
 * Names that pass the shape filter but say nothing about where they live.
 * Kept short on purpose: every entry is a hole in the gate.
 */
const TOO_COMMON = new Set([
	'equals',
	'hashCode',
	'toString',
	'compareTo',
	'getInstance',
	'valueOf',
	'Builder',
	'Companion'
]);

function isWorthTracking(name) {
	if (name.length < 5) return false;
	if (TOO_COMMON.has(name)) return false;
	// camelCase, PascalCase or CONSTANT_CASE — a name someone navigates by.
	return /[A-Z_]/.test(name);
}

function declaredNamesIn(source) {
	const text = withoutAnnotations(source);
	const names = new Set();
	for (const pattern of DECLARATIONS) {
		pattern.lastIndex = 0;
		let match;
		while ((match = pattern.exec(text)) !== null) {
			if (isWorthTracking(match[1])) names.add(match[1]);
		}
	}
	return names;
}

function arg(name) {
	const index = process.argv.indexOf(name);
	return index === -1 ? undefined : process.argv[index + 1];
}

const base = arg('--base');
if (!base) {
	console.error('Usage: node scripts/check-rename-residue.mjs --base <ref>');
	process.exit(2);
}

// A first push to a new branch has no "before" commit to diff against.
if (/^0{40}$/.test(base)) {
	console.log('Rename residue skipped: no base commit for this event.');
	process.exit(0);
}

function git(args) {
	return execFileSync('git', args, {
		cwd: repoRoot,
		encoding: 'utf8',
		maxBuffer: 64 * 1024 * 1024
	});
}

/**
 * A file as it stood at the base revision, or `null` when it did not exist
 * there. stderr is dropped rather than inherited: a path that is new in the
 * range is an ordinary outcome here, not something to print git's complaint
 * about.
 */
function showAtBase(file) {
	try {
		return execFileSync('git', ['show', `${mergeBase}:${file}`], {
			cwd: repoRoot,
			encoding: 'utf8',
			maxBuffer: 64 * 1024 * 1024,
			stdio: ['ignore', 'pipe', 'ignore']
		});
	} catch {
		return null;
	}
}

let mergeBase;
try {
	mergeBase = git(['merge-base', base, 'HEAD']).trim();
} catch {
	console.error(
		`Cannot resolve a merge base between "${base}" and HEAD. ` +
			'In CI the checkout needs full history (fetch-depth: 0).'
	);
	process.exit(2);
}

// -U0: only the changed lines, so unchanged context cannot read as a removal.
const diff = git(['diff', '-U0', `${mergeBase}..HEAD`, '--', ...SOURCE_PATHSPECS]);

/*
 * Candidates are collected per file, because each one is then held against
 * the code that file actually had at the base revision.
 *
 * The path comes from the `--- a/…` header rather than `+++ b/…`: it is the
 * pre-image name, which is what the base blob is stored under, and a deleted
 * file has no `+++` path at all.
 */
const removedPerFile = new Map();
let preImage = null;
for (const line of diff.split('\n')) {
	if (line.startsWith('--- ')) {
		const declared = line.slice(4).trim();
		preImage = declared === '/dev/null' ? null : declared.replace(/^a\//, '');
		continue;
	}
	if (line.startsWith('+++ ') || !line.startsWith('-') || !preImage) continue;
	const names = removedPerFile.get(preImage) ?? new Set();
	for (const name of declaredNamesIn(line.slice(1))) names.add(name);
	removedPerFile.set(preImage, names);
}

/*
 * A candidate has to have BEEN code, not merely declaration-shaped text on a
 * removed line — otherwise the two halves of this check disagree about what
 * code is. `codeOnly` decides whether a name survives, so a name that only
 * ever lived inside a string literal is invisible to that half while the raw
 * removed line makes it visible to this one, and a file whose subject matter
 * is code-in-strings then reports every fixture it touches. Not hypothetical:
 * check-java-callers.test.mjs is nothing but Java declarations inside JS
 * strings, and editing them cost #321 a waiver for a rename that never
 * happened.
 *
 * The base blob is read whole rather than blanking the removed line on its
 * own, because `codeOnly` is a scanner: a text block or a block comment is
 * only recognisable from the lines around it, and a lone diff line has none.
 */
const removedNames = new Set();
for (const [file, names] of removedPerFile) {
	if (names.size === 0) continue;
	const before = showAtBase(file);
	if (before === null) continue;
	const wasCode = new Set(codeOnly(before).match(/[A-Za-z_$][\w$]*/g) ?? []);
	for (const name of names) if (wasCode.has(name)) removedNames.add(name);
}

if (removedNames.size === 0) {
	console.log('Rename residue OK: the range removes no declaration.');
	process.exit(0);
}

const tracked = git(['ls-files', '--', ...SOURCE_PATHSPECS])
	.split('\n')
	.map((file) => file.trim())
	.filter((file) => file && !GENERATED.includes(file));

const sources = tracked.map((file) => ({
	file,
	text: readFileSync(path.join(repoRoot, file), 'utf8')
}));

const namesInCode = new Set();
for (const { text } of sources) {
	for (const token of codeOnly(text).match(/[A-Za-z_$][\w$]*/g) ?? []) namesInCode.add(token);
}

const orphaned = [...removedNames].filter((name) => !namesInCode.has(name)).sort();

const residue = new Map();
for (const name of orphaned) {
	const word = new RegExp(`\\b${name}\\b`);
	const hits = [];
	for (const { file, text } of sources) {
		const lines = text.split('\n');
		for (let index = 0; index < lines.length; index++) {
			if (word.test(lines[index])) hits.push({ file, line: index + 1, text: lines[index].trim() });
		}
	}
	if (hits.length > 0) residue.set(name, hits);
}

// Any commit in the range may carry the waiver; the reason lands in history.
const waiver = /^Rename-residue:\s*(.+)$/m.exec(git(['log', '--format=%B', `${mergeBase}..HEAD`]));

if (residue.size === 0) {
	console.log(
		`Rename residue OK: ${orphaned.length} name(s) left the code, nothing still mentions them.`
	);
	process.exit(0);
}

const report = [];
for (const [name, hits] of residue) {
	report.push(`  ${name} — gone from the code, still named in:`);
	for (const hit of hits.slice(0, 10)) {
		report.push(`      ${hit.file}:${hit.line}  ${hit.text.slice(0, 100)}`);
	}
	if (hits.length > 10) report.push(`      … and ${hits.length - 10} more`);
}

if (waiver) {
	console.log(`Rename residue waived: "${waiver[1].trim()}"`);
	for (const line of report) console.log(line);
	process.exit(0);
}

console.error('This change removed a symbol that source still names:');
for (const line of report) console.error(line);
console.error(
	'\nA compiler would have caught a real call, so every hit above is a comment, ' +
		'a string or a test name — what a rename silently leaves behind.\n' +
		'Update them, or record why the mention is deliberate with a commit trailer:\n' +
		'    Rename-residue: <reason>'
);
process.exitCode = 1;
