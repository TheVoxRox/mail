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

const violations = new Map(rules.map((rule) => [rule.id, []]));

for await (const file of sourceFiles(srcDir)) {
	const relative = path.relative(frontendDir, file);
	const lines = stripComments(await readFile(file, 'utf8')).split('\n');
	for (const rule of rules) {
		if (rule.skip?.(relative)) continue;
		lines.forEach((line, i) => {
			for (const match of line.matchAll(rule.pattern)) {
				if (rule.accept?.(match)) continue;
				violations.get(rule.id).push(`  ${relative}:${i + 1}  ${match[0]}`);
			}
		});
	}
}

const failed = rules.filter((rule) => violations.get(rule.id).length > 0);

if (failed.length > 0) {
	const total = failed.reduce((sum, rule) => sum + violations.get(rule.id).length, 0);
	throw new Error(
		`Found ${total} design-system violation${total === 1 ? '' : 's'}.\n` +
			failed
				.map((rule) => `${rule.id}: ${rule.hint}\n${violations.get(rule.id).join('\n')}`)
				.join('\n\n')
	);
}

console.log('Design OK: tokens, radius scale, one focus ring, tints on the ladder');
