import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import { strip, stripBlankLinesToNull, stripToNull } from './storedText.js';

interface StoredTextVector {
	why: string;
	input: string;
	stripToNull: string | null;
	stripBlankLinesToNull: string | null;
}

// The vectors the backend's StoredTextTest runs too: a value comes out of both
// the same, so what the form shows is what the backend stores.
const { vectors } = JSON.parse(
	readFileSync(
		new URL('../../../backend/src/test/resources/text/stored-text-vectors.json', import.meta.url),
		'utf8'
	)
) as { vectors: StoredTextVector[] };

describe('the stored-text vectors shared with the backend', () => {
	it.each(vectors)('$why', (vector) => {
		expect(stripToNull(vector.input)).toBe(vector.stripToNull);
		expect(strip(vector.input)).toBe(vector.stripToNull ?? '');
		expect(stripBlankLinesToNull(vector.input)).toBe(vector.stripBlankLinesToNull);
	});

	it('reads nothing given as no value', () => {
		expect(stripToNull(null)).toBeNull();
		expect(stripToNull(undefined)).toBeNull();
		expect(stripBlankLinesToNull(null)).toBeNull();
	});
});
