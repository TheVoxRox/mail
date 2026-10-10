import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';

/*
 * Contrast of colour tokens, computed from app.css itself so a token edited
 * there is judged as written. Only the pairs a WCAG criterion binds are here.
 */

const css = readFileSync(new URL('./app.css', import.meta.url), 'utf8');

/** The custom properties of one theme block (`:root` or `.dark`). */
function theme(selector: ':root' | '.dark'): Map<string, string> {
	const start = css.indexOf(`\n${selector} {`);
	const block = css.slice(start, css.indexOf('\n}', start));
	return new Map(
		[...block.matchAll(/^\s*--([\w-]+):\s*([^;]+);/gm)].map((match) => [match[1], match[2].trim()])
	);
}

type Rgba = [number, number, number, number];

/** `oklch(L C h)` or `oklch(L C h / A%)` to sRGB in 0..1, with alpha. */
function oklch(value: string): Rgba {
	const match = /^oklch\(([\d.]+) ([\d.]+) ([\d.]+)(?: \/ ([\d.]+)%)?\)$/.exec(value);
	if (!match) throw new Error(`not an oklch() colour: ${value}`);
	const [L, C, h] = [Number(match[1]), Number(match[2]), Number(match[3])];
	const alpha = match[4] === undefined ? 1 : Number(match[4]) / 100;
	const a = C * Math.cos((h * Math.PI) / 180);
	const b = C * Math.sin((h * Math.PI) / 180);
	const l = (L + 0.3963377774 * a + 0.2158037573 * b) ** 3;
	const m = (L - 0.1055613458 * a - 0.0638541728 * b) ** 3;
	const s = (L - 0.0894841775 * a - 1.291485548 * b) ** 3;
	const linear = [
		4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s,
		-1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s,
		-0.0041960863 * l - 0.7034186147 * m + 1.707614701 * s
	].map((c) => Math.min(1, Math.max(0, c)));
	const [r, g, bl] = linear.map((c) =>
		c <= 0.0031308 ? 12.92 * c : 1.055 * c ** (1 / 2.4) - 0.055
	);
	return [r, g, bl, alpha];
}

const over = (top: Rgba, bottom: Rgba): Rgba => [
	top[0] * top[3] + bottom[0] * (1 - top[3]),
	top[1] * top[3] + bottom[1] * (1 - top[3]),
	top[2] * top[3] + bottom[2] * (1 - top[3]),
	1
];

const luminance = ([r, g, b]: Rgba) => {
	const [lr, lg, lb] = [r, g, b].map((c) =>
		c <= 0.04045 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4
	);
	return 0.2126 * lr + 0.7152 * lg + 0.0722 * lb;
};

const contrast = (x: Rgba, y: Rgba) => {
	const [light, dark] = [luminance(x), luminance(y)].sort((p, q) => q - p);
	return (light + 0.05) / (dark + 0.05);
};

/*
 * The surfaces a text field, select or textarea is put on: the page and page
 * shells, cards (Surface), dialogs, the sidebars (their search fields) and the
 * muted bars over lists (the contact filter). The field's own fill is the page
 * colour, so the page stands for the inside of the edge as well.
 */
const SURFACES = ['background', 'card', 'popover', 'sidebar', 'muted'];

describe('WCAG 1.4.11: the edge of a form field', () => {
	for (const selector of [':root', '.dark'] as const) {
		const tokens = theme(selector);
		for (const surface of SURFACES) {
			it(`meets 3:1 against --${surface} in ${selector}`, () => {
				const background = oklch(tokens.get(surface) ?? '');
				const edge = over(oklch(tokens.get('input') ?? ''), background);
				expect(contrast(edge, background)).toBeGreaterThanOrEqual(3);
			});
		}
	}
});
