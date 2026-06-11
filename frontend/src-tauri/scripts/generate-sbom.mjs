#!/usr/bin/env node
/**
 * Generates a CycloneDX SBOM for the Tauri (Rust / Cargo) dependency
 * tree, written to `frontend/src-tauri/bom.json`. Run before every
 * release; the file is published as a CI artefact and consumed by
 * external CVE scanners.
 *
 * Requires `cargo-cyclonedx` installed as a cargo subcommand:
 *   cargo install cargo-cyclonedx
 *
 * Usage: `node frontend/src-tauri/scripts/generate-sbom.mjs`
 *        or `npm run regen:sbom:tauri` (from frontend/).
 */

import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const here = path.dirname(fileURLToPath(import.meta.url));
const tauriDir = path.resolve(here, '..');

try {
	execFileSync('cargo', ['cyclonedx', '--help'], { stdio: 'pipe' });
} catch {
	console.error('cargo-cyclonedx is not installed. Run:');
	console.error('  cargo install cargo-cyclonedx');
	process.exit(1);
}

execFileSync(
	'cargo',
	['cyclonedx', '--format', 'json', '--spec-version', '1.5', '--override-filename', 'bom'],
	{
		cwd: tauriDir,
		stdio: 'inherit'
	}
);

console.log(
	`Wrote ${path.relative(path.resolve(tauriDir, '..', '..'), path.join(tauriDir, 'bom.json'))}.`
);
