#!/usr/bin/env node
/**
 * Generates a CycloneDX 1.5 SBOM for the frontend npm runtime dependency
 * tree, written to `frontend/bom.json`. Run before every release; the
 * file is published as a CI artefact and consumed by external CVE
 * scanners (OWASP Dependency-Check, Snyk, etc.).
 *
 * The SBOM mirrors `THIRD_PARTY_LICENSES.md` semantically — it lists the
 * production tree (npm `dependencies`, not `devDependencies`). Run after
 * `npm install` so that `package-lock.json` reflects the on-disk state.
 *
 * Usage: `node frontend/scripts/generate-sbom.mjs`
 *        or `npm run regen:sbom`.
 */

import { execSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const here = path.dirname(fileURLToPath(import.meta.url));
const frontendDir = path.resolve(here, '..');
const outFile = path.join(frontendDir, 'bom.json');

const NPM_CYCLONEDX_VERSION = '4.0.0';

// Static command string through the shell — npx is a .cmd shim on Windows,
// which Node refuses to spawn without one (and shell + args array is
// deprecated, DEP0190). The output path is cwd-relative to keep the command
// free of spaces.
const command = [
	'npx --yes',
	`@cyclonedx/cyclonedx-npm@${NPM_CYCLONEDX_VERSION}`,
	'--output-format JSON',
	'--spec-version 1.5',
	'--omit dev',
	'--output-file bom.json',
	'package.json'
].join(' ');

console.log(`Running @cyclonedx/cyclonedx-npm@${NPM_CYCLONEDX_VERSION} ...`);
execSync(command, {
	cwd: frontendDir,
	stdio: 'inherit'
});

console.log(`Wrote ${path.relative(path.resolve(frontendDir, '..'), outFile)}.`);
