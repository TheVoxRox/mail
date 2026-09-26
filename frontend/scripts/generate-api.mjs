import { mkdir } from 'node:fs/promises';
import path from 'node:path';
import process from 'node:process';
import { runOrThrow } from './lib/run.mjs';

const rootDir = process.cwd();
const outFile = path.join(rootDir, 'src', 'lib', 'api', 'schema.d.ts');
const openApiCliPath = path.join(rootDir, 'node_modules', 'openapi-typescript', 'bin', 'cli.js');
const prettierCliPath = path.join(rootDir, 'node_modules', 'prettier', 'bin', 'prettier.cjs');
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

async function main() {
	await mkdir(path.dirname(outFile), { recursive: true });
	console.log(`Generating API schema from ${snapshotPath}`);
	await runOrThrow(process.execPath, [openApiCliPath, snapshotPath, '-o', outFile], {
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
