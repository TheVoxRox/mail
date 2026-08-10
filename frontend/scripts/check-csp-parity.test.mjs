import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { createGateRepo } from './test-support/gate-repo.mjs';

/*
 * Dev and production must enforce the same Content-Security-Policy, or the
 * policy is only ever exercised in the configuration nobody develops against.
 * The comparison has to be order-insensitive — JSON key order and directive
 * order carry no meaning — while still catching a directive whose *value*
 * differs, which is the difference that matters.
 */

let repo;

const CSP = {
	'default-src': "'self' asset:",
	'script-src': "'self'",
	'object-src': "'none'"
};

function seed(csp, devCsp) {
	repo.write(
		'frontend/src-tauri/tauri.conf.json',
		JSON.stringify({ app: { security: { csp, devCsp } } }, null, '\t')
	);
}

beforeEach(() => {
	repo = createGateRepo();
});

afterEach(() => {
	repo.cleanup();
});

describe('check-csp-parity', () => {
	it('passes when both policies are identical', () => {
		seed(CSP, { ...CSP });

		const result = repo.run('check-csp-parity.mjs');

		expect(result.status).toBe(0);
		expect(result.stdout).toContain('CSP parity OK');
	});

	it('passes when the two differ only in key order', () => {
		seed(CSP, {
			'object-src': "'none'",
			'default-src': "'self' asset:",
			'script-src': "'self'"
		});

		expect(repo.run('check-csp-parity.mjs').status).toBe(0);
	});

	it('fails on a directive that is loosened in dev, naming it', () => {
		seed(CSP, { ...CSP, 'script-src': "'self' 'unsafe-eval'" });

		const result = repo.run('check-csp-parity.mjs');

		expect(result.status).not.toBe(0);
		expect(result.output).toContain('script-src');
		expect(result.output).toContain('unsafe-eval');
	});

	it('fails on a directive present in only one of them', () => {
		const { 'object-src': _dropped, ...withoutObjectSrc } = CSP;
		seed(CSP, withoutObjectSrc);

		const result = repo.run('check-csp-parity.mjs');

		expect(result.status).not.toBe(0);
		expect(result.output).toContain('object-src');
	});

	it('fails when a policy is missing altogether', () => {
		repo.write(
			'frontend/src-tauri/tauri.conf.json',
			JSON.stringify({ app: { security: { csp: CSP } } }, null, '\t')
		);

		expect(repo.run('check-csp-parity.mjs').status).not.toBe(0);
	});
});
