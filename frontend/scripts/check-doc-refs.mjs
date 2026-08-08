import { execFileSync } from 'node:child_process';
import { existsSync, readFileSync } from 'node:fs';
import path from 'node:path';
import process from 'node:process';

/*
 * Referential integrity for paths and npm scripts named in prose.
 *
 * Markdown links were already safe — a broken one is visible when you click
 * it. What rots unseen is the path mentioned *without* a link: a header
 * comment promising a vendored `cyclonedx-bom-1.5.schema.json` that never
 * existed, a comment pointing at `lib/api/session.ts` when the file lives at
 * `frontend/src/lib/api/session.ts`. Both shipped; both are one `existsSync`
 * away from being caught.
 *
 * Those two examples are spelled without their leading directory on purpose:
 * a reference only counts when it starts at a repo root, so naming a dead
 * path in full would trip this very check. That is the intended behaviour —
 * it fired on this file's own docstring the first time it ran over it.
 *
 * Scope note: historical documents are skipped. A changelog entry describing
 * `V2__add_threading.sql` is correct precisely because the file is gone —
 * demanding it exist would force us to falsify the record.
 *
 * Only tracked files are scanned (`git ls-files`), so a brand-new file's own
 * violations surface on the commit that adds it, not while it is untracked.
 * CI always sees the tracked state, so nothing escapes — it just means a local
 * run before `git add` can look cleaner than the push will.
 */

const repoRoot = path.join(process.cwd(), '..');

/** Documents that describe the past on purpose. Their dead refs are the point. */
const HISTORICAL = [
	'CHANGELOG.md',
	'backend/CHANGELOG.md',
	'todo-archive.md',
	'backend/docs/THREADING_DESIGN.md',
	'backend/docs/OPENAPI_AUDIT.md',
	'docs/COMPOSE_DRAFT_LIFECYCLE.md'
];

/*
 * Paths a document deliberately names before they exist. Each needs a reason,
 * and the check fails once the file appears — otherwise the exemption outlives
 * the thing it was excusing, which is how allowlists become lies.
 */
const PLANNED_REFS = {
	'docs/DECISIONS.md':
		'todo.md plans to move `## Rozhodnuti` here when todo.md is retired post-release'
};

const CODE_ROOTS = [
	'backend/src/main/java',
	'frontend/src',
	'frontend/scripts',
	'frontend/src-tauri/src'
];

const CODE_EXTENSIONS = ['.java', '.ts', '.svelte', '.mjs', '.rs'];

/** Extensions worth resolving. A bare `foo.json` with no slash is too ambiguous. */
const REF_EXTENSIONS =
	'java|ts|tsx|mjs|js|svelte|json|xml|sql|md|ps1|yml|yaml|rs|properties|txt|html|css';

const TOP_LEVEL = ['backend', 'frontend', 'docs', 'scripts', 'src', '.github', '.githooks'];

function tracked(patterns) {
	return execFileSync('git', ['ls-files', ...patterns], { cwd: repoRoot, encoding: 'utf8' })
		.split('\n')
		.filter(Boolean);
}

const packageScripts = new Set(
	Object.keys(
		JSON.parse(readFileSync(path.join(repoRoot, 'frontend/package.json'), 'utf8')).scripts
	)
);

const problems = [];

/**
 * A reference resolves if it is findable from the repo root, from the file's
 * own directory, or from either module root — all three spellings are in use
 * and all three are legible to a reader.
 */
function resolves(ref, fromFile) {
	const bases = [repoRoot, path.dirname(path.join(repoRoot, fromFile)), 'frontend', 'backend'].map(
		(b) => (path.isAbsolute(b) ? b : path.join(repoRoot, b))
	);
	return bases.some((base) => existsSync(path.join(base, ref)));
}

function checkRefs(file, text, { commentsOnly }) {
	text.split('\n').forEach((line, index) => {
		if (commentsOnly && !/^\s*(\*|\/\/|\/\*|#)/.test(line)) return;

		// The trailing lookahead matters: without it the `js` alternative wins
		// inside `.json` and every config file is reported as a missing `.js`.
		const pathPattern = new RegExp(
			`(?:^|[\\s\`"'(\\[])((?:${TOP_LEVEL.join('|')})/[A-Za-z0-9_./{}$-]*\\.(?:${REF_EXTENSIONS})(?![A-Za-z0-9]))`,
			'g'
		);
		for (const match of line.matchAll(pathPattern)) {
			const ref = match[1];
			// Template placeholders (${app.data-dir}/logs/mail.log) are not paths.
			if (ref.includes('${') || ref.includes('{')) continue;
			// Elided prose paths (backend/src/main/java/.../ApiKeyFilter.java).
			if (ref.includes('/...')) continue;
			if (ref in PLANNED_REFS) continue;
			if (!resolves(ref, file)) {
				problems.push(`${file}:${index + 1} references "${ref}", which does not exist`);
			}
		}

		for (const match of line.matchAll(/npm run ([a-z][a-z0-9:-]*)/g)) {
			if (!packageScripts.has(match[1])) {
				problems.push(
					`${file}:${index + 1} references "npm run ${match[1]}", not a script in frontend/package.json`
				);
			}
		}
	});
}

const docFiles = tracked(['*.md', '**/*.md']).filter(
	(f) => !HISTORICAL.includes(f) && !f.includes('node_modules')
);
for (const file of docFiles) {
	checkRefs(file, readFileSync(path.join(repoRoot, file), 'utf8'), { commentsOnly: false });
}

const codeFiles = tracked(CODE_ROOTS).filter((f) => CODE_EXTENSIONS.some((ext) => f.endsWith(ext)));
for (const file of codeFiles) {
	checkRefs(file, readFileSync(path.join(repoRoot, file), 'utf8'), { commentsOnly: true });
}

for (const [ref, reason] of Object.entries(PLANNED_REFS)) {
	if (existsSync(path.join(repoRoot, ref))) {
		problems.push(
			`"${ref}" now exists — drop it from PLANNED_REFS in this script (was: ${reason})`
		);
	}
}

if (problems.length > 0) {
	console.error('Dangling references in docs or comments:');
	for (const problem of problems) console.error(`  - ${problem}`);
	process.exitCode = 1;
} else {
	console.log(
		`Doc refs OK: ${docFiles.length} docs + ${codeFiles.length} source files, ` +
			`every repo path and npm script named in prose exists.`
	);
}
