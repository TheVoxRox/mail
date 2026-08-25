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
 * Acknowledging was, until this was added, the cheaper of those two forever.
 * It cost one paragraph appended to a free-text field, so the ledger grew a
 * note per PR and nothing ever forced the audit itself to be read again: the
 * IMAP/SMTP entry reached 12 791 characters and seven run-together reviews in
 * the seventeen days after its last re-verification, and its numbering had
 * already lost track of itself ("Sixth commit reviewed" followed later by
 * "Third commit, the dead-code batch"). A gate that is green while the
 * document it guards goes stale is worse than no gate, because it also
 * supplies the confidence.
 *
 * So an acknowledgement is now a dated record and a run of them is capped.
 * The caps are not about any single judgement being wrong — each one reads a
 * real diff and is usually right. They are about the UNION: eight separate
 * "this cannot move the verdict" calls, each made in isolation, compose into
 * a delta nobody has ever judged as a whole, and it is the whole that decides
 * whether a verdict still holds. Ninety days does the same job for an entry
 * that grows slowly instead of quickly.
 *
 * Both caps are deliberately reachable rather than generous. Hitting one is
 * not a failure of process — it is the process, arriving at the moment the
 * audit is due a real read.
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

/** How many acknowledgements may stand before the audit must be re-verified. */
const MAX_ACKNOWLEDGEMENTS = 8;
/** How long the oldest standing acknowledgement may stand, in days. */
const MAX_ACKNOWLEDGED_DAYS = 90;
/*
 * Where the warning starts. A cap that is only ever met as a red build on an
 * unrelated PR gets bypassed on the spot; one that has been announcing itself
 * for two acknowledgements or a fortnight gets planned for.
 */
const WARN_AT_ACKNOWLEDGEMENTS = MAX_ACKNOWLEDGEMENTS - 2;
const WARN_AT_DAYS = MAX_ACKNOWLEDGED_DAYS - 15;

const DAY_MS = 24 * 60 * 60 * 1000;
const ISO_DATE = /^\d{4}-\d{2}-\d{2}$/;

/*
 * Both sides of the age comparison are UTC midnights, so the answer is a whole
 * number of days that does not change with the machine's timezone or with the
 * hour the gate happens to run.
 */
const todayUtc = Math.floor(Date.now() / DAY_MS) * DAY_MS;
const daysSince = (isoDate) => Math.floor((todayUtc - Date.parse(isoDate)) / DAY_MS);

/*
 * Only the drift report's commit list still shells out per call, and only on a
 * run that is already failing — every object id goes through resolveObjects.
 */
function git(args) {
	return execFileSync('git', args, { cwd: repoRoot, encoding: 'utf8' }).trim();
}

/** A resolved `--batch-check` line: `<oid> <type> <size>`. */
const OBJECT_LINE = /^([0-9a-f]{40}) (\S+) (\d+)$/;

/**
 * Resolves every revspec — a bare `<rev>` or a `<rev>:<path>` — in ONE git
 * process, returning `revspec -> { oid, type }` and `null` for anything git
 * cannot resolve.
 *
 * `git cat-file --batch-check` reads revspecs on stdin and writes exactly one
 * line per input line, in order: `<oid> <type> <size>` when it resolves,
 * `<input> missing` when it does not. Results are matched back to inputs BY
 * POSITION, not by parsing the echoed input, because a `Code paths` entry may
 * contain a space and the echo would then be indistinguishable from an oid
 * line's field layout.
 *
 * This replaced one `git rev-parse` per lookup. That form cost ~160 process
 * spawns across six audits, and a spawn is ~43 ms on a Windows laptop with a
 * real-time scanner in the path — so the gate spent ~7 s starting git rather
 * than reading it, growing linearly with every audit added. The batch form
 * resolves the same set in ~0.2 s and no longer scales with the audit count.
 *
 * Unresolvable input is data here, not an error: `missing` is how a pre-squash
 * `Audited commit` reports, which is a case the checks below handle.
 */
function resolveObjects(revspecs) {
	const unique = [...new Set(revspecs)];
	const resolved = new Map();
	if (unique.length === 0) return resolved;

	const stdout = execFileSync('git', ['cat-file', '--batch-check'], {
		cwd: repoRoot,
		encoding: 'utf8',
		input: `${unique.join('\n')}\n`,
		stdio: ['pipe', 'pipe', 'ignore']
	});

	const lines = stdout.split('\n');
	unique.forEach((revspec, index) => {
		const match = OBJECT_LINE.exec(lines[index] ?? '');
		resolved.set(revspec, match ? { oid: match[1], type: match[2] } : null);
	});
	return resolved;
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

/** A note trimmed to one line, so a 13 kB record cannot own the terminal. */
const summarize = (note) => {
	const flat = note.replace(/\s+/g, ' ').trim();
	return flat.length > 140 ? `${flat.slice(0, 137)}...` : flat;
};

/**
 * The dated records justifying one ledger entry, oldest first. Returns `[]`
 * for an audit with no entry, and also for a malformed one — having recorded
 * the problem, so the remaining checks still run and report on the same pass.
 *
 * The shape is enforced rather than assumed because every cap below is
 * measured off it: an undated record cannot age, and a run whose oldest
 * record is not first would age from the wrong end.
 */
function readAcknowledgements(rel, entry, hasEntry) {
	if (!hasEntry) return [];

	// The pre-2026-08 format. Named explicitly rather than ignored: silently
	// reading a string as "no acknowledgements" would drop the justification
	// and the caps at once, and read as a pass.
	if (typeof entry.note === 'string') {
		problems.push(
			`${rel}: audit-freshness.json still carries the single \`note\` string. Replace it with ` +
				`"acknowledgements": [{ "date": "YYYY-MM-DD", "note": "..." }], oldest record first.`
		);
		return [];
	}

	const list = entry.acknowledgements;
	if (!Array.isArray(list) || list.length === 0) {
		problems.push(
			`${rel}: audit-freshness.json records object ids but no "acknowledgements" — an id ` +
				`without the review that produced it is the thing this ledger exists to prevent.`
		);
		return [];
	}

	let previousDate = '';
	for (const [index, record] of list.entries()) {
		const at = `${rel}: acknowledgements[${index}]`;
		if (!record || typeof record !== 'object') {
			problems.push(`${at} is not an object — expected { "date": "YYYY-MM-DD", "note": "..." }`);
			return [];
		}
		if (typeof record.date !== 'string' || !ISO_DATE.test(record.date)) {
			problems.push(
				`${at} has no "date" in YYYY-MM-DD form (found ${JSON.stringify(record.date)})`
			);
			return [];
		}
		if (Number.isNaN(Date.parse(record.date))) {
			problems.push(`${at} has "date": "${record.date}", which is not a real date`);
			return [];
		}
		if (typeof record.note !== 'string' || record.note.trim() === '') {
			problems.push(`${at} has no "note" — the note is the evidence that someone read the diff`);
			return [];
		}
		// ISO dates sort as strings exactly as they sort as dates.
		if (record.date < previousDate) {
			problems.push(
				`${at} is dated ${record.date}, before acknowledgements[${index - 1}] ` +
					`(${previousDate}) — records go oldest first, because the caps age from the first one`
			);
			return [];
		}
		if (daysSince(record.date) < 0) {
			problems.push(`${at} is dated ${record.date}, which is in the future`);
			return [];
		}
		previousDate = record.date;
	}
	return list;
}

/*
 * A missing docs/ is the strongest form of "the audit set moved", which is
 * exactly what the message below is for — so it has to reach that message
 * rather than throw ENOENT out of readdirSync with a stack trace.
 */
const auditFiles = existsSync(docsDir)
	? readdirSync(docsDir)
			.filter((name) => name.endsWith('_AUDIT.md'))
			.sort()
	: [];

if (auditFiles.length === 0) {
	console.error('No docs/*_AUDIT.md found — has the audit set moved?');
	process.exit(1);
}

const ledger = existsSync(ledgerPath) ? JSON.parse(readFileSync(ledgerPath, 'utf8')) : {};
const problems = [];
const notices = [];
const rows = [];
const audits = [];

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

	audits.push({ rel, auditedCommit, codePaths });
}

/*
 * Every object id the checks below compare is known by now, so ask git once.
 * Both sides of every comparison go in: the current id, the id at the audit's
 * own anchor, and the anchor itself — whose type is what answers isCommit.
 */
const wanted = [];
for (const { auditedCommit, codePaths } of audits) {
	wanted.push(auditedCommit);
	for (const p of codePaths) {
		wanted.push(`HEAD:${p}`);
		wanted.push(`${auditedCommit}:${p}`);
	}
}
const resolved = resolveObjects(wanted);

/** Whether `sha` names a commit that exists in this repository. */
const isCommit = (sha) => resolved.get(sha)?.type === 'commit';

/**
 * The object id git stores for `path` at `rev` — a tree for a directory, a
 * blob for a file. Identical content always yields an identical id, which is
 * the whole point: it is stable across squash, rebase and cherry-pick.
 * Returns null when the path does not exist at that revision.
 */
const objectIdAt = (rev, filePath) => resolved.get(`${rev}:${filePath}`)?.oid ?? null;

for (const { rel, auditedCommit, codePaths } of audits) {
	const hasEntry = Object.hasOwn(ledger, rel);
	const entry = ledger[rel] ?? {};
	const reviewed = entry.reviewedTrees ?? {};
	const acknowledgements = readAcknowledgements(rel, entry, hasEntry);
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

	/*
	 * The caps end a run of acknowledgements, and the only way out of them is
	 * route 1 — so reporting the drift underneath would name a second, weaker
	 * action for the same entry. One instruction, and it is the stronger one.
	 */
	if (acknowledgements.length > 0) {
		const oldest = acknowledgements[0];
		const age = daysSince(oldest.date);
		const exceeded = [];
		if (acknowledgements.length > MAX_ACKNOWLEDGEMENTS) {
			exceeded.push(
				`${acknowledgements.length} acknowledgements stand (cap ${MAX_ACKNOWLEDGEMENTS})`
			);
		}
		if (age > MAX_ACKNOWLEDGED_DAYS) {
			exceeded.push(`the oldest is ${age} days old (cap ${MAX_ACKNOWLEDGED_DAYS})`);
		}

		if (exceeded.length > 0) {
			problems.push(
				`${rel}: acknowledged drift has outrun its cap — ${exceeded.join(', and ')}. ` +
					`No further acknowledgement is available here: re-verify the audit against HEAD, ` +
					`bump \`Audited commit\`, add the change-log entry, and delete this entry from ` +
					`docs/audit-freshness.json. Standing since ${oldest.date}: ${summarize(oldest.note)}`
			);
			continue;
		}

		if (acknowledgements.length >= WARN_AT_ACKNOWLEDGEMENTS || age >= WARN_AT_DAYS) {
			notices.push(
				`${rel}: ${acknowledgements.length}/${MAX_ACKNOWLEDGEMENTS} acknowledgements, oldest ` +
					`${age}/${MAX_ACKNOWLEDGED_DAYS} days — re-verification is due before the cap, and ` +
					`planning it now is cheaper than meeting it on an unrelated PR.`
			);
		}
	}

	if (!auditedCommitResolves) {
		// Typically a pre-squash branch SHA: the audit can never be re-verified
		// against a code state nobody can check out. Not fatal when the ledger
		// carries a reviewed object id for every path — those do not depend on
		// history resolving at all.
		const covered = codePaths.every((p) => reviewed[p]);
		const newest = acknowledgements.at(-1);
		if (entry.auditedCommitUnresolved === auditedCommit || covered) {
			notices.push(
				`${rel}: \`Audited commit\` ${auditedCommit} is not in this repository` +
					(covered ? ' (drift is judged from the recorded object ids instead)' : '') +
					(newest ? ` — ${summarize(newest.note)}` : '')
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

	rows.push({ rel, totalDrift, acknowledgements: acknowledgements.length });

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
	/*
	 * The acknowledgement count is printed on the GREEN run too. The failure
	 * this gate now guards against grew invisibly for seventeen days precisely
	 * because a passing run said nothing about how much was being carried.
	 */
	console.log(
		`Audit freshness OK: ${auditFiles.length} audits, no unreviewed drift` +
			(behind.length > 0
				? ` (${behind.length} carry acknowledged drift: ` +
					behind
						.map(
							(r) =>
								`${path.basename(r.rel)} ${r.totalDrift} path(s), ` +
								`${r.acknowledgements}/${MAX_ACKNOWLEDGEMENTS} acks`
						)
						.join('; ') +
					')'
				: '')
	);
}
