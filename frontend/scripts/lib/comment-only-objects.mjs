import { execFileSync } from 'node:child_process';
import { differsOnlyInComments } from './comment-only.mjs';
import { readBlobs, resolveObjects } from './git-objects.mjs';

/**
 * Whether `codePath` went from git object `baseline` to object `current`
 * through changes to comments and layout only, as ./comment-only.mjs reads
 * them. Shared by check:audits (audit drift) and release:status (whether a
 * manual release section carries), so the two never disagree about what a
 * comment edit is.
 *
 * Every answer short of a confident yes is no: a baseline this clone does not
 * have (an id recorded from a merge commit that never landed), a file added,
 * deleted or with a changed mode, a submodule, a language the library does
 * not cover. A caller runs it only for a path whose id already differs, so a
 * comparison that found nothing moved never pays for it.
 */
export function changedOnlyInComments(repoRoot, codePath, baseline, current) {
	const objects = resolveObjects(repoRoot, [baseline, current]);
	const before = objects.get(baseline);
	const after = objects.get(current);
	if (!before || !after || before.type !== after.type) return false;

	const pairs = [];
	if (before.type === 'blob') {
		pairs.push({ file: codePath, from: baseline, to: current });
	} else if (before.type === 'tree') {
		// `-z` output: ":<mode> <mode> <oid> <oid> <status>" NUL "<path>" NUL, per file.
		const fields = execFileSync(
			'git',
			['diff-tree', '-r', '-z', '--no-renames', baseline, current],
			{ cwd: repoRoot, encoding: 'utf8' }
		).split('\0');
		for (let index = 0; index + 1 < fields.length; index += 2) {
			const [fromMode, toMode, from, to, status] = fields[index].replace(/^:/, '').split(' ');
			if (status !== 'M' || fromMode !== toMode) return false;
			pairs.push({ file: `${codePath}/${fields[index + 1]}`, from, to });
		}
	} else {
		return false;
	}
	if (pairs.length === 0) return false;

	const blobs = readBlobs(
		repoRoot,
		pairs.flatMap(({ from, to }) => [from, to])
	);
	return pairs.every(({ file, from, to }) => {
		const left = blobs.get(from);
		const right = blobs.get(to);
		return left != null && right != null && differsOnlyInComments(file, left, right);
	});
}
