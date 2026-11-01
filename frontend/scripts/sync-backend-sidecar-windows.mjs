import { access, cp, mkdir, readFile, rm } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
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
const requiredItems = ['voxrox-mail-backend-x86_64-pc-windows-msvc.exe', 'app', 'runtime'];

for (const item of requiredItems) {
	const itemPath = path.join(source, item);
	try {
		await access(itemPath);
	} catch {
		throw new Error(
			`Backend sidecar artifact is missing: ${itemPath}. Run scripts/package-sidecar-windows.ps1 in backend first.`
		);
	}
}

await mkdir(destination, { recursive: true });

for (const item of requiredItems) {
	const target = path.join(destination, item);
	await rm(target, { recursive: true, force: true });
	await cp(path.join(source, item), target, { recursive: true, force: true });
}

/*
 * The jpackage launcher looks for app/<exe-basename>.cfg. Both names must
 * exist side by side: `tauri:dev` spawns the triple-named exe
 * (voxrox-mail-backend-x86_64-pc-windows-msvc.exe), while the release bundle
 * renames the sidecar to voxrox-mail-backend.exe. The previous
 * rename-instead-of-copy left only the short-named cfg — the dev launcher then
 * failed to find its cfg and hung on an invisible jpackage error dialog
 * (frontend waited for a `.ready` that never came).
 */
const cfgSource = path.join(destination, 'app', 'voxrox-mail-backend-x86_64-pc-windows-msvc.cfg');
const cfgTarget = path.join(destination, 'app', 'voxrox-mail-backend.cfg');
try {
	await access(cfgSource);
	await rm(cfgTarget, { force: true });
	await cp(cfgSource, cfgTarget);
} catch {
	// cfg missing in this layout — keep whatever is present
}

/*
 * What this image was built from came with it: packaging wrote the digest of
 * the backend sources into app/ (see lib/sidecar-staleness.mjs), and the copy
 * of app/ above brought it along, for `tauri:dev` to compare with the
 * checked-out backend. Nothing is hashed here. This script copies whatever
 * image backend/target/sidecar holds, which only packaging rebuilds and
 * `mvn clean` leaves alone, so a digest of the working tree taken at sync time
 * described the tree rather than the image — a sync after a backend change
 * stamped the old image as fresh.
 *
 * Carried rather than checked, although the image's digest and the tree could
 * be compared here and the copy refused. A verdict at sync time is the wrong
 * moment: the tree moves on after a sync, so `tauri:dev` has to compare at
 * launch regardless, and a second verdict here would need a way past it of
 * its own for the run MAIL_ALLOW_STALE_SIDECAR exists for, an older backend on
 * purpose. Carrying also leaves the copy a function of its source and
 * destination alone: the release workflow passes both, and the digest goes
 * wherever the image goes, where the one taken here always went to this
 * checkout's binaries/.
 */
let packagedFrom = null;
try {
	packagedFrom = (await readFile(path.join(destination, 'app', HASH_FILE_NAME), 'utf8')).trim();
} catch {
	// An image packaged before packaging recorded the digest; reported below.
}

console.log(`Copied backend sidecar to ${destination}`);
if (packagedFrom) {
	console.log(`Backend sources at packaging: ${packagedFrom.slice(0, 12)}…`);
} else {
	console.warn(
		`The image carries no ${HASH_FILE_NAME}: it was packaged before packaging recorded one, ` +
			'so tauri:dev cannot tell whether it matches the checked-out backend. Package it again ' +
			'to switch the check on.'
	);
}
