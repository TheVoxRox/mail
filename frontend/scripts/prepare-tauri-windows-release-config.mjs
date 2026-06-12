import path from 'node:path';
import process from 'node:process';
import {
	buildUpdaterPlugin,
	buildWindowsSigningBundle,
	writeTauriConfig
} from './lib/tauri-config.mjs';

const outputPath = path.resolve(process.argv[2] ?? 'src-tauri/tauri.release.conf.json');

await writeTauriConfig(
	outputPath,
	{
		bundle: {
			createUpdaterArtifacts: true,
			windows: buildWindowsSigningBundle()
		},
		plugins: { updater: buildUpdaterPlugin() }
	},
	'Windows release config'
);
