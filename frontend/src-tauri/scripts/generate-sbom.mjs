#!/usr/bin/env node
/**
 * Generates a CycloneDX SBOM for the Tauri (Rust / Cargo) dependency
 * tree, written to `frontend/src-tauri/bom.json`. Run before every
 * release; the file is published as a CI artefact and consumed by
 * external CVE scanners.
 *
 * The target is explicit because cargo-cyclonedx otherwise describes the
 * host: on the `ubuntu-latest` runner in vuln-scan.yml that was a Linux tree
 * of gtk and webkit2gtk without the windows and webview2-com crates the
 * shipped build is made of. Build dependencies stay in — a scanner wants
 * the whole build surface. That also makes the SBOM wider than what ships,
 * so generate-bundled-notice.mjs narrows it before writing NOTICE.txt.
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
import { SHIPPED_TARGET } from './lib/built-crates.mjs';

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
	[
		'cyclonedx',
		'--format',
		'json',
		'--spec-version',
		'1.5',
		'--target',
		SHIPPED_TARGET,
		'--override-filename',
		'bom'
	],
	{
		cwd: tauriDir,
		stdio: 'inherit'
	}
);

console.log(
	`Wrote ${path.relative(path.resolve(tauriDir, '..', '..'), path.join(tauriDir, 'bom.json'))}.`
);
