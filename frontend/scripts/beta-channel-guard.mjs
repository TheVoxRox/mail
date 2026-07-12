import { readFile } from 'node:fs/promises';
import { existsSync } from 'node:fs';
import path from 'node:path';
import { parseArgs } from './lib/cli-args.mjs';
import { compareSemver, parseSemver } from './lib/semver.mjs';

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
