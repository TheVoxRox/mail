/**
 * Unit tests for parseDotEnv plus a cross-language parity test: the same
 * fixture is parsed by this module and by backend/scripts/lib/Import-DotEnv.ps1
 * (via pwsh), and both must produce identical values — they read the same
 * backend/.env, so a divergence silently feeds dev and the Tauri scripts
 * different environments. The parity test is skipped when pwsh is not on PATH
 * (it is preinstalled on Windows dev machines and GitHub runners).
 */

import { mkdtemp, rm, writeFile } from 'node:fs/promises';
import { spawnSync } from 'node:child_process';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { envForDesktopSidecar, parseDotEnv } from './dotenv.mjs';

const here = path.dirname(fileURLToPath(import.meta.url));
const importDotEnvPs1 = path.resolve(
	here,
	'..',
	'..',
	'..',
	'backend',
	'scripts',
	'lib',
	'Import-DotEnv.ps1'
);

const FIXTURE = [
	'# full-line comment',
	'PLAIN=abc',
	'TRAILING=abc # comment',
	'GLUED=abc#not-a-comment',
	'QUOTED_HASH="abc # data"',
	"SINGLE='abc # data'",
	'QUOTED_TRAIL="abc" # note',
	'URL=http://x/y#frag',
	'SPACES=a b # c',
	'QUOTED_SPACE="padded  "',
	''
].join('\n');

const EXPECTED = {
	PLAIN: 'abc',
	TRAILING: 'abc',
	GLUED: 'abc#not-a-comment',
	QUOTED_HASH: 'abc # data',
	SINGLE: 'abc # data',
	QUOTED_TRAIL: 'abc',
	URL: 'http://x/y#frag',
	SPACES: 'a b',
	QUOTED_SPACE: 'padded  '
};

describe('parseDotEnv', () => {
	it('parses plain values and skips blank lines and full-line comments', () => {
		expect(parseDotEnv(FIXTURE)).toEqual(EXPECTED);
	});

	it('strips an inline comment from an unquoted value', () => {
		expect(parseDotEnv('KEY=value # note')).toEqual({ KEY: 'value' });
	});

	it("keeps a '#' glued to the value (no whitespace before it)", () => {
		expect(parseDotEnv('KEY=http://x/y#frag')).toEqual({ KEY: 'http://x/y#frag' });
	});

	it("keeps a '#' inside a quoted value", () => {
		expect(parseDotEnv('KEY="a # b"')).toEqual({ KEY: 'a # b' });
		expect(parseDotEnv("KEY='a # b'")).toEqual({ KEY: 'a # b' });
	});

	it('unquotes a quoted value that is followed by an inline comment', () => {
		expect(parseDotEnv('KEY="abc" # note')).toEqual({ KEY: 'abc' });
	});

	it('preserves whitespace inside quotes and trims around unquoted values', () => {
		expect(parseDotEnv('KEY="padded  "')).toEqual({ KEY: 'padded  ' });
		expect(parseDotEnv('KEY=  spaced  ')).toEqual({ KEY: 'spaced' });
	});
});

describe('envForDesktopSidecar', () => {
	it('filters the backend crypto material unless explicitly included', () => {
		const values = { MAIL_CRYPTO_KEY: 'k', MAIL_CRYPTO_SALT: 's', OTHER: 'x' };
		expect(envForDesktopSidecar(values, false)).toEqual({ OTHER: 'x' });
		expect(envForDesktopSidecar(values, true)).toEqual(values);
	});
});

const hasPwsh =
	spawnSync(
		'pwsh',
		['-NoProfile', '-NonInteractive', '-Command', '$PSVersionTable.PSVersion.Major'],
		{
			encoding: 'utf8'
		}
	).status === 0;

describe.runIf(hasPwsh)('parity with Import-DotEnv.ps1', () => {
	let tempDir;

	beforeAll(async () => {
		tempDir = await mkdtemp(path.join(os.tmpdir(), 'dotenv-parity-'));
	});

	afterAll(async () => {
		await rm(tempDir, { recursive: true, force: true });
	});

	it('both parsers read the shared fixture identically', async () => {
		const fixturePath = path.join(tempDir, 'parity.env');
		await writeFile(fixturePath, FIXTURE, 'utf8');

		const psQuote = (value) => `'${value.replace(/'/g, "''")}'`;
		const keys = Object.keys(EXPECTED);
		const script = [
			`. ${psQuote(importDotEnvPs1)}`,
			`$null = Import-DotEnv -Path ${psQuote(fixturePath)}`,
			'$out = [ordered]@{}',
			`foreach ($k in @(${keys.map(psQuote).join(', ')})) {`,
			"    $out[$k] = [Environment]::GetEnvironmentVariable($k, 'Process')",
			'}',
			'$out | ConvertTo-Json -Compress'
		].join('\n');

		const result = spawnSync('pwsh', ['-NoProfile', '-NonInteractive', '-Command', script], {
			encoding: 'utf8'
		});
		expect(result.status, result.stderr).toBe(0);

		const psValues = JSON.parse(result.stdout.trim());
		expect(psValues).toEqual(EXPECTED);
		expect(psValues).toEqual(parseDotEnv(FIXTURE));
	}, 30_000);
});
