#!/usr/bin/env node
/**
 * Regenerates `frontend/THIRD_PARTY_LICENSES.md` from the current npm
 * production dependency tree. Run before every release; do not commit
 * dependency-related changes without updating this file.
 *
 * Usage: `node scripts/regen-third-party-licenses.mjs`
 *   (or `npm run regen:licenses`).
 *
 * The root `mail-frontend` package is excluded — the file lists only
 * third-party deps that ship to end users.
 */

import { execSync } from 'node:child_process';
import { readFileSync, writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const here = path.dirname(fileURLToPath(import.meta.url));
const frontendDir = path.resolve(here, '..');
const outFile = path.join(frontendDir, 'THIRD_PARTY_LICENSES.md');

const pkg = JSON.parse(readFileSync(path.join(frontendDir, 'package.json'), 'utf8'));

// Static command string through the shell — npx is a .cmd shim on Windows,
// which Node refuses to spawn without one (and shell + args array is
// deprecated, DEP0190). npm package names and versions cannot contain
// spaces or shell metacharacters, so no quoting is needed.
const raw = execSync(
	`npx license-checker --production --json --excludePackages ${pkg.name}@${pkg.version}`,
	{
		cwd: frontendDir,
		encoding: 'utf8',
		maxBuffer: 50 * 1024 * 1024
	}
);

const data = JSON.parse(raw);

/** @type {Map<string, Array<{ name: string, version: string, repo: string }>>} */
const byLicense = new Map();
for (const [pkg, info] of Object.entries(data)) {
	const license = info.licenses || 'UNKNOWN';
	const m = pkg.match(/^(.+)@([^@]+)$/);
	if (!m) continue;
	const name = m[1];
	const version = m[2];
	const repo = typeof info.repository === 'string' ? info.repository : '';
	if (!byLicense.has(license)) byLicense.set(license, []);
	byLicense.get(license).push({ name, version, repo });
}

const sortedLicenses = Array.from(byLicense.keys()).sort((a, b) => {
	const ca = byLicense.get(a).length;
	const cb = byLicense.get(b).length;
	if (ca !== cb) return cb - ca;
	return a.localeCompare(b);
});

const total = Object.keys(data).length;
const summary = sortedLicenses.map((lic) => `${byLicense.get(lic).length} ${lic}`).join(', ');

const lines = [
	'# Third-Party Licenses — Frontend (npm)',
	'',
	'VoxRox Mail bundles or transitively depends on the following npm packages. All listed packages are runtime / production dependencies (npm `dependencies`, not `devDependencies`). Development tooling that does not ship code to end users (vitest, playwright, eslint, etc.) is excluded.',
	'',
	`Counts: ${total} packages total. ${summary}.`,
	''
];

for (const license of sortedLicenses) {
	const pkgs = byLicense.get(license).sort((a, b) => a.name.localeCompare(b.name));
	lines.push(`## ${license} (${pkgs.length})`);
	lines.push('');
	for (const { name, version, repo } of pkgs) {
		if (repo) {
			lines.push(`- **${name}** ${version} — [${repo}](${repo})`);
		} else {
			lines.push(`- **${name}** ${version}`);
		}
	}
	lines.push('');
}

writeFileSync(outFile, lines.join('\n'));

// Format the generated markdown so it matches the repo Prettier style and the
// pre-commit format check passes without a manual `npm run format` after every
// regen. Prettier owns the rules we must not hand-replicate here — e.g. a
// license literal like "MIT*" (svelte-toolbelt) is escaped to "MIT\*".
execSync('npx prettier --write THIRD_PARTY_LICENSES.md', {
	cwd: frontendDir,
	stdio: 'ignore'
});

console.log(`Wrote ${outFile} — ${total} packages, ${sortedLicenses.length} license groups.`);
