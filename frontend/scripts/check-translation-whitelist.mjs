#!/usr/bin/env node
/**
 * check-translation-whitelist.mjs
 *
 * Find source files that still contain Czech text (diacritics) and verify
 * they are listed in the per-module translation whitelist. One
 * implementation serves both modules (it replaced the former backend bash
 * variant check-translation-whitelist.sh):
 *
 *   --target=frontend  (default) scan frontend/src against
 *                      frontend/docs/translation-whitelist.txt
 *   --target=backend   scan backend/src/{main,test}/java against
 *                      backend/docs/translation-whitelist.txt
 *
 * Modes:
 *   --mode=report   List non-whitelisted offenders with counts. Exit 0.
 *                   Use during a migration to track progress.
 *   --mode=strict   Exit 1 if any non-whitelisted file contains diacritics.
 *                   Wired into CI for both targets.
 *
 * Default mode is 'report'. Run from anywhere:
 *   node frontend/scripts/check-translation-whitelist.mjs
 *   node frontend/scripts/check-translation-whitelist.mjs --target=backend --mode=strict
 */

import { readdir, readFile, stat } from 'node:fs/promises';
import path from 'node:path';
import process from 'node:process';
import { fileURLToPath } from 'node:url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);
const REPO_ROOT = path.resolve(__dirname, '..', '..');

const DIACRITICS = /[áéíóúýčďěňřšťůžÁÉÍÓÚÝČĎĚŇŘŠŤŮŽ]/;
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

const TARGETS = {
	frontend: {
		whitelistFile: path.join(REPO_ROOT, 'frontend', 'docs', 'translation-whitelist.txt'),
		extensions: new Set(['.ts', '.svelte', '.tsx', '.js', '.mjs', '.json']),
		roots: [{ dir: path.join(REPO_ROOT, 'frontend', 'src'), label: 'frontend/src' }]
	},
	backend: {
		whitelistFile: path.join(REPO_ROOT, 'backend', 'docs', 'translation-whitelist.txt'),
		extensions: new Set(['.java']),
		roots: [
			{
				dir: path.join(REPO_ROOT, 'backend', 'src', 'main', 'java'),
				label: 'backend/src/main/java'
			},
			{
				dir: path.join(REPO_ROOT, 'backend', 'src', 'test', 'java'),
				label: 'backend/src/test/java'
			}
		]
	}
};

function parseArgs(argv) {
	let mode = 'report';
	let target = 'frontend';
	for (const arg of argv) {
		if (arg === '--mode=report' || arg === '--mode=strict') {
			mode = arg.slice('--mode='.length);
		} else if (arg === '--target=frontend' || arg === '--target=backend') {
			target = arg.slice('--target='.length);
		} else if (arg === '-h' || arg === '--help') {
			process.stdout.write(
				'Usage: check-translation-whitelist.mjs [--target=frontend|--target=backend] [--mode=report|--mode=strict]\n'
			);
			process.exit(0);
		} else {
			process.stderr.write(
				`Unknown argument: ${arg}\nUse --target=frontend|backend and --mode=report|strict.\n`
			);
			process.exit(2);
		}
	}
	return { mode, target };
}

async function loadWhitelist(whitelistFile) {
	let raw;
	try {
		raw = await readFile(whitelistFile, 'utf8');
	} catch {
		process.stderr.write(`Whitelist file not found: ${whitelistFile}\n`);
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

async function scan(dir, extensions) {
	const offenders = [];
	let totalFiles = 0;
	let totalLines = 0;
	for await (const file of walk(dir)) {
		const ext = path.extname(file);
		if (!extensions.has(ext)) continue;
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

async function pathExists(target) {
	try {
		await stat(target);
		return true;
	} catch {
		return false;
	}
}

async function main() {
	const { mode, target } = parseArgs(process.argv.slice(2));
	const config = TARGETS[target];
	const whitelist = await loadWhitelist(config.whitelistFile);
	const whitelistRel = path.relative(REPO_ROOT, config.whitelistFile).replace(/\\/g, '/');

	const roots = [];
	for (const root of config.roots) {
		if (await pathExists(root.dir)) roots.push(root);
	}
	if (roots.length === 0) {
		process.stderr.write(`No scan roots found for target ${target}.\n`);
		process.exit(2);
	}

	let offendingFiles = 0;
	for (const root of roots) {
		process.stdout.write(`== ${root.label} ==\n`);
		const { offenders, totalFiles, totalLines } = await scan(root.dir, config.extensions);
		let rootOffendingFiles = 0;
		let rootOffendingLines = 0;
		for (const { count, rel } of offenders) {
			if (whitelist.has(rel)) continue;
			rootOffendingFiles++;
			rootOffendingLines += count;
			process.stdout.write(`  ${String(count).padStart(4)}  ${rel}\n`);
		}
		offendingFiles += rootOffendingFiles;

		process.stdout.write(
			`\n${root.label} summary: ${totalFiles} file(s) with diacritics, ${totalLines} line(s) total. Non-whitelisted: ${rootOffendingFiles} file(s), ${rootOffendingLines} line(s).\n\n`
		);
	}

	if (mode === 'strict') {
		if (offendingFiles > 0) {
			process.stderr.write(
				`FAIL (strict): ${offendingFiles} non-whitelisted file(s) contain Czech diacritics.\n` +
					`Either translate the file or add it to ${whitelistRel} with a justification.\n`
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
