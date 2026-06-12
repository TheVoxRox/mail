/**
 * Loads backend/.env for scripts that hand the backend env to the Tauri
 * sidecar (tauri-dev-with-env, tauri-release-startup-smoke). Deliberately
 * minimal — supports NAME=value lines, comments, and single/double quoted
 * values; no interpolation.
 */

import { readFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const libDir = path.dirname(fileURLToPath(import.meta.url));

/** Absolute path to backend/.env, independent of the caller's cwd. */
export const BACKEND_ENV_PATH = path.resolve(libDir, '..', '..', '..', 'backend', '.env');

/*
 * Desktop crypto bootstraps its key material from the app data dir, so the
 * backend .env crypto values must not leak into the sidecar env unless the
 * caller explicitly opts in (--include-backend-env-crypto).
 */
const DESKTOP_FILTERED_ENV_NAMES = new Set(['MAIL_CRYPTO_KEY', 'MAIL_CRYPTO_SALT']);

export function parseDotEnv(raw) {
	const values = {};

	for (const rawLine of raw.split(/\r?\n/)) {
		const line = rawLine.trim();
		if (!line || line.startsWith('#')) continue;

		const match = line.match(/^([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)$/);
		if (!match) continue;

		const [, name, rawValue] = match;
		let value = rawValue.trim();
		if (
			(value.startsWith('"') && value.endsWith('"')) ||
			(value.startsWith("'") && value.endsWith("'"))
		) {
			value = value.slice(1, -1);
		}
		values[name] = value;
	}

	return values;
}

export async function loadBackendEnv(failureHint) {
	try {
		const raw = await readFile(BACKEND_ENV_PATH, 'utf8');
		return parseDotEnv(raw);
	} catch (error) {
		throw new Error(`Failed to read backend/.env from ${BACKEND_ENV_PATH}. ${failureHint}`, {
			cause: error
		});
	}
}

export function envForDesktopSidecar(values, includeBackendEnvCrypto) {
	if (includeBackendEnvCrypto) {
		return values;
	}

	return Object.fromEntries(
		Object.entries(values).filter(([name]) => !DESKTOP_FILTERED_ENV_NAMES.has(name))
	);
}
