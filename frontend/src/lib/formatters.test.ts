import { describe, expect, it } from 'vitest';
import { formatMessageListDate, formatSize, formatThreadMemberDate } from './formatters.js';

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

	it('falls back to Czech when the locale is null (unresolved app locale)', () => {
		expect(formatSize(1.5 * 1024 * 1024, null)).toBe('1,5 MB');
	});
});

describe('formatThreadMemberDate', () => {
	// Local-time constructor: the "same day" branch is a local-calendar question,
	// so a UTC literal would flip the assertion in a timezone behind UTC.
	const at = (day: number, hour: number, minute: number) =>
		new Date(2026, 5, day, hour, minute).toISOString();
	const now = new Date(2026, 5, 20, 12, 0);

	it('leaves today alone — the list format is already the clock', () => {
		const iso = at(20, 9, 30);
		expect(formatThreadMemberDate(iso, 'cs', now)).toBe(formatMessageListDate(iso, 'cs', now));
	});

	it('appends the clock to the day, so same-day replies differ', () => {
		// Two replies from the same Tuesday: the list format renders both as the
		// weekday alone, which is what made two checkboxes read identically.
		const morning = at(16, 9, 5);
		const evening = at(16, 19, 40);
		expect(formatMessageListDate(morning, 'cs', now)).toBe(
			formatMessageListDate(evening, 'cs', now)
		);
		expect(formatThreadMemberDate(morning, 'cs', now)).not.toBe(
			formatThreadMemberDate(evening, 'cs', now)
		);
		expect(formatThreadMemberDate(morning, 'cs', now)).toContain(
			formatMessageListDate(morning, 'cs', now)
		);
	});

	it('keeps the clock on dates older than a week', () => {
		const iso = at(1, 8, 15);
		expect(formatThreadMemberDate(iso, 'cs', now)).toMatch(/1\. 6\. \d{1,2}:\d{2}/);
	});
});
