import { spawn } from 'node:child_process';
import { mkdtemp, readFile, rm } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import process from 'node:process';

const rootDir = process.cwd();
const schemaFile = path.join(rootDir, 'src', 'lib', 'api', 'schema.d.ts');
const snapshotFile = path.resolve(
	rootDir,
	'..',
	'backend',
	'src',
	'test',
	'resources',
	'openapi',
	'api-docs.json'
);
const openApiCliPath = path.join(rootDir, 'node_modules', 'openapi-typescript', 'bin', 'cli.js');
const prettierCliPath = path.join(rootDir, 'node_modules', 'prettier', 'bin', 'prettier.cjs');
const prettierConfigPath = path.join(rootDir, '.prettierrc');

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

function normalizeNewlines(value) {
	return value.replace(/\r\n/g, '\n');
}

async function main() {
	const tempDir = await mkdtemp(path.join(os.tmpdir(), 'voxrox-openapi-'));
	const generatedFile = path.join(tempDir, 'schema.d.ts');

	try {
		await runCommand([openApiCliPath, snapshotFile, '-o', generatedFile], 'openapi-typescript');
		await runCommand(
			[prettierCliPath, '--config', prettierConfigPath, '--write', generatedFile],
			'prettier'
		);

		const [expected, actual] = await Promise.all([
			readFile(generatedFile, 'utf8'),
			readFile(schemaFile, 'utf8')
		]);
		if (normalizeNewlines(expected) !== normalizeNewlines(actual)) {
			throw new Error(
				[
					'Frontend API schema is out of date with backend/src/test/resources/openapi/api-docs.json.',
					'Run `npm run generate:api:snapshot` from frontend/ and commit src/lib/api/schema.d.ts.'
				].join('\n')
			);
		}
	} finally {
		await rm(tempDir, { recursive: true, force: true });
	}
}

main().catch((error) => {
	console.error(error instanceof Error ? error.message : String(error));
	process.exitCode = 1;
});
