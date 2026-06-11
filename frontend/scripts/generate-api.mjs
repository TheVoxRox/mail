import { spawn } from 'node:child_process';
import { mkdir, readFile } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import process from 'node:process';

const rootDir = process.cwd();
const outFile = path.join(rootDir, 'src', 'lib', 'api', 'schema.d.ts');
const openApiCliPath = path.join(rootDir, 'node_modules', 'openapi-typescript', 'bin', 'cli.js');
const prettierCliPath = path.join(rootDir, 'node_modules', 'prettier', 'bin', 'prettier.cjs');
const sessionPath = path.join(os.homedir(), '.voxrox', 'mail', 'session.json');
const snapshotPath = path.resolve(
	rootDir,
	'..',
	'backend',
	'src',
	'test',
	'resources',
	'openapi',
	'api-docs.json'
);

function openApiUrlFromSession(session) {
	const baseUrl = new URL(session.baseUrl);
	return `${baseUrl.origin}/v3/api-docs`;
}

async function resolveOpenApiUrl() {
	if (process.argv.includes('--snapshot')) {
		return snapshotPath;
	}

	if (process.env.OPENAPI_SOURCE) {
		return process.env.OPENAPI_SOURCE;
	}

	if (process.env.OPENAPI_URL) {
		return process.env.OPENAPI_URL;
	}

	const raw = await readFile(sessionPath, 'utf8');
	const session = JSON.parse(raw);
	if (!session?.baseUrl) {
		throw new Error(`session.json at ${sessionPath} does not contain baseUrl`);
	}

	return openApiUrlFromSession(session);
}

function runCommand(args, label) {
	return new Promise((resolve, reject) => {
		const child = spawn(process.execPath, args, {
			cwd: rootDir,
			stdio: 'inherit',
			windowsHide: true
		});

		child.on('error', (error) => {
			reject(new Error(`${label} failed to start: ${error.message}`));
		});
		child.on('exit', (code, signal) => {
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
	const url = await resolveOpenApiUrl();
	await mkdir(path.dirname(outFile), { recursive: true });
	console.log(`Generating API schema from ${url}`);
	await runCommand([openApiCliPath, url, '-o', outFile], 'openapi-typescript');
	await runCommand([prettierCliPath, '--write', outFile], 'prettier');
}

main().catch((error) => {
	console.error(error instanceof Error ? error.message : String(error));
	process.exitCode = 1;
});
