import { readFile } from 'node:fs/promises';
import path from 'node:path';
import process from 'node:process';
import { cargoLockEntry, cargoPackageName } from './lib/cargo-lock.mjs';

const rootDir = process.cwd();

async function readText(relativePath) {
	return readFile(path.join(rootDir, relativePath), 'utf8');
}

async function readJson(relativePath) {
	return JSON.parse(await readText(relativePath));
}

function extractCargoVersion(source) {
	const match = /^\[package\][^[]*?^version\s*=\s*"([^"]+)"/ms.exec(source);
	if (!match) {
		throw new Error('src-tauri/Cargo.toml does not contain a [package] version');
	}
	return match[1];
}

function extractPomVersion(source) {
	const match =
		/<\/parent>\s*<groupId>[^<]+<\/groupId>\s*<artifactId>[^<]+<\/artifactId>\s*<version>([^<]+)<\/version>/.exec(
			source
		);
	if (!match) {
		throw new Error('../backend/pom.xml does not contain a project <version> after </parent>');
	}
	return match[1];
}

function extractClientVersion(source) {
	const match = /CLIENT_VERSION\s*=\s*['"]([^'"]+)['"]/.exec(source);
	if (!match) {
		throw new Error('src/lib/version.ts does not export a literal CLIENT_VERSION');
	}
	return match[1];
}

function extractPackageLockRootVersion(lock) {
	const version = lock.packages?.['']?.version;
	if (typeof version !== 'string') {
		throw new Error('package-lock.json has no packages[""].version');
	}
	return version;
}

function extractCargoLockVersion(source, name) {
	const match = cargoLockEntry(name).exec(source);
	if (!match) {
		throw new Error(`src-tauri/Cargo.lock has no [[package]] entry for "${name}"`);
	}
	return match[2];
}

const [
	packageJson,
	packageLock,
	tauriConfig,
	versionSource,
	cargoSource,
	cargoLockSource,
	pomSource
] = await Promise.all([
	readJson('package.json'),
	readJson('package-lock.json'),
	readJson('src-tauri/tauri.conf.json'),
	readText('src/lib/version.ts'),
	readText('src-tauri/Cargo.toml'),
	readText('src-tauri/Cargo.lock'),
	readText('../backend/pom.xml')
]);

const versions = {
	'frontend/package.json': packageJson.version,
	'frontend/package-lock.json': packageLock.version,
	'frontend/package-lock.json packages[""]': extractPackageLockRootVersion(packageLock),
	'frontend/src-tauri/tauri.conf.json': tauriConfig.version,
	'frontend/src/lib/version.ts': extractClientVersion(versionSource),
	'frontend/src-tauri/Cargo.toml': extractCargoVersion(cargoSource),
	'frontend/src-tauri/Cargo.lock': extractCargoLockVersion(
		cargoLockSource,
		cargoPackageName(cargoSource)
	),
	'backend/pom.xml': extractPomVersion(pomSource)
};

const uniqueVersions = new Set(Object.values(versions));
if (uniqueVersions.size > 1) {
	console.error('App versions are not in sync:');
	for (const [file, version] of Object.entries(versions)) {
		console.error(`- ${file}: ${version}`);
	}
	process.exitCode = 1;
} else {
	console.log(`App version sync OK: ${packageJson.version}`);
}
