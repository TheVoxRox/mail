import { readdir, readFile, rename, rm, stat } from 'node:fs/promises';
import path from 'node:path';
import process from 'node:process';
import { run } from './lib/run.mjs';
import { requireUsableSidecar } from './lib/sidecar-staleness.mjs';

const frontendRoot = process.cwd();
const tauriCli = path.join(frontendRoot, 'node_modules', '@tauri-apps', 'cli', 'tauri.js');

/*
 * The sidecar check `npm run tauri:dev` makes, made by the same function
 * (lib/sidecar-staleness.mjs) so that the two cannot drift apart. Without it
 * this built the installer around whatever sat in src-tauri/binaries/, and a
 * stale sidecar is worse in an installer than in a dev run: the run is
 * watched from the terminal that started it, while the installer is installed
 * later, somewhere else, and its mismatch reads as a list that never loads.
 * It runs before `tauri build`, which spends minutes on vite and cargo before
 * it looks at the sidecar and then fails only on a launcher it cannot find,
 * never on one that is merely old.
 *
 * MAIL_ALLOW_STALE_SIDECAR is honoured here with the same per-result rule as
 * in a dev run: it waves through a digest mismatch, never a missing or
 * misnamed launcher. That was weighed, not inherited. Against it: an
 * installer outlives the shell it was built in, so a mismatched pair built
 * into one surfaces far from the cause. For it, and deciding:
 * - What reaches users is built only by windows-signed-release.yml, which
 *   packages the sidecar and syncs it from the same checkout a few steps
 *   before this one, so its digest matches and the variable has nothing to
 *   do there; nothing in the workflow sets it. RELEASE_CHECKLIST §3a refuses
 *   a locally assembled installer for release anyway.
 * - A local installer is built to test the installer, the updater or the
 *   shell, and that has the same uses for a mismatched pair a dev run has: a
 *   backend carrying an unrelated edit, without the minutes of a repackage.
 * - Refusing it would not stop such a build, only move it to
 *   `npm run tauri -- build`, which checks nothing at all.
 * So the opt-out is loud instead of absent: the whole report before the
 * build, and a reminder after it, where cargo's output has scrolled the first
 * one away. Recorded in docs/DECISIONS.md with what would reopen it.
 */
let builtOverStaleSidecar;
try {
	builtOverStaleSidecar =
		(await requireUsableSidecar(path.resolve(frontendRoot, '..'), 'tauri-build')) === 'stale';
} catch (error) {
	console.error(error instanceof Error ? error.message : String(error));
	process.exit(1);
}

const exitCode = await run(process.execPath, [tauriCli, 'build', ...process.argv.slice(2)], {
	label: 'tauri build',
	cwd: frontendRoot
});
if (exitCode !== 0) {
	process.exit(exitCode);
}

await normalizeWindowsNsisArtifacts();

if (builtOverStaleSidecar) {
	console.warn(
		'\n[tauri-build] WARNING: this build carries a sidecar that was not packaged from the checked-out\n' +
			'backend (MAIL_ALLOW_STALE_SIDECAR=1). Do not hand the installer on; rebuild with:\n' +
			'  npm run sidecar:rebuild:windows   (from frontend/)\n'
	);
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
