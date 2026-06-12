#!/usr/bin/env node
/**
 * Generates a CycloneDX 1.5 SBOM for the backend Maven dependency tree,
 * written to `backend/target/bom.json`. Run before every release; the
 * file is published as a CI artefact and consumed by external CVE
 * scanners (OWASP Dependency-Check accepts CycloneDX input).
 *
 * Thin wrapper around `mvn cyclonedx:makeAggregateBom` (plugin
 * configuration lives in backend/pom.xml). Centralised here so the
 * release pipeline calls one script per ecosystem.
 *
 * Usage: `node backend/scripts/generate-sbom.mjs`
 *        or `npm run regen:sbom:backend` (from frontend/).
 */

import { execSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const here = path.dirname(fileURLToPath(import.meta.url));
const backendDir = path.resolve(here, '..');

// Static command string through the shell — mvn is a .cmd shim on Windows,
// which Node refuses to spawn without one (and shell + args array is
// deprecated, DEP0190). Do not switch to ./mvnw: the committed mvnw.cmd
// requires legacy Windows PowerShell, which is absent on some dev machines.
execSync('mvn --batch-mode cyclonedx:makeAggregateBom', {
	cwd: backendDir,
	stdio: 'inherit'
});

console.log('Wrote backend/target/bom.json.');
