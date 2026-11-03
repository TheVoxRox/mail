#!/usr/bin/env node
/**
 * Regenerates `frontend/src-tauri/THIRD_PARTY_LICENSES.md`: the crates the
 * `x86_64-pc-windows-msvc` build compiles (the only target VoxRox Mail ships
 * today), with the licence and repository each declares.
 *
 * Run before every release; do not commit Cargo.toml/lock changes without
 * updating this file.
 *
 * Usage: `node frontend/src-tauri/scripts/regen-third-party-licenses.mjs`.
 *
 * Which crates comes from `cargo tree` (see lib/built-crates.mjs for why not
 * `cargo metadata`); what each declares comes from `cargo metadata`. Build-only
 * and dev dependencies are excluded — they do not ship to the end-user
 * binary. The root `app` crate is also excluded.
 */

import { execFileSync } from 'node:child_process';
import { writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
import { builtCrates, SHIPPED_TARGET } from './lib/built-crates.mjs';

const here = path.dirname(fileURLToPath(import.meta.url));
const tauriDir = path.resolve(here, '..');
const outFile = path.join(tauriDir, 'THIRD_PARTY_LICENSES.md');

const raw = execFileSync(
	'cargo',
	['metadata', '--format-version', '1', '--filter-platform', SHIPPED_TARGET],
	{
		cwd: tauriDir,
		encoding: 'utf8',
		maxBuffer: 100 * 1024 * 1024
	}
);

const meta = JSON.parse(raw);

// Exclude the workspace root(s) — those are our own code.
const rootIds = new Set(meta.workspace_default_members);
const built = builtCrates(tauriDir);
const shipped = meta.packages.filter((p) => !rootIds.has(p.id) && built.ships(p.name, p.version));
built.assertAllAccountedFor('THIRD_PARTY_LICENSES.md');

/** @type {Map<string, Array<{ name: string, version: string, repo: string }>>} */
const byLicense = new Map();
for (const pkg of shipped) {
	const license = pkg.license ?? 'UNKNOWN';
	const repo = typeof pkg.repository === 'string' ? pkg.repository : '';
	if (!byLicense.has(license)) byLicense.set(license, []);
	byLicense.get(license).push({ name: pkg.name, version: pkg.version, repo });
}

const sortedLicenses = Array.from(byLicense.keys()).sort((a, b) => {
	const ca = byLicense.get(a).length;
	const cb = byLicense.get(b).length;
	if (ca !== cb) return cb - ca;
	return a.localeCompare(b);
});

const total = shipped.length;
const summary = sortedLicenses.map((lic) => `${byLicense.get(lic).length} ${lic}`).join(', ');

const lines = [
	'# Third-Party Licenses — Tauri Runtime (Cargo crates)',
	'',
	`The Windows build of the Tauri runtime that ships with VoxRox Mail compiles the following Rust crates: the normal dependencies \`cargo tree\` lists for the \`${SHIPPED_TARGET}\` target with the features the build enables. Build-only and dev dependencies are excluded. Procedural macros and their dependencies are included: they run at build time rather than link into the binary, but the code they generate is compiled into it.`,
	'',
	'Dual-licensed crates (e.g. `MIT OR Apache-2.0`) are listed under their canonical license expression — pick either license when redistributing.',
	'',
	`Counts: ${total} crates total. ${summary}.`,
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
console.log(`Wrote ${outFile} — ${total} crates, ${sortedLicenses.length} license groups.`);
