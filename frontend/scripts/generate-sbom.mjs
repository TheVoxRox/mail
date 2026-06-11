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

import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const here = path.dirname(fileURLToPath(import.meta.url));
const frontendDir = path.resolve(here, '..');
const outFile = path.join(frontendDir, 'bom.json');

const NPM_CYCLONEDX_VERSION = '4.0.0';

const args = [
	'--yes',
	`@cyclonedx/cyclonedx-npm@${NPM_CYCLONEDX_VERSION}`,
	'--output-format',
	'JSON',
	'--spec-version',
	'1.5',
	'--omit',
	'dev',
	'--output-file',
	outFile,
	'package.json'
];

console.log(`Running @cyclonedx/cyclonedx-npm@${NPM_CYCLONEDX_VERSION} ...`);
execFileSync(process.platform === 'win32' ? 'npx.cmd' : 'npx', args, {
	cwd: frontendDir,
	stdio: 'inherit',
	shell: process.platform === 'win32'
});

console.log(`Wrote ${path.relative(path.resolve(frontendDir, '..'), outFile)}.`);
