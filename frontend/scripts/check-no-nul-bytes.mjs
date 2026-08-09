import { execFileSync } from 'node:child_process';
import { closeSync, constants, fstatSync, openSync, readFileSync } from 'node:fs';
import path from 'node:path';
import process from 'node:process';

/*
 * Refuses a NUL byte in a file the repo has not declared binary.
 *
 * A NUL turns a source file binary as far as git is concerned: no diff in the
 * PR, no `git blame`, no conflict resolution — just `Bin 5053 -> 5053 bytes`.
 * Nothing else catches it. `tray.ts` shipped one through prettier, eslint,
 * svelte-check and vitest without a murmur, because a NUL is a perfectly legal
 * string character; it surfaced only because someone happened to read a
 * `git diff --stat`. That is not a control.
 *
 * The rule is git's own: a file whose content git treats as binary must say so
 * in .gitattributes. That keeps the list of real binaries explicit and
 * reviewable instead of inferred from an extension list here that would drift
 * the first time someone adds a new asset type.
 *
 * Deliberate NUL in a string stays possible — write it as an escape.
 * MessageStableId used a literal NUL as the field separator in the stableId
 * preimage (the right choice: it cannot occur in a folder name or Message-ID,
 * so field boundaries are unambiguous), which made a load-bearing Java file
 * undiffable for as long as it existed. `"\0mid\0"` compiles to the identical
 * string, so the hashes are byte-for-byte what they were.
 *
 * Usage:
 *   node scripts/check-no-nul-bytes.mjs             all tracked files
 *   node scripts/check-no-nul-bytes.mjs --staged    files staged for commit
 */

/*
 * Asked of git rather than derived from cwd: `npm run` starts this in
 * frontend/, the pre-commit hook starts it from the repo root, and both have
 * to resolve the same tree.
 */
const repoRoot = execFileSync('git', ['rev-parse', '--show-toplevel'], { encoding: 'utf8' }).trim();
const stagedOnly = process.argv.includes('--staged');

function git(args) {
	return execFileSync('git', args, {
		cwd: repoRoot,
		encoding: 'utf8',
		maxBuffer: 64 * 1024 * 1024
	});
}

/**
 * Tracked files, or the ones this commit adds/copies/modifies. Deletions and
 * renames-without-content-change carry nothing to inspect.
 */
function filesToCheck() {
	const args = stagedOnly
		? ['diff', '--cached', '--name-only', '-z', '--diff-filter=ACM']
		: ['ls-files', '-z'];
	return git(args).split('\0').filter(Boolean);
}

/**
 * The subset the repo has declared binary in .gitattributes. `check-attr`
 * answers per path in `path NUL attr NUL value NUL` triples, so one call
 * covers the whole list.
 */
function declaredBinary(files) {
	if (files.length === 0) return new Set();
	const out = execFileSync('git', ['check-attr', '--stdin', '-z', 'binary'], {
		cwd: repoRoot,
		input: files.join('\0'),
		encoding: 'utf8',
		maxBuffer: 64 * 1024 * 1024
	}).split('\0');

	const declared = new Set();
	for (let i = 0; i + 2 < out.length; i += 3) {
		if (out[i + 2] === 'set') declared.add(out[i]);
	}
	return declared;
}

const files = filesToCheck();
const declared = declaredBinary(files);
const offenders = [];

/*
 * Not everything git tracks is a regular file: a symlink is stored as its
 * target path, a submodule as a gitlink. Reading either as bytes fails —
 * EISDIR for a symlink pointing at a directory, which killed the whole run
 * partway through the tree until this was handled.
 *
 * Everything is asked of one open descriptor rather than of the path twice.
 * Stat-then-read is a check against one file and a read of whatever occupies
 * the path a moment later; `fstat` on the descriptor already open cannot be
 * answered about a different file. `O_NOFOLLOW` keeps a symlink from being
 * silently resolved to its target, whose bytes are not this file's content.
 */
function readRegularFile(abs) {
	let fd;
	try {
		fd = openSync(abs, constants.O_RDONLY | (constants.O_NOFOLLOW ?? 0));
	} catch {
		// Gone since git listed it (a staged deletion), or not openable as a
		// plain file: a symlink, a directory, a submodule's gitlink.
		return null;
	}
	try {
		return fstatSync(fd).isFile() ? readFileSync(fd) : null;
	} finally {
		closeSync(fd);
	}
}

for (const file of files) {
	if (declared.has(file)) continue;
	const bytes = readRegularFile(path.join(repoRoot, file));
	if (bytes === null) continue;
	const at = bytes.indexOf(0);
	if (at >= 0) offenders.push({ file, at });
}

if (offenders.length > 0) {
	console.error('NUL bytes in files the repo has not declared binary:');
	for (const { file, at } of offenders) {
		console.error(`  - ${file} (first at byte ${at})`);
	}
	console.error('');
	console.error('git treats these as binary: no diff, no blame, no conflict resolution.');
	console.error('If the NUL is deliberate, write it as a string escape instead');
	console.error('(Java \\0, TypeScript \\u0000). If the file really is binary, declare it');
	console.error('in .gitattributes next to the other binary asset types.');
	process.exitCode = 1;
} else {
	const scope = stagedOnly ? 'staged file(s)' : 'tracked file(s)';
	console.log(
		`NUL check OK: ${files.length} ${scope}, ${declared.size} declared binary and skipped.`
	);
}
