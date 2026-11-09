import { createHash } from 'node:crypto';
import { readdir, readFile, stat } from 'node:fs/promises';
import path from 'node:path';
import process from 'node:process';

/**
 * Does the packaged sidecar carry the backend sources that are checked out now?
 *
 * `npm run tauri:dev` serves the frontend from vite, which always reflects the
 * working tree, but it launches whatever binary happens to sit in
 * `src-tauri/binaries/`. Nothing rebuilds that and nothing used to say a word
 * about it, so the two halves drift apart silently and the app breaks far from
 * the cause: a response missing a field the frontend now requires reads as "the
 * list is stuck loading", not as "your backend is old" (#252, #253).
 * `npm run tauri:build` has the same blind spot with more at stake: it builds
 * the frontend from the working tree and bundles whatever sidecar sits in
 * binaries/ into an installer. Both ask through requireUsableSidecar below.
 *
 * The comparison is over **content**, not modification times. A checkout, a
 * pull or a branch switch rewrites mtimes without changing a byte, so an
 * mtime check calls a perfectly good sidecar stale every time — and a check
 * that cries wolf gets silenced. Same reasoning that moved `check:audits` from
 * commit SHAs to git object ids. Hashing the working tree rather than asking
 * git also means an uncommitted edit counts, which is exactly when a developer
 * is most likely to be running a stale pair.
 *
 * The digest is taken where the image is built, not where it is copied.
 * backend/scripts/package-sidecar-windows.ps1 takes it before Maven reads a
 * source and writes it into the image beside the jar; the sync carries it into
 * binaries/ with the rest of app/. The sync used to take it itself, from the
 * tree as it stood at sync time — but what it copies is whatever image
 * backend/target/sidecar holds, which only packaging rebuilds and `mvn clean`
 * leaves alone, so a sync after a backend change stamped the old image with the
 * new digest and this check then called the pair fresh.
 *
 * The digest cannot see a renamed launcher, though. Renaming the sidecar (#495:
 * `mail` → `voxrox-mail-backend`) changes the packaging script and
 * `externalBin` in tauri.conf.json, not a byte under `backend/src/main`, so a
 * sidecar synced before the rename still hashes as fresh — while tauri-build
 * fails on the launcher it cannot find, with a message that names a resource
 * path rather than a stale sidecar. So the launcher's name is checked against
 * `externalBin` first.
 */

/** Backend inputs whose content decides what a packaged sidecar contains. */
const SOURCE_PATHS = [path.join('backend', 'src', 'main'), path.join('backend', 'pom.xml')];

const IGNORED_DIRS = new Set(['target', 'node_modules', '.git']);

/**
 * Written beside the jar by backend/scripts/package-sidecar-windows.ps1, which
 * spells the name out too, and carried into binaries/app by the sync; never
 * committed (binaries/ is ignored). Renamed from backend-source.sha256, the
 * name the sync wrote its own digest under: a digest taken at sync time says
 * nothing about the image, so a binaries/ last synced that way has to read as
 * `unknown` rather than keep a stamp that may certify an old image.
 */
export const HASH_FILE_NAME = 'packaged-backend-source.sha256';

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

function binariesDir(repoRoot) {
	return path.join(repoRoot, 'frontend', 'src-tauri', 'binaries');
}

/**
 * The launcher names tauri.conf.json expects, e.g. `voxrox-mail-backend`. Tauri
 * looks for each one as `<name>-<target triple>` (plus `.exe` on Windows).
 * Returns null when the config cannot be read, so the name check stays out of
 * the way rather than guessing.
 */
async function expectedLauncherNames(repoRoot) {
	try {
		const config = JSON.parse(
			await readFile(path.join(repoRoot, 'frontend', 'src-tauri', 'tauri.conf.json'), 'utf8')
		);
		const externalBin = config?.bundle?.externalBin;
		if (!Array.isArray(externalBin)) return null;
		return externalBin.map((entry) => path.posix.basename(entry));
	} catch {
		return null;
	}
}

/**
 * `<name>-<target triple>`, with the `.exe` Windows adds. A triple is
 * arch-vendor-sys or arch-vendor-sys-abi; matching its shape rather than just
 * the `<name>-` prefix is what keeps `voxrox-mail` from being satisfied by
 * `voxrox-mail-backend-x86_64-pc-windows-msvc.exe` — and those two names are
 * the shell and the sidecar of this very repo, one a prefix of the other.
 */
const TARGET_TRIPLE = /^[a-z0-9_]+(?:-[a-z0-9_]+){2,3}(?:\.exe)?$/;

function isLauncherFor(file, name) {
	if (!file.startsWith(`${name}-`)) return false;
	return TARGET_TRIPLE.test(file.slice(name.length + 1));
}

/** First launcher `externalBin` names that `binaries/` does not hold, with what it does hold. */
async function findMisnamedLauncher(repoRoot) {
	const expected = await expectedLauncherNames(repoRoot);
	if (!expected) return null;
	const files = (await readdir(binariesDir(repoRoot), { withFileTypes: true }))
		.filter((d) => d.isFile() && !d.name.startsWith('.'))
		.map((d) => d.name);
	const missing = expected.find((name) => !files.some((file) => isLauncherFor(file, name)));
	return missing ? { expectedLauncher: missing, foundLaunchers: files } : null;
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
 * @returns {Promise<{status: 'ok'|'missing'|'misnamed'|'unknown'|'stale', jar?: string,
 *   expected?: string, recorded?: string, expectedLauncher?: string,
 *   foundLaunchers?: string[]}>}
 */
export async function checkSidecarFreshness(repoRoot) {
	const jar = await findSidecarJar(repoRoot);
	if (!jar) return { status: 'missing' };

	const misnamed = await findMisnamedLauncher(repoRoot);
	if (misnamed) return { status: 'misnamed', jar, ...misnamed };

	let recorded;
	try {
		recorded = (await readFile(path.join(appDir(repoRoot), HASH_FILE_NAME), 'utf8')).trim();
	} catch {
		// An image packaged before packaging recorded a digest. Not evidence of
		// staleness, so it must not fail the run — just say what is missing and
		// how to get it.
		return { status: 'unknown', jar };
	}

	const { hash } = await hashBackendSources(repoRoot);
	if (hash !== recorded) return { status: 'stale', jar, expected: hash, recorded };
	return { status: 'ok', jar, expected: hash, recorded };
}

/*
 * One command: packaging and the sync as two steps were what let an old image
 * through, the second taken without the first (see scripts/lib/sidecar-rebuild.mjs).
 */
const REBUILD = '  npm run sidecar:rebuild:windows   (from frontend/)';

/**
 * Human-readable report, plus whether it should stop the run. `unknown` warns
 * without blocking: it means the bookkeeping file is absent, not that the
 * sidecar is wrong. The text serves `tauri:dev` and `tauri:build` alike, so it
 * says nothing only one of them would be true of.
 *
 * `optOut` says whether MAIL_ALLOW_STALE_SIDECAR may wave this result through,
 * and it lives here because this is where the message that advertises the
 * variable is written. Only a digest mismatch is a tradeoff a developer can
 * take: the pair disagrees about the API, which is a run worth having anyway.
 * A missing or misnamed launcher is not a tradeoff at all — tauri-build cannot
 * find the resource and fails, with the message this check exists to replace.
 */
export function describeStaleness(result) {
	switch (result.status) {
		case 'ok':
			return null;
		case 'missing':
			return {
				fatal: true,
				optOut: false,
				text: [
					'No packaged sidecar found in frontend/src-tauri/binaries/app.',
					'The app has no backend to start. Build it first:',
					REBUILD
				].join('\n')
			};
		case 'misnamed':
			return {
				fatal: true,
				optOut: false,
				text: [
					`tauri.conf.json expects the sidecar launcher ${result.expectedLauncher}-<target triple>,`,
					'and frontend/src-tauri/binaries does not hold one.',
					`  found: ${result.foundLaunchers.length > 0 ? result.foundLaunchers.join(', ') : 'no launcher at all'}`,
					'',
					'The launcher was renamed after this sidecar was packaged — the backend sources can',
					'still match, so the content check alone would call it fresh. Rebuild it:',
					REBUILD
				].join('\n')
			};
		case 'unknown':
			return {
				fatal: false,
				optOut: false,
				text: [
					'This sidecar was packaged before packaging recorded which backend sources went',
					`into it (no ${HASH_FILE_NAME} beside the jar), so whether it matches the`,
					'checked-out backend cannot be told. Syncing again does not change that; rebuild',
					'it once to switch the check on:',
					REBUILD
				].join('\n')
			};
		default:
			return {
				fatal: true,
				optOut: true,
				text: [
					'The packaged sidecar was not built from the backend sources that are checked out.',
					`  sidecar: ${result.jar}`,
					`  built from: ${result.recorded.slice(0, 12)}…`,
					`  checked out: ${result.expected.slice(0, 12)}…`,
					'',
					'The frontend always comes from the working tree, so this pair disagrees about the',
					'API — which surfaces as data that never arrives, not as an error. Rebuild it:',
					REBUILD,
					'',
					'To run the mismatched pair anyway: MAIL_ALLOW_STALE_SIDECAR=1'
				].join('\n')
			};
	}
}

/**
 * The check `npm run tauri:dev` and `npm run tauri:build` both make before
 * they start anything: one policy, called by both, so that what a dev run
 * accepts and what an installer is built around cannot drift apart. It
 * reports under `[label]`, and throws when the run has to stop, so the caller
 * stops before it has spent anything.
 *
 * Fails closed, with an opt-out, like the packaging script does for a
 * placeholder OAuth client: a run against a stale backend does not look
 * broken, it looks slow or empty, and the cost of finding that out the hard
 * way is an afternoon.
 *
 * The opt-out is per result, not per run: describeStaleness decides which of
 * its verdicts MAIL_ALLOW_STALE_SIDECAR may wave through, next to the message
 * that offers it. Skipping the whole check meant the variable also switched
 * off the launcher-name check, which advertises no opt-out because there is
 * nothing to opt into — tauri-build simply cannot find the resource.
 *
 * Taking the opt-out prints the whole report as a warning. A variable set
 * once in a shell outlives the reason it was set, and the report is what says
 * which pair is about to run, or to be built into an installer.
 *
 * @param {string} repoRoot
 * @param {string} label prefix of every line it prints, e.g. `tauri-dev`
 * @returns {Promise<'ok'|'unknown'|'stale'>} what it let through; `stale`
 *   only when the opt-out waved it through
 */
export async function requireUsableSidecar(repoRoot, label) {
	const result = await checkSidecarFreshness(repoRoot);
	const report = describeStaleness(result);
	if (!report) {
		// Said, not left silent: a log without this line cannot tell a check
		// that passed from one that never ran.
		console.log(
			`[${label}] The sidecar was packaged from the checked-out backend (${result.expected.slice(0, 12)}…).`
		);
		return result.status;
	}
	if (report.optOut && process.env.MAIL_ALLOW_STALE_SIDECAR === '1') {
		console.warn(`\n[${label}] ${report.text}\n`);
		console.warn(
			`[${label}] WARNING: MAIL_ALLOW_STALE_SIDECAR=1 is set — going ahead with the mismatched pair anyway.\n`
		);
		return result.status;
	}
	if (!report.fatal) {
		console.warn(`\n[${label}] ${report.text}\n`);
		return result.status;
	}
	console.error(`\n[${label}] ${report.text}\n`);
	throw new Error('Sidecar does not match the checked-out backend (see above).');
}
