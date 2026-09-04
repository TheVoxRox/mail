import { execFileSync } from 'node:child_process';
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import process from 'node:process';
import { fileURLToPath } from 'node:url';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

/*
 * The gate exists because `npm audit` returns the same exit code for "found
 * something" and "could not ask", and CI could not tell them apart - one 503
 * failed a Lint job and blocked a merge. So the cases worth pinning are not the
 * happy path but the ones where a verdict is missing: they must never read as a
 * pass, and they must be retried before they end the run.
 *
 * `--input` feeds recorded npm output one file per attempt, so the retry policy
 * is exercised without a network and without waiting out the backoff.
 */

const SCRIPT = path.join(path.dirname(fileURLToPath(import.meta.url)), 'npm-audit-gate.mjs');

/** Real npm output, recorded 2026-09-04 while the advisory endpoint was flapping. */
const REGISTRY_503 = {
	message:
		'503 Service Unavailable - POST https://registry.npmjs.org/-/npm/v1/security/advisories/bulk',
	error: { summary: '', detail: '' }
};
const NETWORK_TIMEOUT = {
	message: 'network timeout at: https://registry.npmjs.org/-/npm/v1/security/advisories/bulk',
	error: { summary: '', detail: '' }
};

function report(vulnerabilities) {
	return {
		auditReportVersion: 2,
		vulnerabilities: {},
		metadata: {
			vulnerabilities: {
				info: 0,
				low: 0,
				moderate: 0,
				high: 0,
				critical: 0,
				total: 0,
				...vulnerabilities
			}
		}
	};
}

let dir;

beforeEach(() => {
	dir = mkdtempSync(path.join(os.tmpdir(), 'voxrox-audit-gate-'));
});

afterEach(() => {
	rmSync(dir, { recursive: true, force: true });
});

/** Writes `body` (an object, or a raw string for the malformed cases) as one attempt. */
function attempt(name, body) {
	const file = path.join(dir, `${name}.json`);
	writeFileSync(file, typeof body === 'string' ? body : JSON.stringify(body));
	return file;
}

/** Runs the gate over the given recorded attempts; never touches the network. */
function runGate(files, extraArgs = []) {
	const args = [SCRIPT, ...files.flatMap((file) => ['--input', file]), ...extraArgs];
	try {
		const stdout = execFileSync(process.execPath, args, { encoding: 'utf8', stdio: 'pipe' });
		return { code: 0, stdout, stderr: '' };
	} catch (error) {
		return { code: error.status, stdout: error.stdout ?? '', stderr: error.stderr ?? '' };
	}
}

describe('npm audit gate', () => {
	it('passes when the report has nothing at or above high', () => {
		const result = runGate([attempt('clean', report({ low: 1, total: 1 }))]);

		expect(result.code).toBe(0);
		expect(result.stdout).toContain('nothing at high or above');
		// The low finding is reported rather than hidden - it is not blocking, but
		// silence would make a growing list invisible.
		expect(result.stdout).toContain('low: 1');
	});

	it('fails on a high finding and says how many', () => {
		const result = runGate([attempt('high', report({ moderate: 2, high: 1, total: 3 }))]);

		expect(result.code).toBe(1);
		expect(result.stderr).toContain('found 1 vulnerability');
	});

	it('counts critical as blocking too', () => {
		const result = runGate([attempt('critical', report({ critical: 2, total: 2 }))]);

		expect(result.code).toBe(1);
		expect(result.stderr).toContain('found 2 vulnerability');
	});

	it('retries past a registry outage and accepts the attempt that answers', () => {
		/*
		 * The exact sequence measured on 2026-09-04: two failures then a clean
		 * report. Before the retry this was a red Lint job and a blocked merge.
		 */
		const result = runGate([
			attempt('a', NETWORK_TIMEOUT),
			attempt('b', REGISTRY_503),
			attempt('c', report({ low: 1, total: 1 }))
		]);

		expect(result.code).toBe(0);
		expect(result.stdout).toContain('nothing at high or above');
	});

	it('fails closed when no attempt reaches a verdict', () => {
		const result = runGate([
			attempt('a', REGISTRY_503),
			attempt('b', REGISTRY_503),
			attempt('c', REGISTRY_503)
		]);

		expect(result.code).toBe(1);
		// The wording matters as much as the exit code: an unreachable registry
		// must not be summarised as a clean audit anywhere a reader might skim.
		expect(result.stderr).toContain('no verdict');
		expect(result.stderr).not.toContain('nothing at high or above');
	});

	it('treats output that is not JSON as no verdict, not as a pass', () => {
		const result = runGate([attempt('garbage', '<html>502 Bad Gateway</html>')]);

		expect(result.code).toBe(1);
		expect(result.stderr).toContain('no verdict');
	});

	it('treats a report without the counts as no verdict', () => {
		/*
		 * The property the whole gate rests on: it keys on the **presence** of
		 * `metadata.vulnerabilities`, not the absence of `error`. A shape neither
		 * branch predicted - here a plausible-looking report that simply carries no
		 * counts - has to end red rather than pass as "nothing found".
		 */
		const result = runGate([attempt('shapeless', { auditReportVersion: 2, metadata: {} })]);

		expect(result.code).toBe(1);
		expect(result.stderr).toContain('no verdict');
	});

	it('writes the accepted report, not the attempts that failed', () => {
		const out = path.join(dir, 'audit.json');
		const result = runGate(
			[attempt('a', REGISTRY_503), attempt('b', report({ low: 1, total: 1 }))],
			['--report', out]
		);

		expect(result.code).toBe(0);
		const written = JSON.parse(readFileSync(out, 'utf8'));
		expect(written.metadata.vulnerabilities.low).toBe(1);
		expect(written.error).toBeUndefined();
	});

	it('rejects an unknown argument instead of ignoring it', () => {
		// A typo in a workflow must not quietly become a run with default settings.
		const result = runGate([attempt('clean', report({}))], ['--audit-level=high']);

		expect(result.code).toBe(2);
		expect(result.stderr).toContain('unknown argument');
	});
});
