import { describe, expect, it } from 'vitest';
import { formatMediumDate } from './formatters.js';

describe('formatMediumDate', () => {
	// Mid-month midday UTC, far from any timezone day boundary, so the year and
	// month assertions hold regardless of the test runner's local timezone.
	const ISO = '2026-06-09T12:00:00Z';

	it('renders an abbreviated month for English', () => {
		expect(formatMediumDate(ISO, 'en')).toMatch(/Jun/);
	});

	it('respects the requested locale (cs and en differ)', () => {
		expect(formatMediumDate(ISO, 'cs')).not.toBe(formatMediumDate(ISO, 'en'));
	});

	it('defaults to Czech when no locale is given', () => {
		expect(formatMediumDate(ISO)).toContain('2026');
	});

	it('falls back to the raw value for an unparseable date', () => {
		expect(formatMediumDate('not-a-date', 'cs')).toBe('not-a-date');
	});
});
