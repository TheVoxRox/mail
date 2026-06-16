/**
 * Guards the typographic scale against drift back into ad-hoc font sizes.
 *
 * Font sizes must come from the semantic scale defined in `src/app.css`:
 * `text-caption` (11px) → `text-xs` (12px) → `text-sm` (14px, default body)
 * → `text-title` (15px, page title) → `text-base` (16px, section/sidebar)
 * → `text-lg` (18px, message subject).
 *
 * Arbitrary Tailwind font-size utilities — `text-[0.72rem]`, `text-[11px]` — are
 * how the scale eroded before (a dozen near-identical one-off sizes nobody could
 * tell apart). This check fails the build on any such utility so a new size is a
 * deliberate scale addition in app.css, not an inline accident.
 *
 * If a genuinely new size is needed, add a `--text-*` token to app.css and use
 * the generated utility instead of widening this allowlist.
 */

import { readdir, readFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const scriptDir = path.dirname(fileURLToPath(import.meta.url));
const srcDir = path.resolve(scriptDir, '..', 'src');

// Matches font-size arbitrary values only: text-[<number>rem] / text-[<number>px].
// Non-size arbitrary `text-[...]` (e.g. text-[#d93025] brand colors) is allowed.
const arbitraryFontSize = /\btext-\[[0-9.]+(?:rem|px)\]/g;

async function* svelteFiles(dir) {
	for (const entry of await readdir(dir, { withFileTypes: true })) {
		const full = path.join(dir, entry.name);
		if (entry.isDirectory()) {
			yield* svelteFiles(full);
		} else if (entry.isFile() && entry.name.endsWith('.svelte')) {
			yield full;
		}
	}
}

const violations = [];

for await (const file of svelteFiles(srcDir)) {
	const lines = (await readFile(file, 'utf8')).split('\n');
	lines.forEach((line, i) => {
		for (const match of line.matchAll(arbitraryFontSize)) {
			const rel = path.relative(path.resolve(scriptDir, '..'), file);
			violations.push(`  ${rel}:${i + 1}  ${match[0]}`);
		}
	});
}

if (violations.length > 0) {
	throw new Error(
		`Found ${violations.length} arbitrary font-size utilit${violations.length === 1 ? 'y' : 'ies'}. ` +
			`Use the semantic scale (text-caption/xs/sm/title/base/lg) from src/app.css instead.\n` +
			`${violations.join('\n')}\n` +
			`If a new size is genuinely needed, add a --text-* token to app.css.`
	);
}

console.log('Typography OK: no arbitrary font-size utilities');
