import { describe, expect, it } from 'vitest';
import {
	assertCompatible,
	assertSessionMatchesReadiness,
	BackendCompatibilityError,
	BackendReadinessError,
	compareSemver,
	parseReadiness,
	parseSemver,
	type SystemReadinessResponse
} from './readiness.js';
import type { SessionPayload } from './session.js';

const baseReadiness: SystemReadinessResponse = {
	ready: true,
	phase: 'READY',
	appName: 'mail',
	appVersion: '0.1.0',
	apiVersion: '1.0.0',
	minClientVersion: '0.1.0',
	dbSchemaVersion: '1',
	reason: null
};

const baseSession: SessionPayload = {
	appName: 'mail',
	appVersion: '0.1.0',
	apiVersion: '1.0.0',
	minClientVersion: '0.1.0',
	dbSchemaVersion: '1',
	port: 51234,
	apiKey: 'k',
	baseUrl: 'http://127.0.0.1:51234/api'
};

describe('parseSemver', () => {
	it('parses major.minor.patch', () => {
		expect(parseSemver('1.2.3', 'x')).toEqual({ major: 1, minor: 2, patch: 3 });
	});

	it('parses with pre-release / build metadata', () => {
		expect(parseSemver('0.1.0-rc.1', 'x')).toEqual({ major: 0, minor: 1, patch: 0 });
		expect(parseSemver('1.2.3+sha.abc', 'x')).toEqual({ major: 1, minor: 2, patch: 3 });
	});

	it('rejects non-semver', () => {
		expect(() => parseSemver('1.2', 'x')).toThrow(BackendCompatibilityError);
		expect(() => parseSemver('v1.2.3', 'x')).toThrow(BackendCompatibilityError);
		expect(() => parseSemver('', 'x')).toThrow(BackendCompatibilityError);
	});
});

describe('compareSemver', () => {
	it('orders by major, then minor, then patch', () => {
		expect(
			compareSemver({ major: 1, minor: 0, patch: 0 }, { major: 2, minor: 0, patch: 0 })
		).toBeLessThan(0);
		expect(
			compareSemver({ major: 1, minor: 5, patch: 0 }, { major: 1, minor: 2, patch: 99 })
		).toBeGreaterThan(0);
		expect(compareSemver({ major: 1, minor: 2, patch: 3 }, { major: 1, minor: 2, patch: 3 })).toBe(
			0
		);
	});
});

describe('parseReadiness', () => {
	it('parses a valid payload', () => {
		const result = parseReadiness({ ...baseReadiness });
		expect(result.ready).toBe(true);
		expect(result.phase).toBe('READY');
		expect(result.appName).toBe('mail');
	});

	it('throws on null/non-object', () => {
		expect(() => parseReadiness(null)).toThrow(BackendReadinessError);
		expect(() => parseReadiness('string')).toThrow(BackendReadinessError);
	});

	it('rejects missing `ready` (non-boolean)', () => {
		expect(() => parseReadiness({ ...baseReadiness, ready: 'yes' })).toThrow(BackendReadinessError);
	});

	it('gives helpful diagnostic when appName is missing (stale sidecar)', () => {
		const broken = { ...baseReadiness, appName: '' };
		expect(() => parseReadiness(broken)).toThrowError(/old sidecar version|sidecar/i);
	});

	it('rejects empty required string fields', () => {
		for (const field of [
			'phase',
			'appVersion',
			'apiVersion',
			'minClientVersion',
			'dbSchemaVersion'
		] as const) {
			const broken = { ...baseReadiness, [field]: '' };
			expect(() => parseReadiness(broken), `empty ${field}`).toThrow(BackendReadinessError);
		}
	});

	it('allows null/undefined reason but rejects non-string', () => {
		expect(() => parseReadiness({ ...baseReadiness, reason: null })).not.toThrow();
		expect(() => parseReadiness({ ...baseReadiness, reason: undefined })).not.toThrow();
		expect(() => parseReadiness({ ...baseReadiness, reason: 42 })).toThrow(BackendReadinessError);
	});
});

describe('assertCompatible', () => {
	it('accepts matching API major and adequate client version', () => {
		expect(() => assertCompatible(baseReadiness)).not.toThrow();
	});

	it('rejects API major mismatch', () => {
		expect(() => assertCompatible({ ...baseReadiness, apiVersion: '2.0.0' })).toThrow(
			BackendCompatibilityError
		);
	});

	it('rejects client older than minClientVersion', () => {
		// CLIENT_VERSION is 0.1.0 — require 0.2.0 should fail
		expect(() => assertCompatible({ ...baseReadiness, minClientVersion: '0.2.0' })).toThrow(
			BackendCompatibilityError
		);
	});

	it('rejects malformed apiVersion semver', () => {
		expect(() => assertCompatible({ ...baseReadiness, apiVersion: 'one.two' })).toThrow(
			BackendCompatibilityError
		);
	});
});

describe('assertSessionMatchesReadiness', () => {
	it('passes when fields match', () => {
		expect(() => assertSessionMatchesReadiness(baseSession, baseReadiness)).not.toThrow();
	});

	it('throws on any drift', () => {
		const drift: Array<keyof SystemReadinessResponse & keyof SessionPayload> = [
			'appName',
			'appVersion',
			'apiVersion',
			'minClientVersion',
			'dbSchemaVersion'
		];
		for (const field of drift) {
			const readiness = { ...baseReadiness, [field]: 'X' } as SystemReadinessResponse;
			expect(
				() => assertSessionMatchesReadiness(baseSession, readiness),
				`drift on ${field}`
			).toThrow(BackendReadinessError);
		}
	});
});
