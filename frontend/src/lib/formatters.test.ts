import { describe, expect, it } from 'vitest';
import { formatMediumDate, formatSize } from './formatters.js';

describe('formatSize', () => {
	it('renders bytes and kB as separator-free integers', () => {
		expect(formatSize(512)).toBe('512 B');
		expect(formatSize(2048)).toBe('2 kB');
	});

	it('localizes the MB decimal separator (cs comma, en dot)', () => {
		const oneAndAHalfMb = 1.5 * 1024 * 1024;
		expect(formatSize(oneAndAHalfMb, 'cs')).toBe('1,5 MB');
		expect(formatSize(oneAndAHalfMb, 'en')).toBe('1.5 MB');
	});

	it('defaults to Czech when no locale is given', () => {
		expect(formatSize(1.5 * 1024 * 1024)).toBe('1,5 MB');
	});
});

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
