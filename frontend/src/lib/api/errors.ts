import { get } from 'svelte/store';
import { _ } from '#lib/i18n/index.js';
import { ApiError } from './client.js';
import { reportClientError } from './clientErrors.js';

/**
 * Normalizes anything thrown (Error | string | other) into an Error
 * instance. Use this when storing in a `error: Error | null` store.
 */
export function toError(err: unknown): Error {
	return err instanceof Error ? err : new Error(String(err));
}

/**
 * The error's own text, untranslated: what a log line or a technical message
 * built around it wants. Never what the interface shows — see
 * {@link toErrorMessage}.
 */
export function technicalMessage(err: unknown): string {
	return err instanceof Error ? err.message : String(err);
}

/**
 * The error's own text when that text is already in the interface's language,
 * else null.
 *
 * Only the backend's `problem.detail` is: it is localized via
 * `Accept-Language` (see the `error.*` keys in `messages_cs/en.properties`).
 * Everything else that reaches the interface — `ApiError`'s "API error 503:
 * Service Unavailable" fallback, a network failure, a message the frontend
 * wrote itself — is English, because code and its messages are English here.
 */
export function localizedErrorMessage(err: unknown): string | null {
	if (!(err instanceof ApiError)) return null;
	const detail = err.problem?.detail;
	return typeof detail === 'string' && detail.trim().length > 0 ? err.message : null;
}

/**
 * For the places that show an error as a diagnosis rather than as news — the
 * boot failure screen, the session line in About and the update failure
 * dialog — where the
 * technical text is the point and "unexpected error" would hide it: the
 * localized text when there is one, else the error's own text marked as the
 * English it is, for the element that shows it to carry as `lang`.
 */
export function diagnosticErrorText(err: unknown): { text: string; lang: 'en' | undefined } {
	const localized = localizedErrorMessage(err);
	return localized !== null
		? { text: localized, lang: undefined }
		: { text: technicalMessage(err), lang: 'en' };
}

/** Each error object goes to the log once, however often its message is shown. */
const reportedErrors = new WeakSet<object>();

/**
 * A human-readable message for the UI, always in the interface's language.
 *
 * English text inside a Czech interface was read with the Czech voice, and
 * none of the forty-odd places that show this message could mark it as
 * English (WCAG 3.1.2). So an error the backend did not localize is not shown
 * as written: an `ApiError` keeps its status code, which says something on its
 * own, and anything else becomes "unexpected error", its own text sent to the
 * backend log through `reportClientError` so it is not lost.
 */
export function toErrorMessage(err: unknown): string {
	const localized = localizedErrorMessage(err);
	if (localized !== null) return localized;

	const translate = get(_);
	if (err instanceof ApiError) {
		return translate('common.errorHttpStatus', { values: { status: err.status } });
	}

	if (typeof err !== 'object' || err === null || !reportedErrors.has(err)) {
		if (typeof err === 'object' && err !== null) reportedErrors.add(err);
		void reportClientError({
			kind: 'manual',
			error: err,
			context: { source: 'toErrorMessage' }
		});
	}
	return translate('common.errorUnexpected');
}
