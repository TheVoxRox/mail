/**
 * Single source of the SemVer 2.0.0 grammar and precedence used by the
 * release tooling (bump-version.mjs, beta-channel-guard.mjs). Kept
 * dependency-free on purpose — the beta channel guard runs inside a minimal
 * CI job with no npm install.
 */

export const SEMVER_RE = /^(\d+)\.(\d+)\.(\d+)(?:-([0-9A-Za-z.-]+))?(?:\+[0-9A-Za-z.-]+)?$/;

export function parseSemver(value) {
	const match = SEMVER_RE.exec(value);
	if (!match) {
		throw new Error(`Not a valid semver version: ${value}`);
	}
	return {
		major: Number(match[1]),
		minor: Number(match[2]),
		patch: Number(match[3]),
		prerelease: match[4] ? match[4].split('.') : []
	};
}

// Full SemVer 2.0.0 precedence (spec §11): numeric core, then prerelease
// identifiers compared one by one (numeric < alphanumeric, numeric compared as
// numbers, alphanumeric as ASCII strings; a release outranks any prerelease of
// the same core). Build metadata is ignored.
export function compareSemver(a, b) {
	const left = parseSemver(a);
	const right = parseSemver(b);

	for (const part of ['major', 'minor', 'patch']) {
		if (left[part] !== right[part]) return left[part] < right[part] ? -1 : 1;
	}

	if (left.prerelease.length === 0 && right.prerelease.length === 0) return 0;
	if (left.prerelease.length === 0) return 1;
	if (right.prerelease.length === 0) return -1;

	const length = Math.max(left.prerelease.length, right.prerelease.length);
	for (let i = 0; i < length; i += 1) {
		const l = left.prerelease[i];
		const r = right.prerelease[i];
		if (l === undefined) return -1;
		if (r === undefined) return 1;
		if (l === r) continue;

		const lNumeric = /^\d+$/.test(l);
		const rNumeric = /^\d+$/.test(r);
		if (lNumeric && rNumeric) return Number(l) < Number(r) ? -1 : 1;
		if (lNumeric !== rNumeric) return lNumeric ? -1 : 1;
		return l < r ? -1 : 1;
	}
	return 0;
}
