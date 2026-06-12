import path from 'node:path';
import process from 'node:process';
import { buildWindowsSigningBundle, writeTauriConfig } from './lib/tauri-config.mjs';

const outputPath = path.resolve(process.argv[2] ?? 'src-tauri/tauri.signing.conf.json');

await writeTauriConfig(
	outputPath,
	{ bundle: { windows: buildWindowsSigningBundle() } },
	'Windows signing config'
);
