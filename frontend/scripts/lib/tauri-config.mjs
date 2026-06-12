/**
 * Builders shared by the prepare-tauri-*-config scripts. Each script writes
 * a partial Tauri config (merged by the CLI via --config) from CI env vars;
 * the building blocks live here so the updater and signing shapes cannot
 * drift between the release, updater-only, and signing-only variants.
 */

import { mkdir, writeFile } from 'node:fs/promises';
import path from 'node:path';
import process from 'node:process';

export function requireEnv(name) {
	const value = process.env[name]?.trim();
	if (!value) throw new Error(`${name} is required.`);
	return value;
}

/** TAURI_UPDATER_ENDPOINTS accepts a JSON string array or a newline/comma separated list. */
function parseUpdaterEndpoints(value) {
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

/** plugins.updater block built from TAURI_UPDATER_* env vars. */
export function buildUpdaterPlugin() {
	const pubkey = requireEnv('TAURI_UPDATER_PUBKEY');
	const endpoints = parseUpdaterEndpoints(process.env.TAURI_UPDATER_ENDPOINTS);
	if (endpoints.length === 0) {
		throw new Error('TAURI_UPDATER_ENDPOINTS must contain at least one endpoint URL.');
	}

	const updater = {
		pubkey,
		endpoints,
		windows: {
			installMode: process.env.TAURI_UPDATER_WINDOWS_INSTALL_MODE?.trim() || 'passive'
		}
	};

	if (process.env.TAURI_UPDATER_DANGEROUS_INSECURE_TRANSPORT_PROTOCOL === 'true') {
		updater.dangerousInsecureTransportProtocol = true;
	}

	return updater;
}

/** bundle.windows code-signing block built from WINDOWS_* env vars. */
export function buildWindowsSigningBundle() {
	return {
		certificateThumbprint: requireEnv('WINDOWS_CERTIFICATE_THUMBPRINT'),
		digestAlgorithm: process.env.WINDOWS_DIGEST_ALGORITHM?.trim() || 'sha256',
		timestampUrl: process.env.WINDOWS_TIMESTAMP_URL?.trim() || 'http://timestamp.digicert.com'
	};
}

export async function writeTauriConfig(outputPath, config, label) {
	await mkdir(path.dirname(outputPath), { recursive: true });
	await writeFile(outputPath, `${JSON.stringify(config, null, '\t')}\n`);
	console.log(`Wrote ${label} to ${outputPath}`);
}
