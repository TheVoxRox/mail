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

import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const here = path.dirname(fileURLToPath(import.meta.url));
const backendDir = path.resolve(here, '..');

const mvn = process.platform === 'win32' ? 'mvn.cmd' : 'mvn';
execFileSync(mvn, ['--batch-mode', 'cyclonedx:makeAggregateBom'], {
	cwd: backendDir,
	stdio: 'inherit',
	shell: process.platform === 'win32'
});

console.log('Wrote backend/target/bom.json.');
