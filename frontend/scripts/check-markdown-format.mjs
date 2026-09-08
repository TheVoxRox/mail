/**
 * Formatting gate for the repo's Markdown, including everything outside
 * `frontend/`.
 *
 * Why this exists: prettier's config and `node_modules` live in `frontend/`,
 * and the lint step runs `prettier --check .` from there — so the gate only
 * ever saw `frontend/**`. Every other Markdown file in the monorepo (root
 * `CHANGELOG.md`, `todo.md`, `docs/`, `backend/docs/`, `.github/`) was
 * ungated, and 23 of them had drifted out of format by 2026-08-06.
 *
 * The file list comes from `git ls-files`, not a glob: build output and
 * dependencies (`backend/target`, `node_modules`, `.svelte-kit`) can then
 * never be pulled in, and an untracked scratch note cannot fail someone's
 * commit.
 *
 * Config is resolved per file exactly as the prettier CLI would resolve it —
 * files under `frontend/` pick up `frontend/.prettierrc`, everything else gets
 * prettier's defaults, because no config exists above the repo root. That
 * asymmetry is deliberate: matching the CLI is what keeps this check and a
 * manual `prettier --write` from disagreeing.
 *
 * Run with `--write` to fix instead of report — except on a file prettier
 * cannot format stably, which is reported in both modes because rewriting it
 * does not converge. See the note by the second `prettier.format` call.
 */

import { execFileSync } from 'node:child_process';
import { readFile, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

import * as prettier from 'prettier';

const scriptDir = path.dirname(fileURLToPath(import.meta.url));
const repoRoot = path.resolve(scriptDir, '..', '..');
const write = process.argv.includes('--write');

const files = execFileSync('git', ['ls-files', '-z', '--', '*.md'], {
	cwd: repoRoot,
	encoding: 'utf8'
})
	.split('\0')
	.filter(Boolean)
	.sort();

const drifted = [];
const unstable = [];

for (const relative of files) {
	const absolute = path.join(repoRoot, relative);
	const source = await readFile(absolute, 'utf8');
	const options = await prettier.resolveConfig(absolute);
	const formatted = await prettier.format(source, { ...options, filepath: absolute });
	if (formatted === source) continue;

	/*
	 * Formatting is meant to be idempotent, and when it is not, `--write`
	 * cannot fix the file — every run produces something new and the next
	 * check fails again. Telling the author to run `format:md` in that state
	 * is advice they cannot follow, so name the file as unstable instead.
	 *
	 * Known trigger (prettier 3.9.6): a table indented to the content column
	 * of a task-list item, `- [ ] ` then a table at column 6. Each pass
	 * indents every row after the first by four more spaces, without bound.
	 * The same table under a plain `-`, under `1.`, or at top level is fine.
	 */
	const twice = await prettier.format(formatted, { ...options, filepath: absolute });
	if (twice !== formatted) {
		unstable.push(relative);
		continue;
	}

	if (write) {
		await writeFile(absolute, formatted, 'utf8');
	}
	drifted.push(relative);
}

if (unstable.length > 0) {
	throw new Error(
		`${unstable.length} tracked Markdown file(s) cannot be formatted stably:\n` +
			`${unstable.map((file) => `  ${file}`).join('\n')}\n` +
			`Prettier produces different output on each pass, so --write cannot fix this.\n` +
			`Rewrite the construct instead. One known cause is a table indented to the\n` +
			`content column of a task-list item ("- [ ] " then a table at column 6).`
	);
}

if (write) {
	console.log(
		drifted.length === 0
			? `Markdown OK: ${files.length} tracked file(s) already formatted`
			: `Markdown formatted: ${drifted.length} of ${files.length} tracked file(s) rewritten`
	);
	process.exit(0);
}

if (drifted.length > 0) {
	throw new Error(
		`${drifted.length} of ${files.length} tracked Markdown file(s) are not formatted:\n` +
			`${drifted.map((file) => `  ${file}`).join('\n')}\n` +
			`Fix with: cd frontend && npm run format:md`
	);
}

console.log(`Markdown OK: ${files.length} tracked file(s) formatted`);
