#!/usr/bin/env node
/**
 * Regenerates `frontend/src-tauri/THIRD_PARTY_LICENSES.md` from
 * `cargo metadata` resolved for the `x86_64-pc-windows-msvc` target
 * (the only target VoxRox Mail ships today).
 *
 * Run before every release; do not commit Cargo.toml/lock changes without
 * updating this file.
 *
 * Usage: `node frontend/src-tauri/scripts/regen-third-party-licenses.mjs`.
 *
 * Build-only and dev dependencies are excluded — they do not ship to the
 * end-user binary. The root `app` crate is also excluded.
 */

import { execFileSync } from 'node:child_process';
import { writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const here = path.dirname(fileURLToPath(import.meta.url));
const tauriDir = path.resolve(here, '..');
const outFile = path.join(tauriDir, 'THIRD_PARTY_LICENSES.md');

const raw = execFileSync(
	'cargo',
	['metadata', '--format-version', '1', '--filter-platform', 'x86_64-pc-windows-msvc'],
	{
		cwd: tauriDir,
		encoding: 'utf8',
		maxBuffer: 100 * 1024 * 1024
	}
);

const meta = JSON.parse(raw);

// Walk the resolve graph to find what is actually built into the binary
// (normal dependencies of the root + their transitive normal deps). The
// `packages` list also contains build-only and dev deps which would inflate
// the inventory.
const rootIds = new Set(meta.workspace_default_members);
const normalDeps = new Set();
const queue = Array.from(rootIds);
const byId = new Map(meta.packages.map((p) => [p.id, p]));
const resolveById = new Map((meta.resolve?.nodes ?? []).map((n) => [n.id, n]));

while (queue.length) {
	const id = queue.shift();
	if (normalDeps.has(id)) continue;
	normalDeps.add(id);
	const node = resolveById.get(id);
	if (!node) continue;
	for (const dep of node.deps ?? []) {
		// Each `dep_kinds[].kind` is "normal" | "dev" | "build" | null.
		// Anything that has at least one "normal" kind ships.
		const isNormal = (dep.dep_kinds ?? []).some((dk) => dk.kind === null || dk.kind === 'normal');
		if (isNormal) queue.push(dep.pkg);
	}
}

// Exclude the workspace root(s) — those are our own code.
for (const id of rootIds) normalDeps.delete(id);

/** @type {Map<string, Array<{ name: string, version: string, repo: string }>>} */
const byLicense = new Map();
for (const id of normalDeps) {
	const pkg = byId.get(id);
	if (!pkg) continue;
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

const total = normalDeps.size;
const summary = sortedLicenses.map((lic) => `${byLicense.get(lic).length} ${lic}`).join(', ');

const lines = [
	'# Third-Party Licenses — Tauri Runtime (Cargo crates)',
	'',
	'The Tauri runtime that ships with VoxRox Mail compiles in the following Rust crates (normal dependencies only, resolved for the `x86_64-pc-windows-msvc` target — build-only and dev dependencies are excluded).',
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
