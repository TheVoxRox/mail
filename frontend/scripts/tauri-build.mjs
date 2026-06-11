import { spawn } from 'node:child_process';
import { readdir, readFile, rename, rm, stat } from 'node:fs/promises';
import path from 'node:path';

const frontendRoot = process.cwd();
const tauriCli = path.join(frontendRoot, 'node_modules', '@tauri-apps', 'cli', 'tauri.js');

const exitCode = await run(process.execPath, [tauriCli, 'build', ...process.argv.slice(2)]);
if (exitCode !== 0) {
	process.exit(exitCode);
}

await normalizeWindowsNsisArtifacts();

function run(command, args) {
	return new Promise((resolve, reject) => {
		const child = spawn(command, args, {
			cwd: frontendRoot,
			stdio: 'inherit',
			windowsHide: true
		});
		child.once('error', reject);
		child.once('exit', (code) => resolve(code ?? 1));
	});
}

async function normalizeWindowsNsisArtifacts() {
	const config = JSON.parse(
		await readFile(path.join(frontendRoot, 'src-tauri', 'tauri.conf.json'), 'utf8')
	);
	const version = config.version;
	const productName = config.productName ?? 'app';
	const slug = slugify(productName);
	const nsisDir = path.join(frontendRoot, 'src-tauri', 'target', 'release', 'bundle', 'nsis');

	let entries;
	try {
		entries = await readdir(nsisDir, { withFileTypes: true });
	} catch {
		return;
	}

	const setupArtifacts = [];
	for (const entry of entries) {
		if (!entry.isFile()) continue;
		const match = entry.name.match(/(?:_|-)(x64|x86|arm64)-setup\.exe$/i);
		if (!match || !entry.name.toLowerCase().includes(version.toLowerCase())) continue;

		const fullPath = path.join(nsisDir, entry.name);
		setupArtifacts.push({
			name: entry.name,
			path: fullPath,
			arch: match[1].toLowerCase(),
			mtimeMs: (await stat(fullPath)).mtimeMs
		});
	}

	const newestByArch = new Map();
	for (const artifact of setupArtifacts.sort((a, b) => b.mtimeMs - a.mtimeMs)) {
		if (!newestByArch.has(artifact.arch)) {
			newestByArch.set(artifact.arch, artifact);
		}
	}

	for (const artifact of setupArtifacts) {
		const newest = newestByArch.get(artifact.arch);
		if (newest !== artifact) {
			await removeArtifactPair(artifact.path);
		}
	}

	for (const artifact of newestByArch.values()) {
		const normalizedName = `${slug}-${version}-windows-${artifact.arch}-setup.exe`;
		const normalizedPath = path.join(nsisDir, normalizedName);
		if (artifact.path.toLowerCase() !== normalizedPath.toLowerCase()) {
			await rm(normalizedPath, { force: true });
			await rename(artifact.path, normalizedPath);
			await renameIfExists(`${artifact.path}.sig`, `${normalizedPath}.sig`);
			console.log(`Renamed NSIS installer to ${path.relative(frontendRoot, normalizedPath)}`);
		}
	}
}

async function removeArtifactPair(filePath) {
	await rm(filePath, { force: true });
	await rm(`${filePath}.sig`, { force: true });
}

async function renameIfExists(source, target) {
	try {
		await rm(target, { force: true });
		await rename(source, target);
	} catch (error) {
		if (error?.code !== 'ENOENT') throw error;
	}
}

function slugify(value) {
	return value
		.trim()
		.toLowerCase()
		.replace(/[^a-z0-9]+/g, '-')
		.replace(/^-+|-+$/g, '');
}
