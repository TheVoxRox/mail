/**
 * Guards the four parts of the visual system that a type checker cannot see.
 *
 * Sibling of `check-typography.mjs`, which does the same for font sizes. Each
 * rule here is one that had already been broken by the time it was written —
 * the app had four spellings of "success", six of "this element is focused",
 * ten alphas of `bg-muted` and a radius that belonged to no scale:
 *
 *   1. Colours come from the tokens in `app.css`, never from Tailwind's own
 *      palette. A palette colour has to be re-picked by hand for dark mode and
 *      nothing checks the pair was ever contrast-tested.
 *   2. Corner radii come from the radius scale. Tailwind's bare `rounded` is
 *      its own 4px default and is on no step of it.
 *   3. The focus indicator lives in `ui/focus-ring` and nowhere else. This is
 *      the rule with teeth: a focus ring is a WCAG 1.4.11 contrast target, and
 *      the tinted copies this replaced measured 1.61:1.
 *   4. A token tint steps in multiples of 10. Finer steps are invisible on
 *      screen and deliberate-looking in a diff, which is how ten of them
 *      accumulated.
 *   5. A grid row takes its columns from the grid, never from itself. This one
 *      is scoped to an element rather than a line — see `openingTags`.
 *
 * Adding a colour, a radius or a tint step means changing `app.css`, which is
 * the point: it is one file to read before picking, and one place to argue in.
 */

import { readdir, readFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const scriptDir = path.dirname(fileURLToPath(import.meta.url));
const frontendDir = path.resolve(scriptDir, '..');
const srcDir = path.resolve(frontendDir, 'src');

/** The only module allowed to spell out a focus indicator. */
const FOCUS_RING_MODULE = path.join('src', 'lib', 'components', 'ui', 'focus-ring');
/**
 * The only module allowed to name palette colours. Avatar hues are
 * categorical — eight colours whose whole job is to differ from each other —
 * so there is no semantic token they could come from. The exception is a path,
 * not a hue list, so a second categorical palette has to be moved next to this
 * one rather than sprinkled through a component.
 */
const AVATAR_MODULE = path.join('src', 'lib', 'components', 'ui', 'avatar');

/**
 * Blanks out comments, keeping line numbers intact. Without this the rules
 * match their own documentation: prose about `rounded` or `outline-none`
 * reads exactly like the class it warns about.
 */
function stripComments(text) {
	const blank = (match) => match.replace(/[^\n]/g, ' ');
	return text
		.replace(/\/\*[\s\S]*?\*\//g, blank)
		.replace(/<!--[\s\S]*?-->/g, blank)
		.replace(/(^|[^:])\/\/[^\n]*/g, (match, lead) => lead + blank(match.slice(lead.length)));
}

const PALETTE_HUES = [
	'slate',
	'gray',
	'zinc',
	'neutral',
	'stone',
	'red',
	'orange',
	'amber',
	'yellow',
	'lime',
	'green',
	'emerald',
	'teal',
	'cyan',
	'sky',
	'blue',
	'indigo',
	'violet',
	'purple',
	'fuchsia',
	'pink',
	'rose'
];
const COLOR_PREFIX =
	'bg|text|border|ring|fill|stroke|divide|from|to|via|outline|placeholder|caret|decoration|accent|shadow';

const RADIUS_STEPS = ['none', 'sm', 'md', 'lg', 'xl', '2xl', 'full'];
const RADIUS_SIDES = ['t', 'r', 'b', 'l', 'tl', 'tr', 'br', 'bl', 's', 'e', 'ss', 'se', 'es', 'ee'];

const rules = [
	{
		id: 'palette-colour',
		// `bg-red-500` and friends. Tokens have no numeric step, so they never match.
		pattern: new RegExp(`\\b(?:${COLOR_PREFIX})-(?:${PALETTE_HUES.join('|')})-\\d{2,3}\\b`, 'g'),
		hint: 'Use a token from app.css (--primary, --destructive, --success, …), not a Tailwind palette colour.',
		skip: (relative) => relative.startsWith(AVATAR_MODULE)
	},
	{
		id: 'off-scale-radius',
		// `rounded`, an optional side, an optional step. The step is what is
		// judged: absent means Tailwind's bare 4px default, and `2xl` is on the
		// scale only because app.css defines `--radius-2xl`.
		pattern: new RegExp(
			`\\brounded\\b(?:-(?:${RADIUS_SIDES.join('|')})\\b)?(?:-(\\[[^\\]]*\\]|[a-z0-9]+))?`,
			'g'
		),
		accept: (match) => {
			const step = match[1];
			if (!step) return false;
			// An arbitrary value is fine as long as it is derived from the scale.
			if (step.startsWith('[')) return step.includes('--radius');
			return RADIUS_STEPS.includes(step);
		},
		hint: `Use the radius scale: rounded-${RADIUS_STEPS.join(' / rounded-')}.`
	},
	{
		id: 'inline-focus-ring',
		pattern:
			/(?:focus|focus-visible|group-focus-visible|peer-focus-visible):(?:ring|outline)[-\w[\]/.]*/g,
		hint: `Import focusRing / focusRingInset from ${FOCUS_RING_MODULE.replaceAll(path.sep, '/')} instead.`,
		skip: (relative) => relative.startsWith(FOCUS_RING_MODULE)
	},
	{
		id: 'outline-none',
		// Tailwind 4 split these: `outline-none` drops the transparent outline
		// that forced-colors mode relies on, `outline-hidden` keeps it.
		pattern: /\boutline-none\b/g,
		hint: 'Use outline-hidden — it keeps an outline under forced-colors, where a ring is not painted.'
	},
	{
		id: 'off-ladder-tint',
		pattern: new RegExp(`\\b(?:${COLOR_PREFIX})-[a-z-]+/(\\d+)\\b`, 'g'),
		accept: (match) => Number(match[1]) % 10 === 0,
		hint: 'Token tints step in multiples of 10 (see the comment above :root in app.css).'
	}
];

/**
 * Yields the opening tag of every element, with the line it starts on.
 *
 * A scanner rather than a regex because the two attributes this has to see
 * together sit far apart: on a data row `role="row"` and the class expression
 * are eight lines and a `cn(...)` call away from each other. Scanning to the
 * first `>` would not do either — an event handler's arrow (`onclick={(e) =>
 * …}`) puts a `>` inside the tag — so the walk carries quote and brace state
 * and ends the tag only at brace depth zero.
 */
function* openingTags(text) {
	let line = 1;
	for (let i = 0; i < text.length; i++) {
		if (text[i] === '\n') {
			line++;
			continue;
		}
		if (text[i] !== '<' || !/[A-Za-z]/.test(text[i + 1] ?? '')) continue;

		const startLine = line;
		let depth = 0;
		let quote = null;
		let j = i + 1;
		for (; j < text.length; j++) {
			const char = text[j];
			if (char === '\n') line++;
			if (quote) {
				if (char === quote) quote = null;
				continue;
			}
			if (char === '"' || char === "'" || char === '`') quote = char;
			else if (char === '{') depth++;
			else if (char === '}') depth--;
			else if (depth === 0 && char === '>') break;
		}
		if (j >= text.length) return; // Unterminated tag: nothing further to trust.
		yield { line: startLine, attributes: text.slice(i, j + 1) };
		i = j;
	}
}

/**
 * Rules that need a whole element rather than a line. Kept separate because
 * the shape differs: a line rule reports the text it matched, an element rule
 * reports the pair of attributes that may not appear together.
 */
const elementRules = [
	{
		id: 'row-grid-columns',
		// The three data grids keep their tracks on the container and give each
		// row `grid-cols-subgrid`. Nothing stopped a fourth row-level component
		// from declaring its own tracks instead, and columns that drift apart
		// do it silently — the same way radii and focus rings did before the
		// rules above existed. Literal `role="row"` only: a computed role is
		// not something this can read, and guessing at one would be worse than
		// not looking.
		test: (attributes) =>
			/\brole=(["'])row\1/.test(attributes) && attributes.includes('grid-cols-['),
		label: 'grid-cols-[…] on role="row"',
		hint: 'A row takes its columns from the grid it is in: put the tracks on the container and grid-cols-subgrid on the row.'
	}
];

async function* sourceFiles(dir) {
	for (const entry of await readdir(dir, { withFileTypes: true })) {
		const full = path.join(dir, entry.name);
		if (entry.isDirectory()) {
			if (entry.name === 'test-fixtures' || entry.name === 'test-stubs') continue;
			yield* sourceFiles(full);
			continue;
		}
		if (!entry.isFile()) continue;
		if (!/\.(svelte|ts)$/.test(entry.name)) continue;
		if (/\.(test|e2e)\.ts$/.test(entry.name)) continue;
		yield full;
	}
}

const allRules = [...rules, ...elementRules];
const violations = new Map(allRules.map((rule) => [rule.id, []]));

for await (const file of sourceFiles(srcDir)) {
	const relative = path.relative(frontendDir, file);
	const source = stripComments(await readFile(file, 'utf8'));
	const lines = source.split('\n');
	for (const rule of rules) {
		if (rule.skip?.(relative)) continue;
		lines.forEach((line, i) => {
			for (const match of line.matchAll(rule.pattern)) {
				if (rule.accept?.(match)) continue;
				violations.get(rule.id).push(`  ${relative}:${i + 1}  ${match[0]}`);
			}
		});
	}
	// Markup only — an element rule needs an element, and a type parameter in a
	// .ts file reads like a tag to any scanner.
	if (!file.endsWith('.svelte')) continue;
	const tags = [...openingTags(source)];
	for (const rule of elementRules) {
		if (rule.skip?.(relative)) continue;
		for (const { line, attributes } of tags) {
			if (!rule.test(attributes)) continue;
			violations.get(rule.id).push(`  ${relative}:${line}  ${rule.label}`);
		}
	}
}

const failed = allRules.filter((rule) => violations.get(rule.id).length > 0);

if (failed.length > 0) {
	const total = failed.reduce((sum, rule) => sum + violations.get(rule.id).length, 0);
	throw new Error(
		`Found ${total} design-system violation${total === 1 ? '' : 's'}.\n` +
			failed
				.map((rule) => `${rule.id}: ${rule.hint}\n${violations.get(rule.id).join('\n')}`)
				.join('\n\n')
	);
}

console.log(
	'Design OK: tokens, radius scale, one focus ring, tints on the ladder, rows on subgrid'
);
