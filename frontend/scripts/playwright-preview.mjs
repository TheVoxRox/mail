import { spawn } from 'node:child_process';
import path from 'node:path';
import process from 'node:process';
import { terminateProcessTreeSync } from './lib/process-tree.mjs';
import { runOrThrow } from './lib/run.mjs';

const rootDir = process.cwd();
const nodeExe = process.execPath;
const viteCli = path.join(rootDir, 'node_modules', 'vite', 'bin', 'vite.js');
const modeArg = process.argv.find((arg) => arg.startsWith('--mode='));
const mode = modeArg?.slice('--mode='.length);

async function main() {
	await runOrThrow(nodeExe, mode ? [viteCli, 'build', '--mode', mode] : [viteCli, 'build'], {
		label: 'vite build'
	});

	const preview = spawn(
		nodeExe,
		[viteCli, 'preview', '--host', '127.0.0.1', '--port', '4173', '--strictPort'],
		{
			cwd: rootDir,
			stdio: 'inherit',
			windowsHide: true
		}
	);

	const shutdown = () => terminateProcessTreeSync(preview);
	process.on('SIGINT', shutdown);
	process.on('SIGTERM', shutdown);

	preview.on('error', (error) => {
		console.error(`vite preview failed to start: ${error.message}`);
		process.exitCode = 1;
	});

	preview.on('exit', (code, signal) => {
		if (signal) {
			process.exitCode = 1;
			return;
		}

		process.exitCode = code ?? 0;
	});
}

main().catch((error) => {
	console.error(error.message);
	process.exitCode = 1;
});
