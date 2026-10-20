import { execFileSync } from 'node:child_process';

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
 * POSITION, not by parsing the echoed input, because a path may contain a
 * space and the echo would then be indistinguishable from an oid line's field
 * layout.
 *
 * This replaced one `git rev-parse` per lookup in check-audit-freshness.mjs.
 * That form cost ~160 process spawns across six audits, and a spawn is ~43 ms
 * on a Windows laptop with a real-time scanner in the path — so the gate spent
 * ~7 s starting git rather than reading it, growing linearly with every audit
 * added. The batch form resolves the same set in ~0.2 s and no longer scales
 * with the audit count.
 *
 * Unresolvable input is data here, not an error: `missing` is how a
 * pre-squash commit reports, and how a commit that was never fetched does.
 * Shared with release-status.mjs, which compares a candidate sheet against
 * main and the tag the same way the audit gate compares an audit against its
 * `Code paths`.
 */
export function resolveObjects(repoRoot, revspecs) {
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

/**
 * Reads the content of every blob revspec in ONE git process, returning
 * `revspec -> text` and `null` for anything git cannot resolve or that is not
 * a blob. The batch form of `git show <rev>:<path>`, for release-status, which
 * reads the checklist as it stood at each commit that changed it.
 *
 * `git cat-file --batch` answers each input line with `<oid> <type> <size>`,
 * then exactly `size` bytes and a newline, or with one `<input> missing` line.
 * The content is cut by BYTE count, so the output is kept as a Buffer until
 * each piece is cut out: a size read against a decoded string would land
 * inside the next answer on the first multi-byte character.
 */
export function readBlobs(repoRoot, revspecs) {
	const unique = [...new Set(revspecs)];
	const contents = new Map();
	if (unique.length === 0) return contents;

	const stdout = execFileSync('git', ['cat-file', '--batch'], {
		cwd: repoRoot,
		input: `${unique.join('\n')}\n`,
		maxBuffer: 256 * 1024 * 1024,
		stdio: ['pipe', 'pipe', 'ignore']
	});

	let offset = 0;
	for (const revspec of unique) {
		const end = stdout.indexOf(0x0a, offset);
		const header = stdout.toString('utf8', offset, end === -1 ? stdout.length : end);
		offset = end === -1 ? stdout.length : end + 1;
		const match = OBJECT_LINE.exec(header);
		if (!match) {
			contents.set(revspec, null);
			continue;
		}
		const size = Number(match[3]);
		contents.set(
			revspec,
			match[2] === 'blob' ? stdout.toString('utf8', offset, offset + size) : null
		);
		offset += size + 1;
	}
	return contents;
}
