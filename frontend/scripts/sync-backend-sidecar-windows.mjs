import { access, cp, mkdir, rename, rm } from 'node:fs/promises';
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

const cfgSource = path.join(destination, 'app', 'mail-x86_64-pc-windows-msvc.cfg');
const cfgTarget = path.join(destination, 'app', 'mail.cfg');
try {
	await access(cfgSource);
	await rm(cfgTarget, { force: true });
	await rename(cfgSource, cfgTarget);
} catch {
	// cfg already renamed or missing
}

console.log(`Copied backend sidecar to ${destination}`);
