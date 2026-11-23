import { execFileSync } from 'node:child_process';
import path from 'node:path';
import process from 'node:process';
import { fileURLToPath } from 'node:url';

/*
 * Refuses a change to a database migration that a release carries.
 *
 * An installation records every migration it has run in
 * `flyway_schema_history`. A migration edited after release runs again
 * nowhere: the installations that ran the released version keep its schema,
 * new ones get the edited one, and both report the same schema version. For
 * a SQL migration Flyway notices at the next start, because it recorded a
 * checksum of the file, and the sidecar refuses to start with exit code 70
 * (OPERATIONS.md, "Update troubleshooting"). For a Java migration it notices
 * nothing: Flyway records one without a checksum unless the class overrides
 * `getChecksum()`, and it compares two checksums only when neither is null
 * (measured in FlywayAlteredMigrationDetectionTest, #997). The installations
 * of `V2__Fold_keys_and_strip_text` hold null for good, so for that one this
 * gate is the only guard there is.
 *
 * The rule, for SQL and Java alike: every file under the two migration
 * directories that exists in a `v*` tag merged into HEAD must exist in HEAD
 * with the same blob. An edit, a deletion and a rename all fail. A migration
 * no tag carries yet is free to change, which is how a migration is written
 * before it ships. Every `v*` tag counts, a draft's included: telling a
 * published release from a draft needs the GitHub API, and a re-cut deletes
 * the tag before it tags again. The `beta` channel tag does not match `v*`.
 *
 * Compared by git object id, the way check:audits compares an audit's code
 * paths, and against HEAD's tree rather than the working tree: what is
 * checked is what a push sends.
 *
 * With no `v*` tag reachable there is nothing to compare against, and that
 * fails rather than passes: a shallow clone fetches no tags, and a gate that
 * passes for lack of input looks exactly like one that passed.
 *
 * Usage: node scripts/check-migrations.mjs
 */

// From where this script lives, as in check-no-nul-bytes.mjs: a hook in a
// linked worktree exports GIT_DIR, and then git takes the current directory for
// the top of the tree.
const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');

const MIGRATION_DIRS = [
	'backend/src/main/resources/db/migration',
	'backend/src/main/java/db/migration'
];

function git(args) {
	return execFileSync('git', args, {
		cwd: repoRoot,
		encoding: 'utf8',
		maxBuffer: 64 * 1024 * 1024
	});
}

/** Path to blob id of every file under the migration directories at `ref`. */
function migrationBlobs(ref) {
	const blobs = new Map();
	const out = git(['ls-tree', '-r', '-z', '--full-tree', ref, '--', ...MIGRATION_DIRS]);
	for (const entry of out.split('\0').filter(Boolean)) {
		// <mode> SP <type> SP <object> TAB <path>
		const tab = entry.indexOf('\t');
		const [, type, id] = entry.slice(0, tab).split(' ');
		if (type === 'blob') blobs.set(entry.slice(tab + 1), id);
	}
	return blobs;
}

const tags = git(['tag', '--list', '--merged', 'HEAD', 'v*'])
	.split('\n')
	.map((tag) => tag.trim())
	.filter(Boolean);

if (tags.length === 0) {
	console.error('No v* tag is reachable from HEAD, so the migrations were not checked.');
	console.error('');
	console.error('A shallow clone fetches no tags. Fetch them with `git fetch --tags`, or in');
	console.error('CI check out with `fetch-depth: 0`.');
	process.exit(1);
}

const head = migrationBlobs('HEAD');
/** path -> { id, tags } for each released version of a file HEAD does not carry. */
const offenders = new Map();
const released = new Set();

for (const tag of tags) {
	for (const [file, id] of migrationBlobs(`refs/tags/${tag}`)) {
		released.add(file);
		if (head.get(file) === id) continue;
		const key = `${file}\0${id}`;
		if (!offenders.has(key)) offenders.set(key, { file, id, tags: [] });
		offenders.get(key).tags.push(tag);
	}
}

if (offenders.size > 0) {
	console.error('A released migration differs from the release that carries it:');
	for (const { file, id, tags: carriedBy } of offenders.values()) {
		const now = head.has(file)
			? `HEAD has ${head.get(file).slice(0, 10)}`
			: 'HEAD does not have it';
		console.error(`  - ${file}`);
		console.error(`    ${carriedBy.join(', ')}: ${id.slice(0, 10)}, ${now}`);
	}
	console.error('');
	console.error('Every installation of those releases has already run the released version,');
	console.error('and does not run it again. Put the file back as the tag has it');
	console.error('(git checkout <tag> -- <path>) and make the change a new migration,');
	console.error('V<n+1>, instead. A deletion or a rename fails the same way.');
	console.error('');
	console.error('If a re-cut moved one of those tags and this clone still has the old one,');
	console.error('`git fetch --tags --force` brings the moved tag.');
	process.exit(1);
}

console.log(
	`Migrations check OK: ${released.size} released migration file(s) unchanged across ${tags.length} v* tag(s).`
);
