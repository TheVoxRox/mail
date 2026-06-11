import { describe, expect, it } from 'vitest';
import {
	apiV1Base,
	parseSession,
	validateBackendBaseUrl,
	SessionLoadError,
	type SessionPayload
} from './session.js';

const validPayload = {
	appName: 'mail',
	appVersion: '0.1.0',
	apiVersion: '1.0.0',
	minClientVersion: '0.1.0',
	dbSchemaVersion: '1',
	port: 51234,
	apiKey: 'k',
	baseUrl: 'http://127.0.0.1:51234/api'
};

describe('apiV1Base', () => {
	it('appends /v1 to the session baseUrl', () => {
		const session = validPayload as SessionPayload;
		expect(apiV1Base(session)).toBe('http://127.0.0.1:51234/api/v1');
	});

	it('strips trailing slashes from baseUrl before appending /v1', () => {
		const session = { ...validPayload, baseUrl: 'http://127.0.0.1:51234/api/' } as SessionPayload;
		expect(apiV1Base(session)).toBe('http://127.0.0.1:51234/api/v1');
	});
});

describe('parseSession', () => {
	it('parses a valid payload', () => {
		const result = parseSession(JSON.stringify(validPayload));
		expect(result).toEqual(validPayload);
	});

	it('throws on invalid JSON', () => {
		expect(() => parseSession('not json')).toThrow(SessionLoadError);
	});

	it('throws when payload is not an object', () => {
		expect(() => parseSession('null')).toThrow(SessionLoadError);
		expect(() => parseSession('"string"')).toThrow(SessionLoadError);
	});

	it('rejects missing or empty required string fields', () => {
		for (const field of [
			'appName',
			'appVersion',
			'apiVersion',
			'minClientVersion',
			'dbSchemaVersion',
			'apiKey',
			'baseUrl'
		] as const) {
			const broken = { ...validPayload, [field]: '' };
			expect(() => parseSession(JSON.stringify(broken)), `empty ${field}`).toThrow(
				SessionLoadError
			);
			const missing = { ...validPayload };
			// biome-ignore lint: intentional drop for test
			delete (missing as Record<string, unknown>)[field];
			expect(() => parseSession(JSON.stringify(missing)), `missing ${field}`).toThrow(
				SessionLoadError
			);
		}
	});

	it('rejects port outside [1, 65535]', () => {
		expect(() => parseSession(JSON.stringify({ ...validPayload, port: 0 }))).toThrow(
			SessionLoadError
		);
		expect(() => parseSession(JSON.stringify({ ...validPayload, port: 70000 }))).toThrow(
			SessionLoadError
		);
		expect(() => parseSession(JSON.stringify({ ...validPayload, port: 1.5 }))).toThrow(
			SessionLoadError
		);
	});

	it('requires baseUrl to start with http', () => {
		expect(() => parseSession(JSON.stringify({ ...validPayload, baseUrl: 'ftp://x/api' }))).toThrow(
			SessionLoadError
		);
	});
});

describe('validateBackendBaseUrl', () => {
	it('accepts loopback http URLs ending in /api', () => {
		expect(() => validateBackendBaseUrl('http://127.0.0.1:51234/api', 51234)).not.toThrow();
		expect(() => validateBackendBaseUrl('http://localhost:51234/api', 51234)).not.toThrow();
		expect(() => validateBackendBaseUrl('http://[::1]:51234/api', 51234)).not.toThrow();
	});

	it('rejects https (loopback contract is http)', () => {
		expect(() => validateBackendBaseUrl('https://127.0.0.1:51234/api', 51234)).toThrow(
			SessionLoadError
		);
	});

	it('rejects non-loopback hosts', () => {
		expect(() => validateBackendBaseUrl('http://example.com:51234/api', 51234)).toThrow(
			SessionLoadError
		);
		expect(() => validateBackendBaseUrl('http://10.0.0.5:51234/api', 51234)).toThrow(
			SessionLoadError
		);
	});

	it('rejects port mismatch between baseUrl and payload.port', () => {
		expect(() => validateBackendBaseUrl('http://127.0.0.1:51234/api', 9999)).toThrow(
			SessionLoadError
		);
	});

	it('rejects path other than /api', () => {
		expect(() => validateBackendBaseUrl('http://127.0.0.1:51234/', 51234)).toThrow(
			SessionLoadError
		);
		expect(() => validateBackendBaseUrl('http://127.0.0.1:51234/api/v1', 51234)).toThrow(
			SessionLoadError
		);
	});

	it('tolerates trailing slash on /api', () => {
		expect(() => validateBackendBaseUrl('http://127.0.0.1:51234/api/', 51234)).not.toThrow();
	});

	it('rejects malformed URLs', () => {
		expect(() => validateBackendBaseUrl('not a url', 51234)).toThrow(SessionLoadError);
	});
});
