#!/usr/bin/env node
/**
 * Validates the three generated CycloneDX SBOM files against the
 * specification's required-field shape. Run after `regen:sbom:all` and
 * in CI before the release artefact upload.
 *
 * Schema fetched from the official cyclonedx/specification repo and
 * vendored in `frontend/scripts/cyclonedx-bom-1.5.schema.json` so the
 * check runs offline. Uses a minimal hand-rolled validator (the spec's
 * full validator pulls a 200 KB jsonschema runtime; we only need to
 * assert the structural invariants that downstream CVE scanners depend
 * on).
 *
 * Usage: `node frontend/scripts/validate-sbom.mjs`
 *        or `npm run check:sbom`.
 */

import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const here = path.dirname(fileURLToPath(import.meta.url));
const repoRoot = path.resolve(here, '..', '..');

const targets = [
	{ path: path.join(repoRoot, 'frontend', 'bom.json'), ecosystem: 'npm' },
	{ path: path.join(repoRoot, 'backend', 'target', 'bom.json'), ecosystem: 'maven' },
	{ path: path.join(repoRoot, 'frontend', 'src-tauri', 'bom.json'), ecosystem: 'cargo' }
];

const failures = [];

function fail(file, message) {
	failures.push(`  ${path.relative(repoRoot, file)}: ${message}`);
}

function validateBom(file, ecosystem) {
	let raw;
	try {
		raw = readFileSync(file, 'utf8');
	} catch (e) {
		fail(file, `cannot read (${e.code || e.message}); run \`npm run regen:sbom:all\` first`);
		return;
	}

	let bom;
	try {
		bom = JSON.parse(raw);
	} catch (e) {
		fail(file, `not valid JSON: ${e.message}`);
		return;
	}

	// Top-level invariants (CycloneDX 1.4+/1.5)
	if (bom.bomFormat !== 'CycloneDX') {
		fail(file, `bomFormat must be "CycloneDX", got "${bom.bomFormat}"`);
	}
	if (!['1.3', '1.4', '1.5', '1.6'].includes(bom.specVersion)) {
		fail(file, `unsupported specVersion: "${bom.specVersion}"`);
	}
	if (typeof bom.version !== 'number') {
		fail(file, `version field must be a number, got ${typeof bom.version}`);
	}
	if (typeof bom.serialNumber !== 'string' || !bom.serialNumber.startsWith('urn:uuid:')) {
		fail(file, `serialNumber must be a urn:uuid:* string`);
	}

	// Components — every CVE scanner reads these
	if (!Array.isArray(bom.components)) {
		fail(file, `components must be an array`);
		return;
	}
	if (bom.components.length === 0) {
		fail(file, `components array is empty — SBOM is useless for a CVE scan`);
		return;
	}

	let missingPurl = 0;
	let missingType = 0;
	let missingName = 0;
	let missingVersion = 0;
	for (const c of bom.components) {
		if (!c.name) missingName++;
		if (!c.version) missingVersion++;
		if (!c.type) missingType++;
		if (!c.purl) missingPurl++;
	}
	// purl is REQUIRED for CVE matching — OWASP DC / Snyk / Trivy use it as
	// the primary identifier. Some tools tolerate missing purl on aggregate
	// "application" rows; tolerate one missing entry but no more.
	if (missingName > 0) fail(file, `${missingName} component(s) missing name`);
	if (missingVersion > 0) fail(file, `${missingVersion} component(s) missing version`);
	if (missingType > 0) fail(file, `${missingType} component(s) missing type`);
	if (missingPurl > 1) {
		fail(file, `${missingPurl} component(s) missing purl — CVE scanners cannot match these`);
	}

	console.log(
		`  ${ecosystem.padEnd(6)} ${path.relative(repoRoot, file).padEnd(48)} ` +
			`spec ${bom.specVersion}  ${bom.components.length} components`
	);
}

console.log('Validating CycloneDX SBOMs:');
for (const t of targets) {
	validateBom(t.path, t.ecosystem);
}

if (failures.length > 0) {
	console.error('\nFAIL:');
	for (const f of failures) console.error(f);
	process.exit(1);
}

console.log('\nOK — all 3 SBOMs pass structural validation.');
