import { execFileSync } from 'node:child_process';
import { existsSync, readFileSync } from 'node:fs';
import path from 'node:path';
import process from 'node:process';

/*
 * An accessibility warning of the Svelte compiler may be silenced only with
 * the reason written next to it.
 *
 * `npm run check` runs svelte-check with --fail-on-warnings, so every warning
 * the compiler has about accessibility (a click with no key, an image with no
 * alt, a role with a missing property) stops the push. That leaves one way to
 * get a warning past it, `<!-- svelte-ignore a11y_… -->`, and that comment
 * says only which rule was switched off, never why. Nine of them had no reason
 * when this gate was written, and the reason was what a reader needed: on a
 * grid cell the click handler only keeps the click from reaching the row, so
 * there is no action a key would owe.
 *
 * The reason goes inside the comment, after the codes. In runes mode Svelte
 * reads the codes as a comma-separated list and everything after the first
 * code with no comma behind it as prose
 * (svelte/src/compiler/utils/extract_svelte_ignore.js), so the prose costs
 * nothing and moves with the comment:
 *
 *     <!-- svelte-ignore a11y_click_events_have_key_events (the row takes the keys) -->
 *
 * Only `a11y_` codes are judged. Other warnings are about the code, not about
 * a user. An ignore that no longer silences anything is eslint-plugin-svelte's
 * to fail (`svelte/no-unused-svelte-ignore`, on in its recommended set).
 *
 * Tracked and untracked files alike, so a new component is judged before its
 * first commit.
 *
 * Usage: node scripts/check-a11y-ignores.mjs
 */

const repoRoot = path.join(process.cwd(), '..');

/** Svelte's own test for a svelte-ignore comment. */
const SVELTE_IGNORE = /^\s*svelte-ignore\s/;
/**
 * An HTML comment in markup, a block or a line comment in a script. A script
 * comment has to start a line or follow whitespace, so the `//` of a URL in
 * an attribute and the `/*` of a glob in a string are not read as one.
 */
const COMMENTS = /<!--([\s\S]*?)-->|(?<!\S)\/\*([\s\S]*?)\*\/|(?<!\S)\/\/([^\n]*)/g;

function git(args) {
	return execFileSync('git', args, {
		cwd: repoRoot,
		encoding: 'utf8',
		maxBuffer: 64 * 1024 * 1024
	});
}

/**
 * The codes and the prose of one comment's text, parsed the way Svelte parses
 * them in runes mode: a code with a comma behind it continues the list, the
 * first one without ends it.
 */
function parseIgnore(text) {
	const rest = text.replace(SVELTE_IGNORE, '');
	const codes = [];
	let end = 0;
	for (const match of rest.matchAll(/([\w$-]+)(,)?/g)) {
		codes.push(match[1]);
		end = match.index + match[0].length;
		if (!match[2]) break;
	}
	return { codes, prose: rest.slice(end) };
}

const files = git([
	'ls-files',
	'--cached',
	'--others',
	'--exclude-standard',
	'--',
	'frontend/src/*.svelte'
])
	.split('\n')
	.map((file) => file.trim())
	.filter(Boolean)
	// Deleted in the working tree but still in the index.
	.filter((file) => existsSync(path.join(repoRoot, file)));

const unexplained = [];

for (const file of files) {
	const source = readFileSync(path.join(repoRoot, file), 'utf8');
	for (const match of source.matchAll(COMMENTS)) {
		const text = match[1] ?? match[2] ?? match[3];
		if (!SVELTE_IGNORE.test(text)) continue;
		const { codes, prose } = parseIgnore(text);
		const a11y = codes.filter((code) => /^a11y[_-]/.test(code));
		if (a11y.length === 0 || /[A-Za-z]/.test(prose)) continue;
		const line = source.slice(0, match.index).split('\n').length;
		unexplained.push(`  ${file}:${line}  ${a11y.join(', ')}`);
	}
}

if (unexplained.length > 0) {
	console.error(
		`Found ${unexplained.length} accessibility warning${unexplained.length === 1 ? '' : 's'} ` +
			'silenced without a reason:\n' +
			`${unexplained.join('\n')}\n\n` +
			'Fix the markup, or say after the codes why the warning does not apply:\n' +
			'  <!-- svelte-ignore a11y_click_events_have_key_events (the row takes the keys) -->\n' +
			'Svelte reads everything after the last code without a comma as prose.'
	);
	process.exit(1);
}

console.log(`A11y ignores OK: ${files.length} Svelte file(s), every silenced warning says why.`);
