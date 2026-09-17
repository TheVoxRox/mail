/**
 * The pure half of `npm run release:scan-logs`: parsing the backend's
 * `mail.log` and `audit.log`, sorting what they say into the verdicts
 * RELEASE_CHECKLIST §7 and §8 ask for, and finding what must never be in a log
 * at all. Reading files, gunzipping and the exit code live in
 * ../scan-logs.mjs, so everything decided here is tested on plain strings.
 *
 * What it replaces is a `Select-String` over `mail.log` and `mail.log.*`.
 * Logback compresses a rotated file (`mail.log.2026-09-15.0.gz`), and rotation
 * is daily, so that search read gzip bytes for everything before midnight and
 * reported zero — a false green on exactly the part of a long run it existed
 * for.
 */

/** Spring Boot's default file line: `2026-09-16T09:00:00.301+02:00  WARN 3324 --- [mail] [main] logger : message`. */
const MAIL_HEADER =
	/^(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?(?:Z|[+-]\d{2}:?\d{2})?)\s+(TRACE|DEBUG|INFO|WARN|ERROR)\s+\S+\s+---\s+\[[^\]]*\]\s+\[([^\]]*)\]\s+(\S+)\s*:\s?(.*)$/;

/** The AUDIT appender in logback-spring.xml: `2026-09-16 09:00:06.997 [main] ERROR CRITICAL action=… actor=… detail=…`. */
const AUDIT_LINE =
	/^(\d{4})-(\d{2})-(\d{2}) (\d{2}):(\d{2}):(\d{2})\.(\d{3}) \[([^\]]*)\] (\w+)\s+(SUCCESS|FAILURE|CRITICAL) action=(\S*) actor=(\S*) (?:detail|reason)=(.*)$/;

/*
 * The transient-IMAP hiccup "D" (#78), as MailSyncService logs it. The three
 * texts are the ones §8.2 of the checklist names; keep them in step with
 * MailSyncService.java, where they are written.
 */
const D_RETRIED = 'Transient IMAP error during folder sync';
const D_EXHAUSTED = 'transient-retry attempt(s)';
const D_EXHAUSTED_LEAD = 'still failing after';
const D_CRITICAL = 'Critical error during folder sync';
const D_CAUSE = 'failed to create new store connection';

/*
 * What a log must never carry. An address is unmasked when its local part has
 * no `*` — LogMasker writes `j***k@seznam.cz` — so a match may not start right
 * after a `*`, which is what keeps the tail of a masked address from matching.
 */
const ADDRESS =
	/(?<![\w.*%+-])[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+)*\.[A-Za-z]{2,}(?![\w-])/g;
const TOKEN_PATTERNS = [
	['Google access token', /\bya29\.[\w-]{20,}/g],
	['Google refresh token', /(?<![\w/])1\/\/[\w-]{20,}/g],
	['JSON web token', /\beyJ[\w-]{8,}\.[\w-]{8,}\.[\w-]{8,}/g],
	['Microsoft access token', /\bEw[A-Za-z0-9+/]{40,}={0,2}/g],
	['bearer credential', /\bBearer\s+[\w.~+/-]{16,}=*/g],
	// base64 of `user=` and whatever follows: the SASL XOAUTH2 initial response.
	['XOAUTH2 initial response', /\bdXNlcj[0-3][A-Za-z0-9+/]{16,}={0,2}/g],
	[
		'OAuth parameter',
		/\b(?:access_token|refresh_token|id_token|client_secret|code_verifier)["']?\s*[=:]\s*["']?[\w.~+/-]{8,}/g
	]
];

/** Parses one `mail.log` text into entries; a line without a header continues the entry above it. */
export function parseMailLog(text, file) {
	const entries = [];
	const lines = text.split(/\r?\n/);
	lines.forEach((raw, index) => {
		const header = MAIL_HEADER.exec(raw);
		if (header) {
			entries.push({
				file,
				line: index + 1,
				time: parseTime(header[1]),
				level: header[2],
				thread: header[3],
				logger: header[4],
				message: header[5],
				detail: []
			});
		} else if (entries.length > 0 && raw.length > 0) {
			entries[entries.length - 1].detail.push(raw);
		}
	});
	return entries;
}

/** Parses one `audit.log` text; audit timestamps carry no offset and are local time. */
export function parseAuditLog(text, file) {
	const records = [];
	text.split(/\r?\n/).forEach((raw, index) => {
		const match = AUDIT_LINE.exec(raw);
		if (!match) return;
		const [, y, mo, d, h, mi, s, ms] = match;
		records.push({
			file,
			line: index + 1,
			time: new Date(+y, +mo - 1, +d, +h, +mi, +s, +ms),
			outcome: match[10],
			action: match[11],
			detail: match[13]
		});
	});
	return records;
}

function parseTime(text) {
	const time = new Date(text);
	return Number.isNaN(time.getTime()) ? null : time;
}

const inWindow = (since) => (item) => !since || (item.time !== null && item.time >= since);

/**
 * A message reduced to what stays the same between two occurrences, so the
 * report can say "this, 40 times" instead of listing forty lines. Numbers go,
 * and the text is cut at its first `: `, past which Spring and this code put
 * the exception text and the values.
 */
export function messageTemplate(message) {
	const cut = message.indexOf(': ');
	const head = cut === -1 ? message : message.slice(0, cut);
	return head.replace(/\d+/g, '#').slice(0, 120).trim();
}

/**
 * Sorts the entries into the §8.2 verdicts for the hiccup D, groups every other
 * WARN and ERROR, and lists CRITICAL audit records. `since` limits all of it to
 * one run.
 */
export function classify(entries, auditRecords, { since = null } = {}) {
	const d = { retried: [], exhausted: [], classifierMiss: [] };
	const groups = new Map();
	for (const entry of entries.filter(inWindow(since))) {
		if (entry.level !== 'WARN' && entry.level !== 'ERROR') continue;
		const text = [entry.message, ...entry.detail].join('\n');
		if (entry.level === 'WARN' && entry.message.includes(D_RETRIED)) {
			d.retried.push(entry);
			continue;
		}
		if (
			entry.level === 'ERROR' &&
			entry.message.includes(D_EXHAUSTED_LEAD) &&
			entry.message.includes(D_EXHAUSTED)
		) {
			d.exhausted.push(entry);
			continue;
		}
		if (entry.level === 'ERROR' && entry.message.includes(D_CRITICAL) && text.includes(D_CAUSE)) {
			d.classifierMiss.push(entry);
			continue;
		}
		const key = `${entry.level} ${entry.logger} ${messageTemplate(entry.message)}`;
		const group = groups.get(key) ?? {
			level: entry.level,
			logger: entry.logger,
			template: messageTemplate(entry.message),
			count: 0,
			first: entry
		};
		group.count += 1;
		groups.set(key, group);
	}
	const byCount = (a, b) => b.count - a.count || a.template.localeCompare(b.template);
	const all = [...groups.values()];
	return {
		d,
		errors: all.filter((g) => g.level === 'ERROR').sort(byCount),
		warnings: all.filter((g) => g.level === 'WARN').sort(byCount),
		critical: auditRecords.filter(inWindow(since)).filter((r) => r.outcome === 'CRITICAL')
	};
}

/**
 * Finds what a log must never hold: an unmasked address, a token, or one of
 * the literal `secrets` (the session API key, a test password). The match
 * itself is never returned, only a masked hint, so the report can be pasted.
 */
export function findLeaks(text, file, { secrets = [] } = {}) {
	const leaks = [];
	const literals = secrets.filter((secret) => secret.length >= 6);
	text.split(/\r?\n/).forEach((raw, index) => {
		const where = { file, line: index + 1 };
		for (const match of raw.matchAll(ADDRESS)) {
			// `<local@domain>` is how a Message-ID is written as well as an address;
			// both identify a person or a message, so either is a leak, but the
			// kind says which to look for.
			const bracketed = raw[match.index - 1] === '<' && raw[match.index + match[0].length] === '>';
			const kind = bracketed ? 'Message-ID or address in <>' : 'unmasked address';
			leaks.push({ ...where, kind, hint: maskAddress(match[0]) });
		}
		for (const [kind, pattern] of TOKEN_PATTERNS) {
			for (const match of raw.matchAll(pattern)) {
				leaks.push({ ...where, kind, hint: maskSecret(match[0]) });
			}
		}
		for (const secret of literals) {
			if (raw.includes(secret)) {
				leaks.push({ ...where, kind: 'known secret', hint: maskSecret(secret) });
			}
		}
	});
	return leaks;
}

function maskAddress(address) {
	const at = address.indexOf('@');
	return `${address[0]}***${address.slice(at)}`;
}

function maskSecret(secret) {
	return `${secret.slice(0, 4)}… (${secret.length} chars)`;
}

/** Whether the scan should fail: anything §8.2 escalates, a CRITICAL, a leak — and with `strict`, any other ERROR. */
export function verdict(result, leaks, { strict = false } = {}) {
	const reasons = [];
	if (result.d.exhausted.length > 0) reasons.push(`${result.d.exhausted.length} exhausted D retry`);
	if (result.d.classifierMiss.length > 0) {
		reasons.push(`${result.d.classifierMiss.length} D failure the classifier missed`);
	}
	if (result.critical.length > 0) reasons.push(`${result.critical.length} CRITICAL audit record`);
	if (leaks.length > 0) reasons.push(`${leaks.length} leak`);
	if (strict) {
		const errors = result.errors.reduce((sum, group) => sum + group.count, 0);
		if (errors > 0) reasons.push(`${errors} other ERROR (strict)`);
	}
	return { ok: reasons.length === 0, reasons };
}

const where = (item) => `${item.file}:${item.line}`;

/** The human report. Lists places, never the text of a leak. */
export function formatReport({ files, result, leaks, outcome, since }) {
	const out = [];
	out.push(`Log scan over ${files.length} file(s)${since ? ` since ${since.toISOString()}` : ''}`);
	for (const file of files) out.push(`  ${file}`);
	out.push('');
	out.push('Transient IMAP hiccup D (RELEASE_CHECKLIST §8.2)');
	out.push(
		`  retried and recovered: ${result.d.retried.length} (expected noise, record the count)`
	);
	out.push(`  retries exhausted:     ${result.d.exhausted.length} (should be 0)`);
	out.push(`  classifier missed:     ${result.d.classifierMiss.length} (should be 0)`);
	for (const entry of [...result.d.exhausted, ...result.d.classifierMiss]) {
		out.push(`    ${where(entry)}  ${entry.time?.toISOString() ?? ''}`);
	}
	out.push('');
	out.push(`CRITICAL audit records: ${result.critical.length}`);
	for (const record of result.critical) out.push(`  ${where(record)}  action=${record.action}`);
	out.push('');
	const section = (title, groups) => {
		const total = groups.reduce((sum, group) => sum + group.count, 0);
		out.push(`${title}: ${total} in ${groups.length} group(s) — explain each or open an issue`);
		for (const group of groups) {
			out.push(`  ${String(group.count).padStart(5)}×  ${group.logger}  ${group.template}`);
			out.push(`          first at ${where(group.first)}`);
		}
		out.push('');
	};
	section('Other ERROR', result.errors);
	section('WARN', result.warnings);
	out.push(`Leaks: ${leaks.length}`);
	for (const leak of leaks) out.push(`  ${where(leak)}  ${leak.kind}: ${leak.hint}`);
	out.push('');
	out.push(outcome.ok ? 'OK' : `FAILED: ${outcome.reasons.join(', ')}`);
	return out.join('\n');
}

/**
 * Orders the files of one log family oldest first: rotated files by the date
 * and index in their name, the live file last. Anything else in the directory
 * is not part of the family.
 */
export function orderLogFiles(names, family) {
	const rotated = new RegExp(
		family === 'mail'
			? '^mail\\.log\\.(\\d{4}-\\d{2}-\\d{2})\\.(\\d+)(?:\\.gz)?$'
			: '^audit\\.(\\d{4}-\\d{2}-\\d{2})\\.(\\d+)\\.log(?:\\.gz)?$'
	);
	const live = `${family}.log`;
	const older = names
		.map((name) => ({ name, match: rotated.exec(name) }))
		.filter(({ match }) => match)
		.sort((a, b) => a.match[1].localeCompare(b.match[1]) || +a.match[2] - +b.match[2])
		.map(({ name }) => name);
	return names.includes(live) ? [...older, live] : older;
}
