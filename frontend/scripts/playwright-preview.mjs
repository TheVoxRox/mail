import { spawn, spawnSync } from 'node:child_process';
import path from 'node:path';
import process from 'node:process';

const rootDir = process.cwd();
const nodeExe = process.execPath;
const viteCli = path.join(rootDir, 'node_modules', 'vite', 'bin', 'vite.js');
const modeArg = process.argv.find((arg) => arg.startsWith('--mode='));
const mode = modeArg?.slice('--mode='.length);

function runStep(args, label) {
	return new Promise((resolve, reject) => {
		const child = spawn(nodeExe, [viteCli, ...args], {
			cwd: rootDir,
			stdio: 'inherit',
			windowsHide: true
		});

		child.on('error', (error) => {
			reject(new Error(`${label} failed to start: ${error.message}`));
		});

		child.on('exit', (code, signal) => {
			if (code === 0) {
				resolve();
				return;
			}

			if (signal) {
				reject(new Error(`${label} terminated by signal ${signal}`));
				return;
			}

			reject(new Error(`${label} exited with code ${code ?? -1}`));
		});
	});
}

async function main() {
	await runStep(mode ? ['build', '--mode', mode] : ['build'], 'vite build');

	const preview = spawn(
		nodeExe,
		[viteCli, 'preview', '--host', '127.0.0.1', '--port', '4173', '--strictPort'],
		{
			cwd: rootDir,
			stdio: 'inherit',
			windowsHide: true
		}
	);

	const shutdown = () => {
		if (!preview.pid || preview.killed) return;

		if (process.platform === 'win32') {
			spawnSync('taskkill', ['/PID', String(preview.pid), '/T', '/F'], {
				stdio: 'ignore',
				windowsHide: true
			});
			return;
		}

		preview.kill('SIGTERM');
	};

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
