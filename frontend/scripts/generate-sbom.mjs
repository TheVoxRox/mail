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
import { existsSync, renameSync, rmSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const here = path.dirname(fileURLToPath(import.meta.url));
const frontendDir = path.resolve(here, '..');
const outFile = path.join(frontendDir, 'bom.json');

/*
 * SvelteKit 3 writes `node_modules/$app` itself (`svelte-kit sync`, run by
 * `prepare`): the `$app/tsconfig` and `$app/types` that tsconfig.json reaches
 * through node resolution. It is not a package and has no package.json, so
 * `npm ls` reports it as an extraneous package named "$app", and
 * cyclonedx-npm stops on it with `Invalid name: "$app"` (4.0.0 and 6.0.1
 * alike). The SBOM job of vuln-scan.yml failed on exactly that.
 * `--package-lock-only` would avoid it but lists every optional platform
 * binary in the lockfile (60 more components on Windows, among them esbuild's
 * builds for other systems) as if the app shipped them.
 *
 * npm skips dot-named directories under node_modules, so the folder is set
 * aside under such a name for the run and put back afterwards, also when the
 * run fails. A folder still set aside by a run that was killed is put back
 * first, unless `svelte-kit sync` has written the folder again since: the copy
 * set aside is then the older of the two and goes, since setting the new one
 * aside onto it fails (EPERM on Windows, ENOTEMPTY elsewhere).
 */
const sveltekitDir = path.join(frontendDir, 'node_modules', '$app');
const asideDir = path.join(frontendDir, 'node_modules', '.sveltekit-app-sbom-aside');
if (existsSync(asideDir)) {
	if (existsSync(sveltekitDir)) rmSync(asideDir, { recursive: true, force: true });
	else renameSync(asideDir, sveltekitDir);
}
const setAside = existsSync(sveltekitDir);

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
if (setAside) renameSync(sveltekitDir, asideDir);
try {
	execSync(command, {
		cwd: frontendDir,
		stdio: 'inherit'
	});
} finally {
	if (setAside) renameSync(asideDir, sveltekitDir);
}

console.log(`Wrote ${path.relative(path.resolve(frontendDir, '..'), outFile)}.`);
