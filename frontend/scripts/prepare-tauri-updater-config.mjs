import path from 'node:path';
import process from 'node:process';
import { buildUpdaterPlugin, writeTauriConfig } from './lib/tauri-config.mjs';

const outputPath = path.resolve(process.argv[2] ?? 'src-tauri/tauri.updater.conf.json');

await writeTauriConfig(
	outputPath,
	{
		bundle: { createUpdaterArtifacts: true },
		plugins: { updater: buildUpdaterPlugin() }
	},
	'Tauri updater config'
);
