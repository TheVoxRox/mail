import { mkdir, writeFile } from 'node:fs/promises';
import path from 'node:path';

const outputPath = path.resolve(process.argv[2] ?? 'src-tauri/tauri.updater.conf.json');
const pubkey = process.env.TAURI_UPDATER_PUBKEY?.trim();
const endpoints = parseEndpoints(process.env.TAURI_UPDATER_ENDPOINTS);
const installMode = process.env.TAURI_UPDATER_WINDOWS_INSTALL_MODE?.trim() || 'passive';
const dangerousInsecureTransportProtocol =
	process.env.TAURI_UPDATER_DANGEROUS_INSECURE_TRANSPORT_PROTOCOL === 'true';

if (!pubkey) {
	throw new Error('TAURI_UPDATER_PUBKEY is required.');
}

if (endpoints.length === 0) {
	throw new Error('TAURI_UPDATER_ENDPOINTS must contain at least one endpoint URL.');
}

const updater = {
	pubkey,
	endpoints,
	windows: {
		installMode
	}
};

if (dangerousInsecureTransportProtocol) {
	updater.dangerousInsecureTransportProtocol = true;
}

const config = {
	bundle: {
		createUpdaterArtifacts: true
	},
	plugins: {
		updater
	}
};

await mkdir(path.dirname(outputPath), { recursive: true });
await writeFile(outputPath, `${JSON.stringify(config, null, '\t')}\n`);

console.log(`Wrote Tauri updater config to ${outputPath}`);

function parseEndpoints(value) {
	if (!value?.trim()) return [];

	const trimmed = value.trim();
	if (trimmed.startsWith('[')) {
		const parsed = JSON.parse(trimmed);
		if (!Array.isArray(parsed) || parsed.some((item) => typeof item !== 'string')) {
			throw new Error('TAURI_UPDATER_ENDPOINTS JSON must be an array of strings.');
		}
		return parsed.map((item) => item.trim()).filter(Boolean);
	}

	return trimmed
		.split(/\r?\n|,/)
		.map((item) => item.trim())
		.filter(Boolean);
}
