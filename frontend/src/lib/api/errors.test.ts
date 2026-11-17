import { get } from 'svelte/store';
import { beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('./clientErrors.js', () => ({
	reportClientError: vi.fn(() => Promise.resolve('sent'))
}));

import { _ } from '#lib/i18n/index.js';
import { ApiError } from './client.js';
import { reportClientError } from './clientErrors.js';
import {
	diagnosticErrorText,
	localizedErrorMessage,
	technicalMessage,
	toError,
	toErrorMessage
} from './errors.js';

const report = vi.mocked(reportClientError);
const translate = (key: string, values?: Record<string, string | number>) =>
	get(_)(key, values ? { values } : undefined);

beforeEach(() => {
	report.mockClear();
});

describe('toError', () => {
	it('returns the same Error instance when given an Error', () => {
		const e = new Error('hello');
		expect(toError(e)).toBe(e);
	});

	it('wraps strings in Error', () => {
		const wrapped = toError('boom');
		expect(wrapped).toBeInstanceOf(Error);
		expect(wrapped.message).toBe('boom');
	});

	it('stringifies non-string non-Error values', () => {
		expect(toError(42).message).toBe('42');
		expect(toError(null).message).toBe('null');
		expect(toError(undefined).message).toBe('undefined');
		expect(toError({ a: 1 }).message).toBe('[object Object]');
	});
});

describe('toErrorMessage', () => {
	it('shows a plain Error as an unexpected error in the interface language, not its English text', () => {
		const message = toErrorMessage(new Error('Failed to fetch'));
		expect(message).toBe(translate('common.errorUnexpected'));
		expect(message).not.toContain('Failed to fetch');
	});

	it('sends the English text it hides to the log, once per error however often it is shown', () => {
		const err = new Error('Failed to fetch');
		toErrorMessage(err);
		toErrorMessage(err);
		expect(report).toHaveBeenCalledTimes(1);
		expect(report).toHaveBeenCalledWith(expect.objectContaining({ kind: 'manual', error: err }));
	});

	it('treats a thrown string like a plain Error', () => {
		expect(toErrorMessage('boom')).toBe(translate('common.errorUnexpected'));
		expect(report).toHaveBeenCalledWith(expect.objectContaining({ error: 'boom' }));
	});

	it('returns ApiError.message which carries the localized backend problem.detail', () => {
		// BE localizes problem.detail via Accept-Language for all error codes,
		// so the FE just surfaces err.message — no client-side override map.
		const err = new ApiError(403, 'Forbidden', null, {
			detail: 'Poskytovatel pošty (OAuth2) odmítl IMAP přístup.',
			errorCode: 'MAIL_OAUTH2_IMAP_ACCESS_DENIED'
		} as never);
		expect(toErrorMessage(err)).toBe('Poskytovatel pošty (OAuth2) odmítl IMAP přístup.');
		expect(report).not.toHaveBeenCalled();
	});

	it('uses problem.detail for non-401 ApiError (regression for 503)', () => {
		const err = new ApiError(503, 'Service Unavailable', null, {
			detail: 'Mail server connection failed: connection refused'
		} as never);
		expect(err.message).toBe('Mail server connection failed: connection refused');
		expect(toErrorMessage(err)).toBe('Mail server connection failed: connection refused');
	});

	it('names the status, translated, when an ApiError has no problem.detail', () => {
		const err = new ApiError(500, 'Internal', null);
		// The error keeps its English text for logs; the interface does not show it.
		expect(err.message).toBe('API error 500: Internal');
		const message = toErrorMessage(err);
		expect(message).toBe(translate('common.errorHttpStatus', { status: 500 }));
		expect(message).toContain('500');
		expect(message).not.toContain('Internal');
		// The backend logged its own 500; this one is not news to the log.
		expect(report).not.toHaveBeenCalled();
	});

	it('treats empty/whitespace problem.detail as missing and uses fallback', () => {
		const err = new ApiError(502, 'Bad Gateway', null, { detail: '   ' } as never);
		expect(err.message).toBe('API error 502: Bad Gateway');
		expect(localizedErrorMessage(err)).toBeNull();
		expect(toErrorMessage(err)).toBe(translate('common.errorHttpStatus', { status: 502 }));
	});
});

describe('technicalMessage', () => {
	it('is the error as raised, for logs and technical messages', () => {
		expect(technicalMessage(new Error('Failed to fetch'))).toBe('Failed to fetch');
		expect(technicalMessage('boom')).toBe('boom');
		expect(technicalMessage(new ApiError(500, 'Internal', null))).toBe('API error 500: Internal');
	});
});

describe('diagnosticErrorText', () => {
	it('keeps the technical text and marks it English when nothing localized it', () => {
		expect(diagnosticErrorText(new Error('Backend readiness failed: 503'))).toEqual({
			text: 'Backend readiness failed: 503',
			lang: 'en'
		});
		expect(diagnosticErrorText(new ApiError(503, 'Service Unavailable', null))).toEqual({
			text: 'API error 503: Service Unavailable',
			lang: 'en'
		});
		expect(report).not.toHaveBeenCalled();
	});

	it('shows a localized backend detail without a language of its own', () => {
		const err = new ApiError(503, 'Service Unavailable', null, {
			detail: 'Mail server connection failed: connection refused'
		} as never);
		expect(diagnosticErrorText(err)).toEqual({
			text: 'Mail server connection failed: connection refused',
			lang: undefined
		});
	});
});
