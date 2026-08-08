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
 * change cannot move a verdict (bump `reviewedAt` with a one-line `note`).
 * Both are honest; forgetting is what this prevents.
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
	for (const p of codePaths) {
		if (/[*?[\]]/.test(p)) {
			problems.push(`${rel}: \`Code paths\` entry "${p}" uses a glob — use literal paths`);
		} else if (!existsSync(path.join(repoRoot, p))) {
			problems.push(`${rel}: \`Code paths\` entry "${p}" does not exist (renamed or deleted?)`);
		}
	}

	const entry = ledger[rel] ?? {};

	if (!isCommit(auditedCommit)) {
		// Typically a pre-squash branch SHA: the audit can never be re-verified
		// against a code state nobody can check out.
		if (entry.auditedCommitUnresolved === auditedCommit) {
			notices.push(
				`${rel}: \`Audited commit\` ${auditedCommit} is not in this repository` +
					(entry.note ? ` — ${entry.note}` : '')
			);
		} else {
			problems.push(
				`${rel}: \`Audited commit\` ${auditedCommit} is not a commit in this repository. ` +
					`Point it at a SHA that is, or record it in docs/audit-freshness.json.`
			);
		}
		continue;
	}

	const since = entry.reviewedAt ?? auditedCommit;
	if (!isCommit(since)) {
		problems.push(`${rel}: audit-freshness.json reviewedAt "${since}" is not a commit`);
		continue;
	}

	const newDrift = git(['log', '--format=%h %s', `${since}..HEAD`, '--', ...codePaths])
		.split('\n')
		.filter(Boolean);
	const totalDrift = git(['log', '--format=%h', `${auditedCommit}..HEAD`, '--', ...codePaths])
		.split('\n')
		.filter(Boolean).length;

	rows.push({ rel, auditedCommit, totalDrift, acknowledged: Boolean(entry.reviewedAt) });

	if (newDrift.length > 0) {
		problems.push(
			`${rel}: ${newDrift.length} unreviewed commit(s) touch the audited paths since ` +
				`${since === auditedCommit ? `the audit (${auditedCommit})` : `the last review (${since})`}:\n` +
				newDrift.map((line) => `      ${line}`).join('\n') +
				`\n    Re-verify the audit and bump \`Audited commit\`, or record the review in ` +
				`docs/audit-freshness.json (reviewedAt + note).`
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
					behind.map((r) => `${path.basename(r.rel)} +${r.totalDrift}`).join(', ') +
					')'
				: '')
	);
}
