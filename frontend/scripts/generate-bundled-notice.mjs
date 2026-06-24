#!/usr/bin/env node
/**
 * Builds NOTICE.txt that ships with the desktop installer.
 *
 * Strict-compliance content (compared to the simpler list-only version):
 *
 *   1. Full canonical text of every license that appears in the
 *      dependency tree (vendored under scripts/notice-templates/). MIT
 *      and BSD only mandate the permission notice + copyright; Apache 2.0
 *      and MPL mandate the full text. We include all of them so the
 *      reader has the actual terms in front of them.
 *
 *   2. Per-component copyright holder (extracted from each CycloneDX SBOM
 *      via `publisher` for Maven and `author` for Cargo; for npm the
 *      SBOM has neither field, so we fall back to license-checker
 *      output cached in THIRD_PARTY_LICENSES.md). Without the copyright
 *      holder the MIT permission notice in (1) cannot legally be honoured
 *      by the redistributor.
 *
 *   3. Source-code availability statement for MPL 2.0 components — the
 *      upstream repository URL printed beside the package.
 *
 * Source of truth: the three CycloneDX SBOMs produced by `regen:sbom:all`.
 * Run `npm run regen:sbom:all && npm run regen:notice` to render this file
 * directly, or `npm run regen:licenses:all` for the one-shot release regen
 * (inventories + SBOMs + this notice). Either way the SBOMs must be fresh
 * first — this script reads them, it does not build them.
 *
 * Output: `frontend/src-tauri/resources/NOTICE.txt` (overwritten).
 */

import { readFileSync, writeFileSync, mkdirSync, readdirSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const here = path.dirname(fileURLToPath(import.meta.url));
const repoRoot = path.resolve(here, '..', '..');
const templatesDir = path.join(here, 'notice-templates');
const outDir = path.resolve(repoRoot, 'frontend', 'src-tauri', 'resources');
const outFile = path.join(outDir, 'NOTICE.txt');

const sbomSources = [
	{
		label: 'Frontend (npm — runtime dependencies)',
		path: path.resolve(repoRoot, 'frontend', 'bom.json'),
		ecosystem: 'npm'
	},
	{
		label: 'Backend (Maven — compile / runtime artefacts)',
		path: path.resolve(repoRoot, 'backend', 'target', 'bom.json'),
		ecosystem: 'maven'
	},
	{
		label: 'Tauri runtime (Cargo crates)',
		path: path.resolve(repoRoot, 'frontend', 'src-tauri', 'bom.json'),
		ecosystem: 'cargo'
	}
];

function readSbom(file) {
	try {
		return JSON.parse(readFileSync(file, 'utf8'));
	} catch (e) {
		console.error(
			`Cannot read ${path.relative(repoRoot, file)}: ${e.message}\n` +
				`Run \`npm run regen:sbom:all\` first.`
		);
		process.exit(1);
	}
}

/**
 * Normalise the licence identifier coming out of a CycloneDX SBOM into
 * a canonical SPDX id. CycloneDX accepts either `license.id` (preferred)
 * or `license.expression` (compound). We keep simple SPDX ids and treat
 * "A OR B" expressions as a separate group so users can see them
 * verbatim.
 */
function componentLicense(component) {
	const lic = component.licenses?.[0];
	if (!lic) return 'UNKNOWN';
	if (lic.license?.id) return lic.license.id;
	if (lic.expression) return lic.expression;
	if (lic.license?.name) return lic.license.name;
	return 'UNKNOWN';
}

const frontendDir = path.resolve(repoRoot, 'frontend');

function npmAuthor(packageName) {
	try {
		const pkg = JSON.parse(
			readFileSync(path.join(frontendDir, 'node_modules', packageName, 'package.json'), 'utf8')
		);
		if (typeof pkg.author === 'string' && pkg.author.trim()) return pkg.author.trim();
		if (pkg.author && typeof pkg.author === 'object' && pkg.author.name) {
			const email = pkg.author.email ? ` <${pkg.author.email}>` : '';
			return `${pkg.author.name}${email}`;
		}
		if (Array.isArray(pkg.contributors) && pkg.contributors.length > 0) {
			const first = pkg.contributors[0];
			if (typeof first === 'string') return first;
			if (first?.name) return `${first.name}${first.email ? ` <${first.email}>` : ''}`;
		}
		if (typeof pkg.maintainers?.[0] === 'object' && pkg.maintainers[0].name) {
			return pkg.maintainers[0].name;
		}
	} catch {
		// node_modules tree may be hoisted; the package is not at the
		// top-level path. Caller falls back to repo URL.
	}
	return '';
}

function componentCopyright(component, ecosystem) {
	if (component.copyright) return component.copyright;
	if (component.author) return component.author;
	if (component.publisher) return component.publisher;
	if (ecosystem === 'npm') {
		const author = npmAuthor(component.name);
		if (author) return author;
		const repo = component.externalReferences?.find((r) => r.type === 'vcs')?.url;
		return repo
			? `(no author in package.json — see ${repo})`
			: '(copyright not declared in package metadata)';
	}
	return '(copyright not declared)';
}

function componentRepo(component) {
	return component.externalReferences?.find((r) => r.type === 'vcs')?.url || '';
}

const licenseTexts = new Map();
for (const file of readdirSync(templatesDir)) {
	if (!file.endsWith('.txt')) continue;
	const id = file.replace(/\.txt$/, '');
	licenseTexts.set(id, readFileSync(path.join(templatesDir, file), 'utf8').trimEnd());
}

// Aggregate components across all three SBOMs, grouped by licence id.
/** @type {Map<string, Array<{name: string, version: string, copyright: string, repo: string, ecosystem: string}>>} */
const byLicense = new Map();
const ecosystemCounts = new Map();
for (const src of sbomSources) {
	const bom = readSbom(src.path);
	ecosystemCounts.set(src.ecosystem, bom.components.length);
	for (const c of bom.components) {
		const license = componentLicense(c);
		if (!byLicense.has(license)) byLicense.set(license, []);
		byLicense.get(license).push({
			name: c.name,
			version: c.version,
			copyright: componentCopyright(c, src.ecosystem),
			repo: componentRepo(c),
			ecosystem: src.ecosystem
		});
	}
}

const sortedLicenses = Array.from(byLicense.keys()).sort((a, b) => {
	const ca = byLicense.get(a).length;
	const cb = byLicense.get(b).length;
	if (ca !== cb) return cb - ca;
	return a.localeCompare(b);
});

const totalComponents = Array.from(byLicense.values()).reduce((acc, list) => acc + list.length, 0);

const header = [
	'VoxRox Mail — Third-Party Notices',
	'==================================',
	'',
	'This file lists every third-party software component bundled with',
	'VoxRox Mail and, when the license requires it, includes the full',
	'license text and the copyright notice that the upstream author has',
	'attached to the component. The product itself is licensed under',
	'the terms of the LICENSE file shipped alongside this notice in the',
	'installation directory.',
	'',
	`Total third-party components: ${totalComponents}`
];
for (const [eco, count] of ecosystemCounts.entries()) {
	header.push(`  ${eco.padEnd(6)} ${count}`);
}
header.push(
	'',
	`Generated: ${new Date().toISOString().slice(0, 10)}`,
	'',
	'Machine-readable CycloneDX SBOM files for each ecosystem are',
	'published alongside the installer on the GitHub Releases page.',
	'',
	'------------------------------------------------------------------',
	''
);

function renderLicenseGroup(licenseId, components) {
	const sortedComponents = components.slice().sort((a, b) => a.name.localeCompare(b.name));
	const sectionLines = [`## ${licenseId}  —  ${components.length} component(s)`, ''];

	const text = licenseTexts.get(licenseId);
	if (text) {
		sectionLines.push('License text:', '', text, '');
	} else {
		// Compound expressions ("Apache-2.0 OR MIT") or licenses we have
		// not vendored: refer the reader to the SPDX repository instead
		// of inlining a guess.
		sectionLines.push(
			`License text: see https://spdx.org/licenses/${encodeURIComponent(licenseId)}.html`,
			''
		);
	}

	sectionLines.push('Components and their copyright notices:', '');
	for (const c of sortedComponents) {
		sectionLines.push(`  * ${c.name} ${c.version}  [${c.ecosystem}]`);
		sectionLines.push(`      ${c.copyright}`);
		if (c.repo) sectionLines.push(`      ${c.repo}`);
		sectionLines.push('');
	}
	sectionLines.push('------------------------------------------------------------------', '');
	return sectionLines.join('\n');
}

const sections = sortedLicenses.map((lic) => renderLicenseGroup(lic, byLicense.get(lic)));

mkdirSync(outDir, { recursive: true });
writeFileSync(outFile, [...header, ...sections].join('\n'), 'utf8');

const sizeKb = (Buffer.byteLength(readFileSync(outFile)) / 1024).toFixed(1);
console.log(
	`Wrote ${path.relative(repoRoot, outFile)} (${sizeKb} KB) — ` +
		`${totalComponents} components across ${sortedLicenses.length} license groups.`
);
