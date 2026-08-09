import { execFileSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import path from 'node:path';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { createGateRepo } from './test-support/gate-repo.mjs';

/*
 * The generated `schema.d.ts` is what the frontend types itself against, so a
 * backend contract change that never reaches it produces types that compile
 * and lie. The gate regenerates from the OpenAPI snapshot and compares.
 *
 * Two behaviours are worth pinning: drift fails, and a difference that is only
 * line endings does not. The snapshot is edited on Windows and the gate runs
 * on Linux in CI, so a comparison that counted CRLF as drift would fail every
 * CI run for a reason no one could act on — and a gate that cries wolf is a
 * gate people route around.
 */

const SNAPSHOT = {
	openapi: '3.1.0',
	info: { title: 'Fixture', version: '1' },
	paths: {
		'/api/v1/ping': {
			get: { operationId: 'ping', responses: { 200: { description: 'ok' } } }
		}
	}
};

let repo;

function seed(snapshot = SNAPSHOT) {
	repo.write(
		'backend/src/test/resources/openapi/api-docs.json',
		JSON.stringify(snapshot, null, '\t')
	);
	repo.write('frontend/.prettierrc', JSON.stringify({ useTabs: true, printWidth: 100 }));
	repo.write('frontend/src/lib/api/schema.d.ts', '// placeholder\n');
}

/**
 * Produces what the gate expects, the same way `npm run generate:api:snapshot`
 * does — openapi-typescript, then prettier with the repo config.
 */
function generateSchema() {
	const frontend = repo.frontend;
	const out = path.join(frontend, 'generated.d.ts');
	const node = process.execPath;
	execFileSync(
		node,
		[
			path.join(frontend, 'node_modules', 'openapi-typescript', 'bin', 'cli.js'),
			path.join(repo.root, 'backend/src/test/resources/openapi/api-docs.json'),
			'-o',
			out
		],
		{ cwd: frontend, stdio: 'ignore' }
	);
	execFileSync(
		node,
		[
			path.join(frontend, 'node_modules', 'prettier', 'bin', 'prettier.cjs'),
			'--config',
			path.join(frontend, '.prettierrc'),
			'--write',
			out
		],
		{ cwd: frontend, stdio: 'ignore' }
	);
	return readFileSync(out, 'utf8');
}

beforeEach(() => {
	repo = createGateRepo();
});

afterEach(() => {
	repo.cleanup();
});

describe('check-api-schema', () => {
	it('fails when the committed schema does not match the snapshot', () => {
		seed();

		const result = repo.run('check-api-schema.mjs');

		expect(result.status).toBe(1);
		expect(result.output).toContain('out of date');
		// The message has to name the fix, or the failure is a puzzle.
		expect(result.output).toContain('generate:api:snapshot');
	});

	it('passes when the committed schema is what the snapshot generates', () => {
		seed();
		repo.write('frontend/src/lib/api/schema.d.ts', generateSchema());

		expect(repo.run('check-api-schema.mjs').status).toBe(0);
	});

	it('treats a CRLF-only difference as no difference', () => {
		seed();
		const generated = generateSchema();
		repo.write('frontend/src/lib/api/schema.d.ts', generated.replace(/\n/g, '\r\n'));

		expect(repo.run('check-api-schema.mjs').status).toBe(0);
	});

	it('fails on a contract change the committed schema has not caught up with', () => {
		seed();
		repo.write('frontend/src/lib/api/schema.d.ts', generateSchema());
		expect(repo.run('check-api-schema.mjs').status).toBe(0);

		// Only the backend contract moves. Re-seeding would also reset
		// schema.d.ts, and the assertion would then pass because the file was
		// blanked rather than because the contract drifted.
		repo.write(
			'backend/src/test/resources/openapi/api-docs.json',
			JSON.stringify(
				{
					...SNAPSHOT,
					paths: {
						...SNAPSHOT.paths,
						'/api/v1/pong': {
							get: { operationId: 'pong', responses: { 200: { description: 'ok' } } }
						}
					}
				},
				null,
				'\t'
			)
		);

		expect(repo.run('check-api-schema.mjs').status).toBe(1);
	});

	it('reports a failure rather than throwing when the snapshot is missing', () => {
		repo.write('frontend/.prettierrc', JSON.stringify({ useTabs: true }));
		repo.write('frontend/src/lib/api/schema.d.ts', '// placeholder\n');

		const result = repo.run('check-api-schema.mjs');

		expect(result.status).toBe(1);
		expect(result.output.length).toBeGreaterThan(0);
	});
});
