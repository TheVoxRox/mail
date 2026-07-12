import { readFile, writeFile } from 'node:fs/promises';
import path from 'node:path';
import process from 'node:process';
import { SEMVER_RE } from './lib/semver.mjs';

const rootDir = process.cwd();
const newVersion = process.argv[2];

if (!newVersion) {
	console.error('Usage: node scripts/bump-version.mjs <X.Y.Z[-prerelease][+build]>');
	process.exit(1);
}

if (!SEMVER_RE.test(newVersion)) {
	console.error(`Not a valid semver: "${newVersion}"`);
	process.exit(1);
}

const targets = [
	{
		label: 'frontend/package.json',
		file: 'package.json',
		update: (src) => replaceJsonField(src, 'version', newVersion)
	},
	{
		label: 'frontend/src-tauri/tauri.conf.json',
		file: 'src-tauri/tauri.conf.json',
		update: (src) => replaceJsonField(src, 'version', newVersion)
	},
	{
		label: 'frontend/src/lib/version.ts',
		file: 'src/lib/version.ts',
		update: (src) =>
			replaceWithCheck(
				src,
				/(CLIENT_VERSION\s*=\s*['"])[^'"]+(['"])/,
				`$1${newVersion}$2`,
				'CLIENT_VERSION literal'
			)
	},
	{
		label: 'frontend/src-tauri/Cargo.toml',
		file: 'src-tauri/Cargo.toml',
		update: (src) =>
			replaceWithCheck(
				src,
				/(^\[package\][^[]*?^version\s*=\s*")[^"]+(")/ms,
				`$1${newVersion}$2`,
				'[package] version'
			)
	},
	{
		label: 'backend/pom.xml',
		file: '../backend/pom.xml',
		update: (src) =>
			replaceWithCheck(
				src,
				/(<\/parent>\s*<groupId>[^<]+<\/groupId>\s*<artifactId>[^<]+<\/artifactId>\s*<version>)[^<]+(<\/version>)/,
				`$1${newVersion}$2`,
				'project <version> after </parent>'
			)
	}
];

function replaceJsonField(source, field, value) {
	const pattern = new RegExp(`("${field}"\\s*:\\s*")[^"]+(")`);
	if (!pattern.test(source)) {
		throw new Error(`JSON field "${field}" not found`);
	}
	return source.replace(pattern, `$1${value}$2`);
}

function replaceWithCheck(source, pattern, replacement, label) {
	if (!pattern.test(source)) {
		throw new Error(`Pattern not found: ${label}`);
	}
	return source.replace(pattern, replacement);
}

const updates = [];
for (const target of targets) {
	const filePath = path.join(rootDir, target.file);
	const source = await readFile(filePath, 'utf8');
	let updated;
	try {
		updated = target.update(source);
	} catch (error) {
		console.error(`Failed in ${target.label}: ${error.message}`);
		process.exit(1);
	}
	if (updated === source) {
		console.log(`= ${target.label} already at ${newVersion}`);
		continue;
	}
	updates.push({ filePath, label: target.label, contents: updated });
}

await Promise.all(updates.map(({ filePath, contents }) => writeFile(filePath, contents, 'utf8')));

for (const { label } of updates) {
	console.log(`+ ${label} -> ${newVersion}`);
}

if (updates.length === 0) {
	console.log(`All files already at ${newVersion}, nothing to do.`);
} else {
	console.log(
		`\nNext steps:\n  - frontend: npm run check:versions\n  - backend: mvn -f ../backend/pom.xml validate\n  - rust (optional): cargo check --manifest-path src-tauri/Cargo.toml\n  - review the diff and commit.`
	);
}
