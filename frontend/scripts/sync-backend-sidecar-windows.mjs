import { access, cp, mkdir, rm } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

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
const requiredItems = ['mail-x86_64-pc-windows-msvc.exe', 'app', 'runtime'];

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
 * (mail-x86_64-pc-windows-msvc.exe), while the release bundle renames the
 * sidecar to mail.exe. The previous rename-instead-of-copy left only mail.cfg
 * — the dev launcher then failed to find its cfg and hung on an invisible
 * jpackage error dialog (frontend waited for a `.ready` that never came).
 */
const cfgSource = path.join(destination, 'app', 'mail-x86_64-pc-windows-msvc.cfg');
const cfgTarget = path.join(destination, 'app', 'mail.cfg');
try {
	await access(cfgSource);
	await rm(cfgTarget, { force: true });
	await cp(cfgSource, cfgTarget);
} catch {
	// cfg missing in this layout — keep whatever is present
}

console.log(`Copied backend sidecar to ${destination}`);
