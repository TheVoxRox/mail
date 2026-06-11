#!/usr/bin/env node
/**
 * check-translation-whitelist.mjs
 *
 * Find frontend source files that still contain Czech text (diacritics) and
 * verify they are listed in frontend/docs/translation-whitelist.txt.
 *
 * Modes:
 *   --mode=report   List non-whitelisted offenders with counts. Exit 0.
 *                   Use during the migration to track progress.
 *   --mode=strict   Exit 1 if any non-whitelisted file contains diacritics.
 *                   Wire into CI once Phase 5 of the translation migration
 *                   is reached.
 *
 * Default mode is 'report'.
 *
 * Run from frontend/ directory (npm script) or repo root:
 *   node frontend/scripts/check-translation-whitelist.mjs
 *   node frontend/scripts/check-translation-whitelist.mjs --mode=strict
 */

import { readdir, readFile, stat } from 'node:fs/promises';
import path from 'node:path';
import process from 'node:process';
import { fileURLToPath } from 'node:url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);
const REPO_ROOT = path.resolve(__dirname, '..', '..');
const FRONTEND_ROOT = path.resolve(__dirname, '..');
const WHITELIST_FILE = path.join(FRONTEND_ROOT, 'docs', 'translation-whitelist.txt');

const DIACRITICS = /[áéíóúýčďěňřšťůžÁÉÍÓÚÝČĎĚŇŘŠŤŮŽ]/;
const SCAN_EXTENSIONS = new Set(['.ts', '.svelte', '.tsx', '.js', '.mjs', '.json']);
const IGNORED_DIRS = new Set([
	'node_modules',
	'.svelte-kit',
	'build',
	'dist',
	'coverage',
	'target',
	'test-results',
	'.git'
]);

function parseArgs(argv) {
	let mode = 'report';
	for (const arg of argv) {
		if (arg === '--mode=report' || arg === '--mode=strict') {
			mode = arg.slice('--mode='.length);
		} else if (arg === '-h' || arg === '--help') {
			process.stdout.write(
				'Usage: check-translation-whitelist.mjs [--mode=report|--mode=strict]\n'
			);
			process.exit(0);
		} else {
			process.stderr.write(`Unknown argument: ${arg}\nUse --mode=report or --mode=strict.\n`);
			process.exit(2);
		}
	}
	return { mode };
}

async function loadWhitelist() {
	let raw;
	try {
		raw = await readFile(WHITELIST_FILE, 'utf8');
	} catch {
		process.stderr.write(`Whitelist file not found: ${WHITELIST_FILE}\n`);
		process.exit(2);
	}
	const entries = new Set();
	for (const line of raw.split(/\r?\n/)) {
		let trimmed = line.split('#')[0];
		const dashIdx = trimmed.indexOf(' --');
		if (dashIdx >= 0) trimmed = trimmed.slice(0, dashIdx);
		trimmed = trimmed.trim();
		if (!trimmed) continue;
		entries.add(trimmed.replace(/\\/g, '/'));
	}
	return entries;
}

async function* walk(dir) {
	let dirents;
	try {
		dirents = await readdir(dir, { withFileTypes: true });
	} catch {
		return;
	}
	for (const entry of dirents) {
		if (IGNORED_DIRS.has(entry.name)) continue;
		const full = path.join(dir, entry.name);
		if (entry.isDirectory()) {
			yield* walk(full);
		} else if (entry.isFile()) {
			yield full;
		}
	}
}

function countDiacriticLines(content) {
	let count = 0;
	for (const line of content.split(/\r?\n/)) {
		if (DIACRITICS.test(line)) count++;
	}
	return count;
}

async function scan(dir) {
	const offenders = [];
	let totalFiles = 0;
	let totalLines = 0;
	for await (const file of walk(dir)) {
		const ext = path.extname(file);
		if (!SCAN_EXTENSIONS.has(ext)) continue;
		const content = await readFile(file, 'utf8');
		const count = countDiacriticLines(content);
		if (count === 0) continue;
		totalFiles++;
		totalLines += count;
		const rel = path.relative(REPO_ROOT, file).replace(/\\/g, '/');
		offenders.push({ count, rel });
	}
	offenders.sort((a, b) => a.rel.localeCompare(b.rel));
	return { offenders, totalFiles, totalLines };
}

async function main() {
	const { mode } = parseArgs(process.argv.slice(2));
	const whitelist = await loadWhitelist();
	const srcDir = path.join(FRONTEND_ROOT, 'src');

	try {
		await stat(srcDir);
	} catch {
		process.stderr.write(`Source dir not found: ${srcDir}\n`);
		process.exit(2);
	}

	process.stdout.write(`== frontend/src ==\n`);
	const { offenders, totalFiles, totalLines } = await scan(srcDir);
	let offendingFiles = 0;
	let offendingLines = 0;
	for (const { count, rel } of offenders) {
		if (whitelist.has(rel)) continue;
		offendingFiles++;
		offendingLines += count;
		process.stdout.write(`  ${String(count).padStart(4)}  ${rel}\n`);
	}

	process.stdout.write(
		`\nfrontend summary: ${totalFiles} file(s) with diacritics, ${totalLines} line(s) total. Non-whitelisted: ${offendingFiles} file(s), ${offendingLines} line(s).\n\n`
	);

	if (mode === 'strict') {
		if (offendingFiles > 0) {
			process.stderr.write(
				`FAIL (strict): ${offendingFiles} non-whitelisted file(s) contain Czech diacritics.\n` +
					`Either translate the file or add it to frontend/docs/translation-whitelist.txt with a justification.\n`
			);
			process.exit(1);
		}
		process.stdout.write('OK (strict): all files with Czech diacritics are whitelisted.\n');
	} else {
		process.stdout.write(
			`Mode: report. Non-whitelisted offenders: ${offendingFiles} (exit code suppressed).\n`
		);
	}
}

main().catch((err) => {
	process.stderr.write(`check-translation-whitelist: ${err.stack || err.message}\n`);
	process.exit(2);
});
