import { spawn } from 'node:child_process';
import http from 'node:http';
import path from 'node:path';
import process from 'node:process';
import { terminateProcessTreeSync } from './lib/process-tree.mjs';
import { run, wait } from './lib/run.mjs';

const rootDir = process.cwd();
const nodeExe = process.execPath;
const previewScript = path.join(rootDir, 'scripts', 'playwright-preview.mjs');
const playwrightCli = path.join(rootDir, 'node_modules', '@playwright', 'test', 'cli.js');
const previewUrl = 'http://127.0.0.1:4173/';
const extraArgs = process.argv.slice(2);
const needsE2eMock = extraArgs.some(
	(arg) =>
		arg.includes('functional') ||
		arg.includes('a11y.e2e') ||
		arg === '--project=functional' ||
		arg === '--project=a11y'
);

async function waitForServer(url, timeoutMs = 120000) {
	const deadline = Date.now() + timeoutMs;

	while (Date.now() < deadline) {
		try {
			await new Promise((resolve, reject) => {
				const req = http.get(url, (res) => {
					res.resume();
					if ((res.statusCode ?? 500) < 500) {
						resolve();
						return;
					}

					reject(new Error(`Unexpected status ${res.statusCode}`));
				});

				req.on('error', reject);
				req.setTimeout(3000, () => {
					req.destroy(new Error('Request timeout'));
				});
			});

			return;
		} catch {
			await wait(500);
		}
	}

	throw new Error(`Preview server did not become ready at ${url} within ${timeoutMs} ms.`);
}

async function main() {
	const preview = spawn(nodeExe, needsE2eMock ? [previewScript, '--mode=e2e'] : [previewScript], {
		cwd: rootDir,
		stdio: 'inherit',
		windowsHide: true
	});

	const cleanup = () => terminateProcessTreeSync(preview);
	process.on('SIGINT', cleanup);
	process.on('SIGTERM', cleanup);

	preview.on('error', (error) => {
		console.error(`Failed to start preview runner: ${error.message}`);
	});

	let exitCode;

	try {
		await waitForServer(previewUrl);

		exitCode = await run(
			nodeExe,
			[playwrightCli, 'test', '--config=playwright.existing-server.config.ts', ...extraArgs],
			{ label: 'playwright' }
		);
	} finally {
		cleanup();
	}

	process.exit(exitCode ?? 1);
}

main().catch((error) => {
	console.error(error.message);
	process.exitCode = 1;
});
