/**
 * Scans the backend logs of one installation for what RELEASE_CHECKLIST §7
 * and §8 ask a person to read them for: the three states of the transient IMAP
 * hiccup D, CRITICAL audit records, every other ERROR and WARN grouped by
 * message, and anything a log must never hold (an unmasked address, a token,
 * the session API key). Rotated `.gz` files are read too. The verdicts live in
 * lib/log-scan.mjs.
 *
 * Usage:
 *   node scripts/scan-logs.mjs [--logs <dir>] [--since <ISO time>]
 *                              [--secrets <file>] [--strict true] [--json <file>]
 *
 * --logs     the logs directory; default the installed app's,
 *            %LOCALAPPDATA%\VoxRox\Mail\logs (the dev build's is Mail.dev)
 * --since    ignore entries older than this, to scope the scan to one run
 * --secrets  a file of literal strings that must not appear, one per line;
 *            the API key from the sibling session.json is always added
 * --strict   also fail on any ERROR the report would otherwise only list
 * --json     write the report as JSON as well
 *
 * Exit 0 when nothing fails, 1 when something does, 2 when there is nothing to
 * scan.
 */

import { readdirSync, readFileSync, writeFileSync } from 'node:fs';
import path from 'node:path';
import process from 'node:process';
import { gunzipSync } from 'node:zlib';
import { parseArgs } from './lib/cli-args.mjs';
import { resolveMailDataDir } from './lib/data-dirs.mjs';
import {
	classify,
	findLeaks,
	formatReport,
	orderLogFiles,
	parseAuditLog,
	parseMailLog,
	verdict
} from './lib/log-scan.mjs';

const args = parseArgs(process.argv.slice(2));
const logsDir = path.resolve(args.logs ?? path.join(resolveMailDataDir(), 'logs'));
const since = args.since ? new Date(args.since) : null;
if (since && Number.isNaN(since.getTime())) {
	console.error(`--since is not a date: ${args.since}`);
	process.exit(2);
}

let names;
try {
	names = readdirSync(logsDir);
} catch {
	console.error(`No logs directory at ${logsDir}. Pass --logs <dir>.`);
	process.exit(2);
}

const mailFiles = orderLogFiles(names, 'mail');
const auditFiles = orderLogFiles(names, 'audit');
if (mailFiles.length === 0) {
	console.error(`No mail.log in ${logsDir}.`);
	process.exit(2);
}

const read = (name) => {
	const bytes = readFileSync(path.join(logsDir, name));
	return (name.endsWith('.gz') ? gunzipSync(bytes) : bytes).toString('utf8');
};

const secrets = args.secrets
	? readFileSync(args.secrets, 'utf8')
			.split(/\r?\n/)
			.map((line) => line.trim())
			.filter(Boolean)
	: [];
try {
	const session = JSON.parse(readFileSync(path.join(logsDir, '..', 'session.json'), 'utf8'));
	if (typeof session.apiKey === 'string') secrets.push(session.apiKey);
} catch {
	// No session.json next to the logs: a copied logs folder, or the app is not running.
}

const entries = [];
const auditRecords = [];
const leaks = [];
for (const name of mailFiles) {
	const text = read(name);
	entries.push(...parseMailLog(text, name));
	leaks.push(...findLeaks(text, name, { secrets }));
}
for (const name of auditFiles) {
	const text = read(name);
	auditRecords.push(...parseAuditLog(text, name));
	leaks.push(...findLeaks(text, name, { secrets }));
}

const result = classify(entries, auditRecords, { since });
const auditScanned = auditFiles.length > 0;
const outcome = verdict(result, leaks, { strict: args.strict === 'true', auditScanned });
const files = [...mailFiles, ...auditFiles].map((name) => path.join(logsDir, name));
console.log(formatReport({ files, result, leaks, outcome, since, auditScanned }));

if (args.json) {
	const brief = (item) => ({ file: item.file, line: item.line });
	const group = ({ level, logger, template, count, first }) => ({
		level,
		logger,
		template,
		count,
		first: brief(first)
	});
	writeFileSync(
		args.json,
		`${JSON.stringify(
			{
				logsDir,
				since: since?.toISOString() ?? null,
				auditScanned,
				ok: outcome.ok,
				reasons: outcome.reasons,
				d: {
					retried: result.d.retried.length,
					exhausted: result.d.exhausted.map(brief),
					classifierMiss: result.d.classifierMiss.map(brief)
				},
				critical: result.critical.map((record) => ({ ...brief(record), action: record.action })),
				errors: result.errors.map(group),
				warnings: result.warnings.map(group),
				leaks
			},
			null,
			2
		)}\n`
	);
}

process.exit(outcome.ok ? 0 : 1);
