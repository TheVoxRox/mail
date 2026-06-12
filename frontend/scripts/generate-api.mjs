import { mkdir, readFile } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import process from 'node:process';
import { runOrThrow } from './lib/run.mjs';

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

async function main() {
	const url = await resolveOpenApiUrl();
	await mkdir(path.dirname(outFile), { recursive: true });
	console.log(`Generating API schema from ${url}`);
	await runOrThrow(process.execPath, [openApiCliPath, url, '-o', outFile], {
		label: 'openapi-typescript',
		cwd: rootDir
	});
	await runOrThrow(process.execPath, [prettierCliPath, '--write', outFile], {
		label: 'prettier',
		cwd: rootDir
	});
}

main().catch((error) => {
	console.error(error instanceof Error ? error.message : String(error));
	process.exitCode = 1;
});
