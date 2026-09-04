#!/usr/bin/env node
/**
 * `npm audit` as a gate that can tell a verdict from a missing one.
 *
 * `npm audit` exits non-zero for two unrelated reasons: it found something at
 * or above the threshold, or it never reached the advisory registry. CI treated
 * both as "the gate failed", so on 2026-09-04 a single 503 from
 * `registry.npmjs.org/-/npm/v1/security/advisories/bulk` took down the whole
 * Lint job and with it the merge, five minutes after it started. Rerunning was
 * the only recourse, and the endpoint went on flapping: three calls from one
 * machine inside the hour gave a network timeout, a 503, and a clean report.
 *
 * Retrying blindly would be worse than not retrying. A real finding would be
 * repeated twice for nothing, and - the part that matters - an error shape
 * nobody anticipated would be indistinguishable from the run that passed. So
 * the cases are told apart on the JSON, and on the half that carries a verdict
 * rather than the half that carries a failure:
 *
 *   registry failure  {"message": "...", "error": {...}}       no metadata
 *   real report       {..., "metadata": {"vulnerabilities": ...}}
 *
 * The test is the **presence of `metadata.vulnerabilities`**, never the absence
 * of `error`. A shape neither branch predicted then counts as "no verdict" and
 * ends red, instead of passing as "nothing found".
 *
 * Each attempt is bounded and then retried, the reasoning the apt step in
 * ci.yml is already written around: a bound alone only fails faster, and a
 * retry alone lets one stuck connection run away with the budget. npm's own
 * retry is turned off in favour of this loop - a fresh invocation gets a fresh
 * connection, and npm's cannot tell the two failures apart either. `npm audit`
 * inherits a 300 s `fetch-timeout`, which is exactly how long that failing CI
 * step took before it gave up.
 *
 * Usage:
 *   node scripts/npm-audit-gate.mjs [--report <file>]
 *   node scripts/npm-audit-gate.mjs --input <file> [--input <file> ...]
 *
 * `--report` writes the accepted report as JSON, for CI to keep as an artifact.
 * `--input` replaces the npm invocation with recorded output, one file per
 * attempt in order, so the retry policy itself is testable without a network.
 */
import { execFileSync } from 'node:child_process';
import { existsSync, readFileSync, writeFileSync } from 'node:fs';
import path from 'node:path';
import process from 'node:process';

/** Severities that fail the build. Everything below is reported, not blocking. */
const BLOCKING = ['high', 'critical'];
const ATTEMPTS = 3;
/** Backoff before attempts 2 and 3. A flapping endpoint needs time, not haste. */
const BACKOFF_MS = [20_000, 40_000];
/**
 * Per-attempt bound. npm's default is 300 s with two internal retries, so one
 * stuck request outlives any sensible job; ours is short because the retry
 * above it is what recovers.
 */
const FETCH_TIMEOUT_MS = 45_000;

function parseArgs(argv) {
	const inputs = [];
	let report = null;
	for (let i = 0; i < argv.length; i += 1) {
		if (argv[i] === '--input') inputs.push(argv[(i += 1)]);
		else if (argv[i] === '--report') report = argv[(i += 1)];
		else {
			console.error(`npm-audit-gate: unknown argument "${argv[i]}"`);
			process.exit(2);
		}
	}
	return { inputs, report };
}

/**
 * npm's own entry script, to be run under this node rather than through the
 * platform shim. Windows ships `npm.cmd`, and node refuses to `execFile` a
 * `.cmd` without a shell (the CVE-2024-27980 fix) while passing arguments
 * through a shell is itself deprecated as of node 26 (DEP0190) - so neither
 * half of the obvious approach is available. `npm_execpath` is set whenever
 * this runs under `npm run`; the layout probes cover a direct `node scripts/`.
 */
function resolveNpmCli() {
	const fromEnv = process.env.npm_execpath;
	if (fromEnv && existsSync(fromEnv)) return fromEnv;
	const nodeDir = path.dirname(process.execPath);
	return (
		[
			path.join(nodeDir, 'node_modules', 'npm', 'bin', 'npm-cli.js'),
			path.join(nodeDir, '..', 'lib', 'node_modules', 'npm', 'bin', 'npm-cli.js')
		].find((candidate) => existsSync(candidate)) ?? null
	);
}

/**
 * One audit attempt. `{ raw }` is output to be judged - a non-zero exit still
 * carries the report, so the exit code is not read. `{ failure }` is this
 * process failing to ask at all, which is not a registry problem and must not
 * be reported as one.
 */
function runAudit(npmCli) {
	try {
		return {
			raw: execFileSync(
				process.execPath,
				[npmCli, 'audit', '--json', `--fetch-timeout=${FETCH_TIMEOUT_MS}`, '--fetch-retries=0'],
				{ encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] }
			)
		};
	} catch (error) {
		if (typeof error.stdout === 'string' && error.stdout.trim()) return { raw: error.stdout };
		return { failure: String(error.message) };
	}
}

/**
 * The report, or `null` when this attempt reached no verdict - unparseable
 * output, a registry error, or any shape without the counts the gate reads.
 */
function verdictOf(raw) {
	let parsed;
	try {
		parsed = JSON.parse(raw);
	} catch {
		return null;
	}
	const counts = parsed?.metadata?.vulnerabilities;
	if (!counts || typeof counts !== 'object') return null;
	return { parsed, counts };
}

function describe(raw) {
	try {
		const parsed = JSON.parse(raw);
		return String(parsed.message ?? parsed.error?.summary ?? 'no message').slice(0, 200);
	} catch {
		return 'output was not JSON';
	}
}

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

const { inputs, report } = parseArgs(process.argv.slice(2));
const recorded = inputs.length > 0;
const attempts = recorded ? inputs.length : ATTEMPTS;

let npmCli = null;
if (!recorded) {
	npmCli = resolveNpmCli();
	if (!npmCli) {
		console.error(
			'::error::npm-audit-gate: could not locate npm-cli.js (npm_execpath unset and no npm ' +
				'beside this node). The audit did not run; this is not an audit result.'
		);
		process.exit(1);
	}
}

let verdict = null;
for (let attempt = 1; attempt <= attempts; attempt += 1) {
	let raw;
	if (recorded) {
		raw = readFileSync(inputs[attempt - 1], 'utf8');
	} else {
		const outcome = runAudit(npmCli);
		if (outcome.failure) {
			// Not retried: a spawn that cannot start will not start next time
			// either, and calling it a registry outage would send the reader after
			// the wrong problem.
			console.error(`::error::npm-audit-gate: could not run npm audit - ${outcome.failure}`);
			process.exit(1);
		}
		raw = outcome.raw;
	}

	verdict = verdictOf(raw);
	if (verdict) break;

	console.error(
		`npm-audit-gate: attempt ${attempt} of ${attempts} reached no verdict - ${describe(raw)}`
	);
	if (attempt < attempts && !recorded) await sleep(BACKOFF_MS[attempt - 1] ?? 0);
}

if (!verdict) {
	console.error(
		`::error::npm audit produced no verdict in ${attempts} attempt(s) - the advisory registry ` +
			'could not be reached. Failing closed: an unreachable registry is not a clean audit.'
	);
	process.exit(1);
}

if (report) writeFileSync(report, JSON.stringify(verdict.parsed, null, 2));

const counts = verdict.counts;
const blocking = BLOCKING.reduce((sum, level) => sum + (Number(counts[level]) || 0), 0);
const summary = Object.entries(counts)
	.filter(([level]) => level !== 'total')
	.map(([level, count]) => `${level}: ${count}`)
	.join(', ');

if (blocking > 0) {
	console.error(`::error::npm audit found ${blocking} vulnerability(ies) at high or above.`);
	console.error(`npm-audit-gate: ${summary}`);
	console.error('Run `npm audit` for the detail, `npm audit fix` for the ones with a fix.');
	process.exit(1);
}

console.log(`npm audit OK: nothing at high or above (${summary}).`);
