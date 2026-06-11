import { describe, expect, it } from 'vitest';
import { toError, toErrorMessage } from './errors.js';
import { ApiError } from './client.js';

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
	it('returns Error.message for plain Error', () => {
		expect(toErrorMessage(new Error('x'))).toBe('x');
	});

	it('falls back to String() for non-Error', () => {
		expect(toErrorMessage('boom')).toBe('boom');
		expect(toErrorMessage(undefined)).toBe('undefined');
	});

	it('returns ApiError.message which carries the localized backend problem.detail', () => {
		// BE localizes problem.detail via Accept-Language for all error codes,
		// so the FE just surfaces err.message — no client-side override map.
		const err = new ApiError(403, 'Forbidden', null, {
			detail: 'Poskytovatel pošty (OAuth2) odmítl IMAP přístup.',
			errorCode: 'MAIL_OAUTH2_IMAP_ACCESS_DENIED'
		} as never);
		expect(toErrorMessage(err)).toBe('Poskytovatel pošty (OAuth2) odmítl IMAP přístup.');
	});

	it('uses problem.detail for non-401 ApiError (regression for 503)', () => {
		const err = new ApiError(503, 'Service Unavailable', null, {
			detail: 'Mail server connection failed: connection refused'
		} as never);
		expect(err.message).toBe('Mail server connection failed: connection refused');
		expect(toErrorMessage(err)).toBe('Mail server connection failed: connection refused');
	});

	it('falls back to "API error N: statusText" when ApiError has no problem.detail', () => {
		const err = new ApiError(500, 'Internal', null);
		expect(err.message).toBe('API error 500: Internal');
		expect(toErrorMessage(err)).toBe('API error 500: Internal');
	});

	it('treats empty/whitespace problem.detail as missing and uses fallback', () => {
		const err = new ApiError(502, 'Bad Gateway', null, { detail: '   ' } as never);
		expect(err.message).toBe('API error 502: Bad Gateway');
	});
});
