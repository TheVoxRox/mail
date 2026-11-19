import { access, cp, mkdir, readFile, rename, rm } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { wait } from './lib/run.mjs';
import { HASH_FILE_NAME } from './lib/sidecar-staleness.mjs';

const scriptDir = path.dirname(fileURLToPath(import.meta.url));
const defaultSource = path.resolve(
	scriptDir,
	'..',
	'..',
	'backend',
	'target',
	'sidecar',
	'x86_64-pc-windows-msvc'
);
const defaultDestination = path.resolve(scriptDir, '..', 'src-tauri', 'binaries');
const source = path.resolve(process.argv[2] ?? defaultSource);
const destination = path.resolve(process.argv[3] ?? defaultDestination);
const launcher = 'voxrox-mail-backend-x86_64-pc-windows-msvc.exe';
/*
 * The jpackage launcher looks for app/<exe-basename>.cfg. Both names must
 * exist side by side: `tauri:dev` spawns the triple-named exe
 * (voxrox-mail-backend-x86_64-pc-windows-msvc.exe), while the release bundle
 * renames the sidecar to voxrox-mail-backend.exe. The previous
 * rename-instead-of-copy left only the short-named cfg — the dev launcher then
 * failed to find its cfg and hung on an invisible jpackage error dialog
 * (frontend waited for a `.ready` that never came).
 *
 * Packaging writes the triple-named one, so an image without it is broken
 * rather than of another layout, and the sync refuses it. It used to carry on
 * without a word, which put exactly that launcher in place.
 */
const launcherCfg = 'voxrox-mail-backend-x86_64-pc-windows-msvc.cfg';
const bundledCfg = 'voxrox-mail-backend.cfg';

/*
 * Checked before anything in the destination is deleted: an image packaging
 * left incomplete must not cost the one already in place.
 */
for (const item of [launcher, 'app', 'runtime', path.join('app', launcherCfg)]) {
	const itemPath = path.join(source, item);
	try {
		await access(itemPath);
	} catch {
		throw new Error(
			`Backend sidecar artifact is missing: ${itemPath}. ` +
				'Package it first: npm run sidecar:rebuild:windows (from frontend/) packages and syncs in one step.'
		);
	}
}

/*
 * Windows Defender scans a freshly written executable or DLL and holds it
 * open meanwhile, so a delete right after packaging fails now and then with
 * EBUSY or EPERM. package-sidecar-windows.ps1 retries its own deletes for the
 * same reason (Remove-ItemWithRetry), and so does this, a bounded number of
 * times: about two seconds, then the error.
 *
 * Not through rm's own maxRetries. Its retries nest, one round per directory
 * level, and measured on Windows 11 with a running executable three levels
 * below the directory being deleted, maxRetries: 5 and retryDelay: 200 held
 * the delete for 19 s, until the executable exited, where no retries failed
 * at once with EPERM. A lock that does not go away has to fail the sync.
 */
const RETRIES = 5;
const RETRY_DELAY_MS = 200;
const RETRIED = new Set(['EBUSY', 'EPERM', 'EACCES', 'ENOTEMPTY']);

async function retrying(operation) {
	for (let attempt = 1; ; attempt++) {
		try {
			return await operation();
		} catch (error) {
			if (attempt >= RETRIES || !RETRIED.has(error?.code)) throw error;
			await wait(RETRY_DELAY_MS * attempt);
		}
	}
}

const remove = (target) => retrying(() => rm(target, { recursive: true, force: true }));
const renameWithRetry = (from, to) => retrying(() => rename(from, to));

/*
 * app/ goes first and arrives last, whole. It holds the jar and the digest,
 * and they are what `tauri:dev` and `tauri:build` judge the whole image by.
 * The sync used to replace the launcher, app/ and runtime/ in that order, so
 * one that stopped in runtime/ left a new app/, digest included, beside half
 * a runtime. The check called that pair fresh, and the sidecar did not start.
 *
 * Now a sync that stops anywhere leaves no app/, which the check reports as a
 * missing sidecar, fatal and with the rebuild to run. app/ is assembled beside
 * its place and renamed into it, so it is never there in part either: a jar
 * without its digest would read as `unknown`, which only warns.
 */
const app = path.join(destination, 'app');
const incoming = path.join(destination, '.app-incoming');

await mkdir(destination, { recursive: true });
await remove(app);

for (const item of [launcher, 'runtime']) {
	const target = path.join(destination, item);
	await remove(target);
	await cp(path.join(source, item), target, { recursive: true, force: true });
}

await remove(incoming);
await cp(path.join(source, 'app'), incoming, { recursive: true, force: true });
await cp(path.join(incoming, launcherCfg), path.join(incoming, bundledCfg));
await renameWithRetry(incoming, app);

/*
 * What this image was built from came with it: packaging wrote the digest of
 * the backend sources into app/ (see lib/sidecar-staleness.mjs), and the copy
 * of app/ above brought it along, for `tauri:dev` and `tauri:build` to compare
 * with the checked-out backend. Nothing is hashed here. This script copies whatever
 * image backend/target/sidecar holds, which only packaging rebuilds and
 * `mvn clean` leaves alone, so a digest of the working tree taken at sync time
 * described the tree rather than the image — a sync after a backend change
 * stamped the old image as fresh.
 *
 * Carried rather than checked, although the image's digest and the tree could
 * be compared here and the copy refused. A verdict at sync time is the wrong
 * moment: the tree moves on after a sync, so `tauri:dev` and `tauri:build`
 * have to compare when they start regardless, and a second verdict here would need a way past it of
 * its own for the run MAIL_ALLOW_STALE_SIDECAR exists for, an older backend on
 * purpose. Carrying also leaves the copy a function of its source and
 * destination alone: the release workflow passes both, and the digest goes
 * wherever the image goes, where the one taken here always went to this
 * checkout's binaries/.
 */
let packagedFrom = null;
try {
	packagedFrom = (await readFile(path.join(app, HASH_FILE_NAME), 'utf8')).trim();
} catch {
	// An image packaged before packaging recorded the digest; reported below.
}

console.log(`Copied backend sidecar to ${destination}`);
if (packagedFrom) {
	console.log(`Backend sources at packaging: ${packagedFrom.slice(0, 12)}…`);
} else {
	console.warn(
		`The image carries no ${HASH_FILE_NAME}: it was packaged before packaging recorded one, ` +
			'so tauri:dev and tauri:build cannot tell whether it matches the checked-out backend. ' +
			'Package it again to switch the check on.'
	);
}
