import path from 'node:path';
import process from 'node:process';
import { fileURLToPath } from 'node:url';
import { run } from './lib/run.mjs';
import { parseRebuildArgs, rebuildSidecar, runningFromCheckout } from './lib/sidecar-rebuild.mjs';

/*
 * npm run sidecar:rebuild:windows [-- --skip-smoke] [-- --allow-placeholder-oauth]
 *
 * Packages the backend sidecar from the checked-out sources, copies it into
 * src-tauri/binaries and checks what landed there; see lib/sidecar-rebuild.mjs.
 * --skip-smoke leaves out the headless start of the packaged sidecar (about
 * 15 s). --allow-placeholder-oauth packages without the OAuth client ids from
 * backend/.env, so the sidecar's OAuth login will not work.
 */

const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');
const log = (line) => console.log(`[sidecar:rebuild] ${line}`);

try {
	if (process.platform !== 'win32') {
		throw new Error('The sidecar is a Windows jpackage image; this runs on Windows only.');
	}
	await rebuildSidecar(repoRoot, parseRebuildArgs(process.argv.slice(2)), {
		runCommand: (step) => run(step.command, step.args, { label: step.title, cwd: step.cwd }),
		findRunning: runningFromCheckout,
		log
	});
} catch (error) {
	console.error(`[sidecar:rebuild] ${error instanceof Error ? error.message : String(error)}`);
	process.exitCode = 1;
}
