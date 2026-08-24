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

/**
 * The date of a message shown inside an expanded conversation. Same shape as
 * {@link formatMessageListDate} plus the clock, because a thread's replies
 * cluster in a day or two and the list format collapses those to one string:
 * three replies from the same Tuesday all render as that weekday's name alone,
 * which leaves the rows — and the checkbox labels built from them —
 * indistinguishable. Today's messages already render as the clock, so they are
 * left alone.
 */
export function formatThreadMemberDate(iso: string, locale = 'cs', now = new Date()): string {
	const date = new Date(iso);
	const listDate = formatMessageListDate(iso, locale, now);
	if (date.toDateString() === now.toDateString()) return listDate;
	return `${listDate} ${formatTime(date, locale)}`;
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
