import { spawn } from 'node:child_process';
import path from 'node:path';
import process from 'node:process';

const rootDir = process.cwd();
const nodeExe = process.execPath;
const runner = path.join(rootDir, 'scripts', 'run-playwright-with-preview.mjs');

function run(args, label) {
	return new Promise((resolve, reject) => {
		const child = spawn(nodeExe, [runner, ...args], {
			cwd: rootDir,
			stdio: 'inherit',
			windowsHide: true
		});

		child.on('error', (error) => reject(new Error(`${label} failed to start: ${error.message}`)));
		child.on('close', (code, signal) => {
			if (signal) {
				reject(new Error(`${label} terminated by signal ${signal}`));
				return;
			}

			if (code === 0) {
				resolve();
				return;
			}

			reject(new Error(`${label} exited with code ${code ?? -1}`));
		});
	});
}

async function main() {
	await run(['--project=functional'], 'functional e2e');
	await run(['src/routes/a11y.e2e.ts'], 'a11y e2e');
}

main().catch((error) => {
	console.error(error.message);
	process.exitCode = 1;
});
