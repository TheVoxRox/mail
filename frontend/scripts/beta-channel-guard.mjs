import { readFile } from 'node:fs/promises';
import { existsSync } from 'node:fs';
import path from 'node:path';

// Refuses to regress the beta channel manifest to an older version. The beta
// release is a moving pointer refreshed on every published release; without
// this guard, re-publishing an old tag (or a manual re-run against one) would
// silently downgrade what beta users are offered. Exit 0 = proceed with the
// upload, exit 1 = refuse (or usage/parse error) — the workflow fails loudly
// instead of skipping, so an operator always sees why. --force turns a refusal
// into a warning: that path is reserved for a deliberate halt/re-point per the
// OPERATIONS.md runbook.

const args = parseArgs(process.argv.slice(2));
const newManifestPath = args.new;
const currentManifestPath = args.current;
const force = args.force === 'true';

if (!newManifestPath) {
	throw new Error('--new <path to candidate latest.json> is required.');
}

const newVersion = await readManifestVersion(newManifestPath);

if (!currentManifestPath || !existsSync(currentManifestPath)) {
	console.log(`Beta channel has no current manifest; accepting ${newVersion}.`);
	process.exit(0);
}

const currentVersion = await readManifestVersion(currentManifestPath);
const order = compareSemver(newVersion, currentVersion);

if (order >= 0) {
	console.log(`Beta channel: ${currentVersion} -> ${newVersion} (ok).`);
	process.exit(0);
}

if (force) {
	console.warn(
		`Beta channel: FORCED regression ${currentVersion} -> ${newVersion} (halt/re-point).`
	);
	process.exit(0);
}

console.error(
	`Beta channel: refusing regression ${currentVersion} -> ${newVersion}. ` +
		'Re-run with force=true only for a deliberate halt/re-point (see backend/OPERATIONS.md).'
);
process.exit(1);

async function readManifestVersion(manifestPath) {
	const manifest = JSON.parse(await readFile(path.resolve(manifestPath), 'utf8'));
	if (!manifest.version) {
		throw new Error(`Missing version in ${manifestPath}`);
	}
	parseSemver(manifest.version);
	return manifest.version;
}

// Full SemVer 2.0.0 precedence (spec §11): numeric core, then prerelease
// identifiers compared one by one (numeric < alphanumeric, numeric compared as
// numbers, alphanumeric as ASCII strings; a release outranks any prerelease of
// the same core). Build metadata is ignored. Kept dependency-free on purpose —
// this runs inside a minimal CI job.
function compareSemver(a, b) {
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

function parseSemver(value) {
	const match = /^(\d+)\.(\d+)\.(\d+)(?:-([0-9A-Za-z.-]+))?(?:\+[0-9A-Za-z.-]+)?$/.exec(value);
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

function parseArgs(argv) {
	const parsed = {};
	for (let i = 0; i < argv.length; i += 1) {
		const item = argv[i];
		if (!item.startsWith('--')) {
			throw new Error(`Unexpected argument: ${item}`);
		}

		const [rawKey, inlineValue] = item.slice(2).split('=', 2);
		const value = inlineValue ?? argv[++i];
		if (!rawKey || value === undefined) {
			throw new Error(`Missing value for argument: ${item}`);
		}
		parsed[rawKey] = value;
	}
	return parsed;
}
