import path from 'node:path';
import { isDeepStrictEqual } from 'node:util';
import { readBlobs, resolveObjects } from './git-objects.mjs';

/**
 * Tells a release version bump from any other change to a JSON file, for
 * check:audits. `npm run bump:version` writes the top-level `version` of
 * frontend/src-tauri/tauri.conf.json, which lies under the updater audit's
 * `Code paths`, so every release cost that audit a dated acknowledgement
 * saying the same thing: no claim of the audit rests on the value. Two of the
 * four standing on 2026-11-12 were exactly that.
 *
 * The direction of error is that of ./comment-only.mjs: anything short of an
 * exact yes is no, and no is drift. The yes is textual, not semantic. The new
 * text has to be the old text with the value of one `"version": "…"` member
 * replaced, byte for byte, and that member has to be the top-level one. A
 * change in whitespace or key order, a value written with escapes, a second
 * member of that shape, or any other edit anywhere in the file is drift.
 */
export function differsOnlyInVersion(filePath, before, after) {
	if (path.extname(filePath).toLowerCase() !== '.json') return false;

	let left;
	let right;
	try {
		left = JSON.parse(before);
		right = JSON.parse(after);
	} catch {
		return false;
	}
	if (!isPlainObject(left) || !isPlainObject(right)) return false;
	if (typeof left.version !== 'string' || typeof right.version !== 'string') return false;
	if (left.version === right.version) return false;

	// The member as JSON.stringify writes its value. A file that spells the
	// value with escapes does not match, which is a no, not a guess.
	const member = new RegExp(
		`("version"\\s*:\\s*)${escapeRegExp(JSON.stringify(left.version))}`,
		'g'
	);
	const matches = [...before.matchAll(member)];
	if (matches.length !== 1) return false;
	const [match] = matches;
	const replaced =
		before.slice(0, match.index) +
		match[1] +
		JSON.stringify(right.version) +
		before.slice(match.index + match[0].length);
	if (replaced !== after) return false;

	// The text test already pins the change to one member whose value is the
	// top-level version. This says the same in the terms the gate cares about,
	// so that a later change to the text test cannot let anything else through.
	return isDeepStrictEqual(withoutVersion(left), withoutVersion(right));
}

/**
 * Whether the file `codePath` went from blob `baseline` to blob `current`
 * through a change to its top-level `version` only. A path that names a
 * directory is not judged: no audited directory holds a file that
 * `bump:version` writes.
 */
export function changedOnlyInVersion(repoRoot, codePath, baseline, current) {
	const objects = resolveObjects(repoRoot, [baseline, current]);
	if (objects.get(baseline)?.type !== 'blob' || objects.get(current)?.type !== 'blob') return false;
	const blobs = readBlobs(repoRoot, [baseline, current]);
	const before = blobs.get(baseline);
	const after = blobs.get(current);
	return before != null && after != null && differsOnlyInVersion(codePath, before, after);
}

function withoutVersion(object) {
	return Object.fromEntries(Object.entries(object).filter(([key]) => key !== 'version'));
}

function isPlainObject(value) {
	return value !== null && typeof value === 'object' && !Array.isArray(value);
}

function escapeRegExp(text) {
	return text.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}
