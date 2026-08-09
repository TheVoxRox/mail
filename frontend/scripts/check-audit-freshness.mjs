import { execFileSync } from 'node:child_process';
import { existsSync, readFileSync, readdirSync } from 'node:fs';
import path from 'node:path';
import process from 'node:process';

/*
 * Fails when a per-subsystem security audit falls behind the code it audits.
 *
 * Every audit in docs/ carries an `Audited commit` — the SHA its claims were
 * verified against (docs/AUDIT_GUIDE.md §2). That row makes drift *visible*
 * but not *detectable*: nothing compared it to the code, so the B3 audit could
 * state "15 controllers" for a month after two more landed. This script closes
 * that gap by asking git the one question nobody was asking: has anything in
 * the audited subsystem changed since the audit read it?
 *
 * The `Code paths` header row is what makes that question answerable — it is
 * the machine-readable version of the audit's scope claim. Getting it wrong is
 * itself worth arguing about, which is the point: an implicit scope cannot be
 * argued with.
 *
 * Known drift is acknowledged in docs/audit-freshness.json rather than
 * silently tolerated. A PR that touches audited paths either re-verifies the
 * audit (bump `Audited commit`, add a change-log entry) or records why the
 * change cannot move a verdict. Both are honest; forgetting is what this
 * prevents.
 *
 * ---
 *
 * The comparison is by CONTENT, not by history: for each audited path we
 * record git's tree (or blob) object id and compare object ids, rather than
 * asking `git log` what happened between two commits.
 *
 * That is not a stylistic choice. A commit SHA does not survive a squash
 * merge, and this repo squashes: an acknowledgement written during a PR names
 * the branch commit, which stops existing the moment the PR lands, so the gate
 * that was green on the author's machine goes red on main and needs a
 * follow-up commit that changes nothing but a SHA. That cycle was paid six
 * times in the two days after this check was introduced (b89159b, e886c70,
 * 59da66a and the three the previous ledger README counted).
 *
 * An object id has none of that problem, because squashing rewrites history
 * and not content — verified on both of the merges that motivated the change:
 * `feature/mail/service` hashed a3ebca31 at branch commit dc40f97 and at
 * squash commit 5c76f91; `src-tauri/src` hashed 452e55d0 at 4805117 and at
 * c16b3df. The same property makes it immune to rebase, cherry-pick and force
 * push, and it is strictly more precise: a change that is reverted within the
 * range reads as drift under `git log` and correctly reads as none here.
 *
 * `Audited commit` stays in the audit document. It is the human anchor into
 * history and the range this script prints for context — it is just no longer
 * what the pass/fail decision hangs on.
 */

const repoRoot = path.join(process.cwd(), '..');
const docsDir = path.join(repoRoot, 'docs');
const ledgerPath = path.join(docsDir, 'audit-freshness.json');

function git(args, { quiet = false } = {}) {
	return execFileSync('git', args, {
		cwd: repoRoot,
		encoding: 'utf8',
		// An unknown SHA is an expected outcome here, not a crash — keep git's
		// "Not a valid object name" off the console so the report reads clean.
		stdio: quiet ? ['ignore', 'pipe', 'ignore'] : undefined
	}).trim();
}

function isCommit(sha) {
	try {
		return git(['cat-file', '-t', sha], { quiet: true }) === 'commit';
	} catch {
		return false;
	}
}

/**
 * The object id git stores for `path` at `rev` — a tree for a directory, a
 * blob for a file. Identical content always yields an identical id, which is
 * the whole point: it is stable across squash, rebase and cherry-pick.
 * Returns null when the path does not exist at that revision.
 */
function objectIdAt(rev, filePath) {
	try {
		return git(['rev-parse', `${rev}:${filePath}`], { quiet: true });
	} catch {
		return null;
	}
}

/** Pulls the first backticked value out of a `| **Label** | ... |` header row. */
function headerValue(source, label) {
	const row = new RegExp(`^\\|\\s*\\*\\*${label}\\*\\*\\s*\\|([^|]*)\\|`, 'm').exec(source);
	if (!row) return null;
	const first = /`([^`]+)`/.exec(row[1]);
	return first ? first[1] : null;
}

/** Pulls every backticked value out of a header row (the path list). */
function headerValues(source, label) {
	const row = new RegExp(`^\\|\\s*\\*\\*${label}\\*\\*\\s*\\|([^|]*)\\|`, 'm').exec(source);
	if (!row) return null;
	return [...row[1].matchAll(/`([^`]+)`/g)].map((m) => m[1]);
}

const auditFiles = readdirSync(docsDir)
	.filter((name) => name.endsWith('_AUDIT.md'))
	.sort();

if (auditFiles.length === 0) {
	console.error('No docs/*_AUDIT.md found — has the audit set moved?');
	process.exit(1);
}

const ledger = existsSync(ledgerPath) ? JSON.parse(readFileSync(ledgerPath, 'utf8')) : {};
const problems = [];
const notices = [];
const rows = [];

for (const file of auditFiles) {
	const rel = `docs/${file}`;
	const source = readFileSync(path.join(docsDir, file), 'utf8');

	const auditedCommit = headerValue(source, 'Audited commit');
	const codePaths = headerValues(source, 'Code paths');

	if (!auditedCommit) {
		problems.push(`${rel}: no \`Audited commit\` header row (AUDIT_GUIDE.md §2 requires one)`);
		continue;
	}
	if (!codePaths || codePaths.length === 0) {
		problems.push(
			`${rel}: no \`Code paths\` header row — add the git pathspec this audit's scope claim covers`
		);
		continue;
	}

	// A pathspec that no longer exists silently matches nothing, which would
	// make a stale audit look fresh. Catch the rename at the source.
	let pathsUsable = true;
	for (const p of codePaths) {
		if (/[*?[\]]/.test(p)) {
			problems.push(`${rel}: \`Code paths\` entry "${p}" uses a glob — use literal paths`);
			pathsUsable = false;
		} else if (!existsSync(path.join(repoRoot, p))) {
			problems.push(`${rel}: \`Code paths\` entry "${p}" does not exist (renamed or deleted?)`);
			pathsUsable = false;
		}
	}
	if (!pathsUsable) continue;

	const entry = ledger[rel] ?? {};
	const reviewed = entry.reviewedTrees ?? {};
	const auditedCommitResolves = isCommit(auditedCommit);

	// An acknowledgement that names a path the audit no longer claims is an
	// exemption outliving its reason — the same failure the `Code paths`
	// existence check catches from the other side.
	for (const p of Object.keys(reviewed)) {
		if (!codePaths.includes(p)) {
			problems.push(
				`${rel}: audit-freshness.json records "${p}", which is not in this audit's \`Code paths\` — ` +
					`drop it from the ledger (the scope claim narrowed)`
			);
		}
	}

	if (!auditedCommitResolves) {
		// Typically a pre-squash branch SHA: the audit can never be re-verified
		// against a code state nobody can check out. Not fatal when the ledger
		// carries a reviewed object id for every path — those do not depend on
		// history resolving at all.
		const covered = codePaths.every((p) => reviewed[p]);
		if (entry.auditedCommitUnresolved === auditedCommit || covered) {
			notices.push(
				`${rel}: \`Audited commit\` ${auditedCommit} is not in this repository` +
					(covered ? ' (drift is judged from the recorded object ids instead)' : '') +
					(entry.note ? ` — ${entry.note}` : '')
			);
		} else {
			problems.push(
				`${rel}: \`Audited commit\` ${auditedCommit} is not a commit in this repository, and the ` +
					`ledger does not record an object id for every \`Code paths\` entry. Point it at a SHA ` +
					`that is, or record the review in docs/audit-freshness.json.`
			);
			continue;
		}
	}

	const drifted = [];
	for (const p of codePaths) {
		// Baseline: what the reviewer signed off on, or failing that the state
		// the audit itself was written against.
		const baseline = reviewed[p] ?? (auditedCommitResolves ? objectIdAt(auditedCommit, p) : null);
		if (!baseline) {
			problems.push(
				`${rel}: no baseline for "${p}" — it does not exist at \`Audited commit\` ${auditedCommit} ` +
					`and the ledger does not record it. Add it to docs/audit-freshness.json with a note.`
			);
			continue;
		}
		const current = objectIdAt('HEAD', p);
		if (current !== baseline) drifted.push(p);
	}

	const totalDrift = auditedCommitResolves
		? codePaths.filter((p) => objectIdAt(auditedCommit, p) !== objectIdAt('HEAD', p)).length
		: codePaths.length;

	rows.push({ rel, totalDrift, acknowledged: Object.keys(reviewed).length > 0 });

	if (drifted.length > 0) {
		// The object ids decide; the commit list is there so a reviewer knows
		// what to read. It is best-effort — an unresolvable `Audited commit`
		// simply means no range to print.
		let context = '';
		if (auditedCommitResolves) {
			const log = git(['log', '--format=%h %s', `${auditedCommit}..HEAD`, '--', ...drifted])
				.split('\n')
				.filter(Boolean);
			if (log.length > 0) {
				context =
					`\n    Commits touching them since ${auditedCommit}:\n` +
					log.map((line) => `      ${line}`).join('\n');
			}
		}
		problems.push(
			`${rel}: ${drifted.length} audited path(s) changed since the last review:\n` +
				drifted.map((p) => `      ${p}`).join('\n') +
				context +
				`\n    Re-verify the audit and bump \`Audited commit\`, or record the review in ` +
				`docs/audit-freshness.json (reviewedTrees + note). Current object ids:\n` +
				drifted.map((p) => `      "${p}": "${objectIdAt('HEAD', p)}"`).join('\n')
		);
	}
}

for (const notice of notices) console.log(`NOTICE  ${notice}`);

if (problems.length > 0) {
	console.error('Audit freshness problems:');
	for (const problem of problems) console.error(`  - ${problem}`);
	process.exitCode = 1;
} else {
	const behind = rows.filter((r) => r.totalDrift > 0);
	console.log(
		`Audit freshness OK: ${auditFiles.length} audits, no unreviewed drift` +
			(behind.length > 0
				? ` (${behind.length} carry acknowledged drift: ` +
					behind.map((r) => `${path.basename(r.rel)} ${r.totalDrift} path(s)`).join(', ') +
					')'
				: '')
	);
}
