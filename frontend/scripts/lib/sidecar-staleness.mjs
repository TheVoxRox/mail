import { createHash } from 'node:crypto';
import { readdir, readFile, stat, writeFile } from 'node:fs/promises';
import path from 'node:path';

/**
 * Does the packaged sidecar carry the backend sources that are checked out now?
 *
 * `npm run tauri:dev` serves the frontend from vite, which always reflects the
 * working tree, but it launches whatever binary happens to sit in
 * `src-tauri/binaries/`. Nothing rebuilds that and nothing used to say a word
 * about it, so the two halves drift apart silently and the app breaks far from
 * the cause: a response missing a field the frontend now requires reads as "the
 * list is stuck loading", not as "your backend is old" (#252, #253).
 *
 * The comparison is over **content**, not modification times. A checkout, a
 * pull or a branch switch rewrites mtimes without changing a byte, so an
 * mtime check calls a perfectly good sidecar stale every time — and a check
 * that cries wolf gets silenced. Same reasoning that moved `check:audits` from
 * commit SHAs to git object ids. Hashing the working tree rather than asking
 * git also means an uncommitted edit counts, which is exactly when a developer
 * is most likely to be running a stale pair.
 */

/** Backend inputs whose content decides what a packaged sidecar contains. */
const SOURCE_PATHS = [path.join('backend', 'src', 'main'), path.join('backend', 'pom.xml')];

const IGNORED_DIRS = new Set(['target', 'node_modules', '.git']);

/** Written next to the jar by the sync script; never committed (binaries/ is ignored). */
export const HASH_FILE_NAME = 'backend-source.sha256';

function appDir(repoRoot) {
	return path.join(repoRoot, 'frontend', 'src-tauri', 'binaries', 'app');
}

async function collectFiles(entry, into) {
	let info;
	try {
		info = await stat(entry);
	} catch {
		return;
	}
	if (info.isFile()) {
		into.push(entry);
		return;
	}
	const dirents = await readdir(entry, { withFileTypes: true });
	for (const dirent of dirents) {
		if (dirent.isDirectory() && IGNORED_DIRS.has(dirent.name)) continue;
		await collectFiles(path.join(entry, dirent.name), into);
	}
}

/**
 * Content digest of every backend source that ends up inside the sidecar.
 *
 * Paths go into the digest next to the bytes, so renaming a file changes it;
 * they are normalised to forward slashes and sorted, so the digest does not
 * depend on the platform or on directory listing order.
 */
export async function hashBackendSources(repoRoot) {
	const files = [];
	for (const relative of SOURCE_PATHS) {
		await collectFiles(path.join(repoRoot, relative), files);
	}
	const relatives = files
		.map((file) => path.relative(repoRoot, file).split(path.sep).join('/'))
		.sort();

	const digest = createHash('sha256');
	for (const relative of relatives) {
		digest.update(relative);
		digest.update('\0');
		digest.update(await readFile(path.join(repoRoot, relative)));
		digest.update('\0');
	}
	return { hash: digest.digest('hex'), fileCount: relatives.length };
}

/** Records the digest of the sources the freshly synced sidecar was built from. */
export async function recordBackendSourceHash(repoRoot) {
	const { hash, fileCount } = await hashBackendSources(repoRoot);
	await writeFile(path.join(appDir(repoRoot), HASH_FILE_NAME), `${hash}\n`, 'utf8');
	return { hash, fileCount };
}

async function findSidecarJar(repoRoot) {
	let dirents;
	try {
		dirents = await readdir(appDir(repoRoot), { withFileTypes: true });
	} catch {
		return null;
	}
	const jar = dirents.find((d) => d.isFile() && d.name.endsWith('.jar'));
	return jar ? path.join(appDir(repoRoot), jar.name) : null;
}

/**
 * @returns {Promise<{status: 'ok'|'missing'|'unknown'|'stale', jar?: string,
 *   expected?: string, recorded?: string}>}
 */
export async function checkSidecarFreshness(repoRoot) {
	const jar = await findSidecarJar(repoRoot);
	if (!jar) return { status: 'missing' };

	let recorded;
	try {
		recorded = (await readFile(path.join(appDir(repoRoot), HASH_FILE_NAME), 'utf8')).trim();
	} catch {
		// A sidecar synced before this check existed. Not evidence of staleness,
		// so it must not fail the run — just say what is missing and how to get it.
		return { status: 'unknown', jar };
	}

	const { hash } = await hashBackendSources(repoRoot);
	if (hash !== recorded) return { status: 'stale', jar, expected: hash, recorded };
	return { status: 'ok', jar, expected: hash, recorded };
}

const REBUILD = [
	'  cd backend; .\\package-sidecar-dev-windows.ps1 -SkipTests   (pwsh 7)',
	'  npm run sidecar:sync:windows                                (from frontend/)'
].join('\n');

/**
 * Human-readable report, plus whether it should stop the run. `unknown` warns
 * without blocking: it means the bookkeeping file is absent, not that the
 * sidecar is wrong.
 */
export function describeStaleness(result) {
	switch (result.status) {
		case 'ok':
			return null;
		case 'missing':
			return {
				fatal: true,
				text: [
					'No packaged sidecar found in frontend/src-tauri/binaries/app.',
					'The app has no backend to start. Build and sync it first:',
					REBUILD
				].join('\n')
			};
		case 'unknown':
			return {
				fatal: false,
				text: [
					`This sidecar predates the freshness check (no ${HASH_FILE_NAME} beside the jar),`,
					'so whether it matches the checked-out backend cannot be told. Re-sync once to',
					'switch the check on:',
					REBUILD
				].join('\n')
			};
		default:
			return {
				fatal: true,
				text: [
					'The packaged sidecar was not built from the backend sources that are checked out.',
					`  sidecar: ${result.jar}`,
					`  built from: ${result.recorded.slice(0, 12)}…`,
					`  checked out: ${result.expected.slice(0, 12)}…`,
					'',
					'vite always serves the current frontend, so this pair disagrees about the API —',
					'which surfaces as data that never arrives, not as an error. Rebuild and sync:',
					REBUILD,
					'',
					'To run the mismatched pair anyway: MAIL_ALLOW_STALE_SIDECAR=1'
				].join('\n')
			};
	}
}
