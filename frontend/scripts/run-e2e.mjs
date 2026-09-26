import path from 'node:path';
import process from 'node:process';
import { runOrThrow } from './lib/run.mjs';

const rootDir = process.cwd();
const nodeExe = process.execPath;
const runner = path.join(rootDir, 'scripts', 'run-playwright-with-preview.mjs');

async function main() {
	await runOrThrow(nodeExe, [runner, '--project=functional'], { label: 'functional e2e' });
	await runOrThrow(nodeExe, [runner, 'src/routes/a11y.e2e.ts'], { label: 'a11y e2e' });
}

main().catch((error) => {
	console.error(error.message);
	process.exitCode = 1;
});
