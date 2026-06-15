// `locale` accepts the app-locale store value directly, including its `null`
// "not yet resolved" state, and falls back to Czech here so callers never have
// to repeat the `?? 'cs'` coalescing.
export function formatSize(bytes: number, locale: string | null | undefined = 'cs'): string {
	if (bytes < 1024) return `${bytes} B`;
	if (bytes < 1024 * 1024) return `${Math.round(bytes / 1024)} kB`;
	// Only the MB branch carries a fraction — localize its decimal separator
	// (cs "1,5 MB" vs en "1.5 MB"). B/kB are integers and stay separator-free.
	const mb = new Intl.NumberFormat(locale ?? 'cs', {
		minimumFractionDigits: 1,
		maximumFractionDigits: 1
	}).format(bytes / (1024 * 1024));
	return `${mb} MB`;
}

export function formatTime(date: Date, locale = 'cs', includeSeconds = false): string {
	return date.toLocaleTimeString(locale, {
		hour: '2-digit',
		minute: '2-digit',
		...(includeSeconds ? { second: '2-digit' as const } : {})
	});
}

function capitalize(value: string): string {
	if (!value) return value;
	return value.charAt(0).toLocaleUpperCase() + value.slice(1);
}

export function formatMessageListDate(iso: string, locale = 'cs', now = new Date()): string {
	const date = new Date(iso);
	const startOfToday = new Date(now.getFullYear(), now.getMonth(), now.getDate());
	const startOfTarget = new Date(date.getFullYear(), date.getMonth(), date.getDate());
	const dayDiff = Math.round(
		(startOfToday.getTime() - startOfTarget.getTime()) / (24 * 60 * 60 * 1000)
	);

	if (dayDiff === 0) {
		return formatTime(date, locale);
	}
	if (dayDiff === 1) {
		return capitalize(new Intl.RelativeTimeFormat(locale, { numeric: 'auto' }).format(-1, 'day'));
	}
	if (dayDiff >= 2 && dayDiff <= 6) {
		return capitalize(date.toLocaleDateString(locale, { weekday: 'long' }));
	}
	if (date.getFullYear() === now.getFullYear()) {
		return date.toLocaleDateString(locale, { day: 'numeric', month: 'numeric' });
	}
	return date.toLocaleDateString(locale, {
		day: 'numeric',
		month: 'numeric',
		year: 'numeric'
	});
}

export function formatNumericDate(iso: string, locale = 'cs'): string {
	return new Date(iso).toLocaleDateString(locale, {
		day: 'numeric',
		month: 'numeric',
		year: 'numeric'
	});
}

export function formatFullDateTime(iso: string, locale = 'cs'): string {
	return new Date(iso).toLocaleString(locale, {
		day: 'numeric',
		month: 'long',
		year: 'numeric',
		hour: '2-digit',
		minute: '2-digit'
	});
}

/**
 * Locale-aware "medium" date (cs: "9. 6. 2026", en: "Jun 9, 2026"). Used by the
 * contacts "updated" column. Falls back to the raw ISO string when the value
 * cannot be parsed — `Intl.format` throws on an invalid Date, and the cell
 * should degrade gracefully rather than break the row.
 */
export function formatMediumDate(iso: string, locale = 'cs'): string {
	const date = new Date(iso);
	if (Number.isNaN(date.getTime())) return iso;
	return new Intl.DateTimeFormat(locale, { dateStyle: 'medium' }).format(date);
}
