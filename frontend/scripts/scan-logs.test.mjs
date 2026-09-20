import { spawnSync } from 'node:child_process';
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { gzipSync } from 'node:zlib';
import { afterEach, describe, expect, it } from 'vitest';
import {
	classify,
	findLeaks,
	messageTemplate,
	orderLogFiles,
	parseAuditLog,
	parseMailLog,
	verdict
} from './lib/log-scan.mjs';

/*
 * The lines below copy the shape Spring Boot and the AUDIT appender write, and
 * the three D messages MailSyncService logs. If one of those texts changes in
 * the backend, the fixture here has to change with it — which is the point of
 * spelling them out rather than generating them.
 */
const line = (time, level, logger, message, thread = 'mail-sync-1') =>
	`${time} ${level.padStart(5)} 4242 --- [mail] [${thread}] ${logger.padEnd(40)} : ${message}`;
const SYNC = 'o.v.m.f.mail.service.MailSyncService';

const D_RETRIED = line(
	'2026-09-16T01:00:00.000+02:00',
	'WARN',
	SYNC,
	'[SYNC] Transient IMAP error during folder sync INBOX (attempt 1/3); reconnecting and retrying: failed to create new store connection'
);
const D_EXHAUSTED = line(
	'2026-09-16T01:05:00.000+02:00',
	'ERROR',
	SYNC,
	'[SYNC] Folder sync INBOX still failing after 3 transient-retry attempt(s); recording it: failed to create new store connection'
);
const D_MISSED = [
	line(
		'2026-09-16T01:10:00.000+02:00',
		'ERROR',
		SYNC,
		'[SYNC] Critical error during folder sync Sent: boom'
	),
	'jakarta.mail.MessagingException: failed to create new store connection',
	'\tat org.eclipse.angus.mail.imap.IMAPStore.getProtocol(IMAPStore.java:1)'
].join('\n');
const OTHER_ERROR = (n) =>
	line(
		`2026-09-16T02:0${n}:00.000+02:00`,
		'ERROR',
		'o.v.m.core.Something',
		`[DATABASE] Reclaim pass ${n} failed: disk I/O error`
	);
const AUDIT_CRITICAL =
	'2026-09-16 01:00:00.000 [main] ERROR CRITICAL action=db_corruption_detected actor=system detail=quick_check';
const AUDIT_SUCCESS =
	'2026-09-16 01:00:00.000 [main] INFO  SUCCESS action=app_started actor=system detail=v=0.1.0';

describe('parseMailLog', () => {
	it('reads the header and hangs a stack trace on the entry above it', () => {
		const entries = parseMailLog(`${D_RETRIED}\n${D_MISSED}\n`, 'mail.log');
		expect(entries).toHaveLength(2);
		expect(entries[0]).toMatchObject({ level: 'WARN', logger: SYNC, line: 1, file: 'mail.log' });
		expect(entries[0].time?.toISOString()).toBe('2026-09-15T23:00:00.000Z');
		expect(entries[1].detail).toHaveLength(2);
	});
});

describe('classify', () => {
	it('sorts the three D states apart and groups every other ERROR by template', () => {
		const text = [D_RETRIED, D_EXHAUSTED, D_MISSED, OTHER_ERROR(1), OTHER_ERROR(2)].join('\n');
		const result = classify(
			parseMailLog(text, 'mail.log'),
			parseAuditLog(AUDIT_CRITICAL, 'audit.log')
		);
		expect(result.d.retried).toHaveLength(1);
		expect(result.d.exhausted).toHaveLength(1);
		expect(result.d.classifierMiss).toHaveLength(1);
		expect(result.errors).toHaveLength(1);
		expect(result.errors[0]).toMatchObject({
			count: 2,
			template: '[DATABASE] Reclaim pass # failed'
		});
		expect(result.critical).toHaveLength(1);
	});

	it('keeps a critical sync error with another cause among the ordinary errors', () => {
		const other = line(
			'2026-09-16T01:10:00.000+02:00',
			'ERROR',
			SYNC,
			'[SYNC] Critical error during folder sync Sent: quota'
		);
		const result = classify(parseMailLog(other, 'mail.log'), []);
		expect(result.d.classifierMiss).toHaveLength(0);
		expect(result.errors).toHaveLength(1);
	});

	it('drops everything older than since, audit records included', () => {
		const text = [D_EXHAUSTED, OTHER_ERROR(1)].join('\n');
		const since = new Date('2026-09-16T00:01:00.000Z');
		// Audit time is local and carries no offset, so the record is a full day
		// earlier: before `since` in every time zone, on a UTC runner as here.
		const olderAudit = AUDIT_CRITICAL.replace('2026-09-16', '2026-09-15');
		const result = classify(parseMailLog(text, 'mail.log'), parseAuditLog(olderAudit, 'a'), {
			since
		});
		expect(result.d.exhausted).toHaveLength(0);
		expect(result.errors).toHaveLength(1);
		expect(result.critical).toHaveLength(0);
	});

	it('does not count a SUCCESS audit record', () => {
		expect(classify([], parseAuditLog(AUDIT_SUCCESS, 'a')).critical).toHaveLength(0);
	});
});

describe('messageTemplate', () => {
	it('drops numbers and whatever follows the first colon', () => {
		expect(messageTemplate('[IMAP] Pool 3 closed 12 connection(s): timeout after 30 s')).toBe(
			'[IMAP] Pool # closed # connection(s)'
		);
	});
});

describe('findLeaks', () => {
	it('passes a masked address and flags an unmasked one without repeating it', () => {
		const leaks = findLeaks(
			'account j***k@seznam.cz ok\nrejected jan.novak@seznam.cz\nthread root <4f2a.x@seznam.cz>',
			'mail.log'
		);
		expect(leaks).toEqual([
			{ file: 'mail.log', line: 2, kind: 'unmasked address', hint: 'j***@seznam.cz' },
			{ file: 'mail.log', line: 3, kind: 'Message-ID or address in <>', hint: '4***@seznam.cz' }
		]);
	});

	it('flags each token shape', () => {
		const text = [
			'token ya29.a0AfH6SMBx1234567890abcdefghij',
			'refresh 1//0gAbCdEfGhIjKlMnOpQrStUvWx',
			'id eyJhbGciOiJSUzI1.eyJzdWIiOiIxMjM0.c2lnbmF0dXJlMTIz',
			'Authorization: Bearer abcdefghijklmnop1234',
			'AUTHENTICATE XOAUTH2 dXNlcj1qYW5Ac2V6bmFtLmN6AWF1dGg9',
			'body refresh_token=abcdefgh12345'
		].join('\n');
		const kinds = findLeaks(text, 'mail.log').map((leak) => leak.kind);
		expect(kinds).toEqual([
			'Google access token',
			'Google refresh token',
			'JSON web token',
			'bearer credential',
			'XOAUTH2 initial response',
			'OAuth parameter'
		]);
	});

	it('flags a known secret and hints at it by its first characters only', () => {
		const leaks = findLeaks('X-API-KEY was s3cr3t-api-key-value', 'mail.log', {
			secrets: ['s3cr3t-api-key-value', 'short']
		});
		expect(leaks).toEqual([
			{ file: 'mail.log', line: 1, kind: 'known secret', hint: 's3cr… (20 chars)' }
		]);
	});

	it('leaves a Java identity hash and a version alone', () => {
		expect(findLeaks('HikariPool@6d06d69c started, Spring 4.1.1, java 25.0.3', 'mail.log')).toEqual(
			[]
		);
	});
});

describe('verdict', () => {
	const clean = {
		d: { retried: [{}], exhausted: [], classifierMiss: [] },
		errors: [],
		critical: []
	};

	it('passes retried D noise', () => {
		expect(verdict(clean, [])).toEqual({ ok: true, reasons: [] });
	});

	it('fails on an ordinary ERROR only when strict', () => {
		const result = { ...clean, errors: [{ count: 2 }] };
		expect(verdict(result, []).ok).toBe(true);
		expect(verdict(result, [], { strict: true })).toEqual({
			ok: false,
			reasons: ['2 other ERROR (strict)']
		});
	});

	it('fails on a leak', () => {
		expect(verdict(clean, [{}]).reasons).toEqual(['1 leak']);
	});

	it('fails on an audit log that was never read, but only when strict', () => {
		// A clean mail.log on its own is a state this script is asked about, so
		// the plain run stays green; §7 wants a verdict on the audit log, and
		// "there was none to read" is not the zero the report would imply.
		expect(verdict(clean, [], { auditScanned: false }).ok).toBe(true);
		expect(verdict(clean, [], { strict: true, auditScanned: false })).toEqual({
			ok: false,
			reasons: ['no audit log to read (strict)']
		});
	});
});

describe('orderLogFiles', () => {
	it('orders rotated files by date and index and puts the live file last', () => {
		const names = [
			'mail.log',
			'mail.log.2026-09-15.1.gz',
			'audit.log',
			'mail.log.2026-09-15.0.gz',
			'mail.log.2026-09-02.0.gz',
			'audit.2026-09-15.0.log.gz',
			'mail-frontend.log'
		];
		expect(orderLogFiles(names, 'mail')).toEqual([
			'mail.log.2026-09-02.0.gz',
			'mail.log.2026-09-15.0.gz',
			'mail.log.2026-09-15.1.gz',
			'mail.log'
		]);
		expect(orderLogFiles(names, 'audit')).toEqual(['audit.2026-09-15.0.log.gz', 'audit.log']);
	});
});

describe('scan-logs.mjs', () => {
	const script = path.join(path.dirname(fileURLToPath(import.meta.url)), 'scan-logs.mjs');
	let dir;
	afterEach(() => {
		if (dir) rmSync(dir, { recursive: true, force: true });
	});

	const run = (...args) => spawnSync(process.execPath, [script, ...args], { encoding: 'utf8' });

	it('reads a rotated .gz file, which is where a run before midnight ends up', () => {
		dir = mkdtempSync(path.join(os.tmpdir(), 'scan-logs-'));
		const logs = path.join(dir, 'logs');
		mkdirSync(logs);
		writeFileSync(path.join(logs, 'mail.log.2026-09-15.0.gz'), gzipSync(`${D_EXHAUSTED}\n`));
		writeFileSync(path.join(logs, 'mail.log'), `${D_RETRIED}\n`);
		writeFileSync(path.join(logs, 'audit.log'), `${AUDIT_SUCCESS}\n`);

		const result = run('--logs', logs);
		expect(result.status).toBe(1);
		expect(result.stdout).toContain('retries exhausted:     1');
		expect(result.stdout).toContain('mail.log.2026-09-15.0.gz:1');
		expect(result.stdout).toContain('FAILED: 1 exhausted D retry');
	});

	it('adds the API key from session.json and never prints it', () => {
		dir = mkdtempSync(path.join(os.tmpdir(), 'scan-logs-'));
		const logs = path.join(dir, 'logs');
		mkdirSync(logs);
		writeFileSync(
			path.join(dir, 'session.json'),
			JSON.stringify({ apiKey: 'key-0123456789abcdef' })
		);
		writeFileSync(path.join(logs, 'mail.log'), `${OTHER_ERROR(1)} key-0123456789abcdef\n`);

		const result = run('--logs', logs, '--json', path.join(dir, 'report.json'));
		expect(result.status).toBe(1);
		expect(result.stdout).toContain('known secret: key-… (20 chars)');
		expect(result.stdout).not.toContain('key-0123456789abcdef');
	});

	it('says the audit log was not checked rather than reporting zero CRITICAL', () => {
		dir = mkdtempSync(path.join(os.tmpdir(), 'scan-logs-'));
		const logs = path.join(dir, 'logs');
		mkdirSync(logs);
		writeFileSync(path.join(logs, 'mail.log'), `${D_RETRIED}\n`);

		const plain = run('--logs', logs);
		expect(plain.status).toBe(0);
		expect(plain.stdout).toContain('CRITICAL audit records: NOT CHECKED');
		expect(plain.stdout).not.toContain('CRITICAL audit records: 0');

		const strict = run('--logs', logs, '--strict', 'true');
		expect(strict.status).toBe(1);
		expect(strict.stdout).toContain('no audit log to read (strict)');
	});

	it('passes a clean log and exits 2 without one', () => {
		dir = mkdtempSync(path.join(os.tmpdir(), 'scan-logs-'));
		const logs = path.join(dir, 'logs');
		mkdirSync(logs);
		expect(run('--logs', logs).status).toBe(2);
		writeFileSync(path.join(logs, 'mail.log'), `${D_RETRIED}\n`);
		const result = run('--logs', logs);
		expect(result.status).toBe(0);
		expect(result.stdout).toContain('retried and recovered: 1');
	});
});
