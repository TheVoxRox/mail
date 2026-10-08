import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import { cn, tv } from './utils.js';

/** Font sizes app.css adds to Tailwind's scale: `--text-<name>: <size>;`. */
const appCssFontSizes = [
	...readFileSync(new URL('../app.css', import.meta.url), 'utf8').matchAll(
		/^\s*--text-([a-z0-9]+(?:-[a-z0-9]+)*)\s*:/gm
	)
].map((match) => match[1]);

describe('class merging and the font sizes app.css adds', () => {
	it('finds the sizes it checks in app.css', () => {
		// Guards the regex: an empty list would pass every test below.
		expect(appCssFontSizes).toContain('caption');
	});

	it.each(appCssFontSizes)('cn keeps text-%s beside a text colour', (size) => {
		expect(cn(`text-${size} text-muted-foreground`)).toBe(`text-${size} text-muted-foreground`);
	});

	it.each(appCssFontSizes)('cn lets text-%s replace another font size', (size) => {
		expect(cn('text-sm', `text-${size}`)).toBe(`text-${size}`);
	});

	it.each(appCssFontSizes)('tv keeps text-%s beside a colour from a variant', (size) => {
		const variants = tv({
			base: `text-${size}`,
			variants: { tone: { muted: 'text-muted-foreground' } }
		});
		expect(variants({ tone: 'muted' })).toBe(`text-${size} text-muted-foreground`);
	});
});
