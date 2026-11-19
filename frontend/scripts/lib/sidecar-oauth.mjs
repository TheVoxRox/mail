import { readdir, readFile } from 'node:fs/promises';
import path from 'node:path';

/**
 * Does the launcher in src-tauri/binaries carry the OAuth client ids?
 *
 * Packaging bakes them into the launcher's .cfg as JVM system properties
 * (backend/scripts/package-sidecar-windows.ps1, `$oauthMappings`), and fails
 * when one is missing — unless `-AllowPlaceholderOAuth` was passed, which
 * `npm run sidecar:rebuild:windows -- --allow-placeholder-oauth` does. Such a
 * sidecar falls back to the `mail-local-*` placeholders of
 * application.properties, which Google and Microsoft reject as an unknown
 * client. Packaging said so once, as a warning, and nothing after it did: an
 * installer built over that sidecar days later could not sign in to a Google
 * or Microsoft account, and nothing on the way had said a word.
 *
 * Only `npm run tauri:build` asks. A dev run passes backend/.env to the
 * sidecar (lib/dotenv.mjs), and application.properties reads the same
 * properties from GOOGLE_OAUTH_CLIENT_ID and the rest, so a dev run over this
 * sidecar signs in like any other. An installed sidecar has nothing but its
 * launcher.
 */

/*
 * The names below say "client" rather than "OAuth" on purpose. CodeQL's
 * clear-text logging query takes any identifier whose name suggests
 * authentication data for a secret, and flagged the warning tauri-build.mjs
 * prints (#968), though what reaches the log is property names, never a value:
 * the values stay in the .cfg and nothing here returns one.
 */

/** The properties `$oauthMappings` maps, in its order. Pinned to it by tauri-build.test.mjs. */
export const LAUNCHER_CLIENT_PROPERTIES = [
	'spring.security.oauth2.client.registration.google.client-id',
	'spring.security.oauth2.client.registration.google.client-secret',
	'spring.security.oauth2.client.registration.microsoft.client-id'
];

const PLACEHOLDER = 'mail-local-';

/** `java-options=-D<property>=<value>`, one per line, as jpackage writes them. */
function bakes(line, property) {
	const prefix = `java-options=-D${property}=`;
	if (!line.startsWith(prefix)) return false;
	const value = line.slice(prefix.length).trim();
	return value !== '' && !value.startsWith(PLACEHOLDER);
}

/**
 * The properties of LAUNCHER_CLIENT_PROPERTIES that some launcher .cfg in
 * binaries/app does not carry with a real value; empty when every one does.
 * Null when there is no .cfg to read, which the sync refuses and the freshness
 * check reports on its own terms.
 */
export async function clientPropertiesNotBaked(repoRoot) {
	const app = path.join(repoRoot, 'frontend', 'src-tauri', 'binaries', 'app');
	let names;
	try {
		names = (await readdir(app)).filter((name) => name.endsWith('.cfg'));
	} catch {
		return null;
	}
	if (names.length === 0) return null;
	const cfgs = await Promise.all(
		names.map(async (name) => (await readFile(path.join(app, name), 'utf8')).split(/\r?\n/))
	);
	return LAUNCHER_CLIENT_PROPERTIES.filter(
		(property) => !cfgs.every((lines) => lines.some((line) => bakes(line, property)))
	);
}

/**
 * The warning `npm run tauri:build` prints before it builds, for what
 * clientPropertiesNotBaked found missing. Not a stop: a local installer is built
 * to test the installer, the updater or the shell, which need no sign-in, and
 * what reaches users is packaged by windows-signed-release.yml, which never
 * passes the opt-out and so cannot produce such a sidecar.
 */
export function describeClientPropertiesNotBaked(missing) {
	const names = missing.map((property) =>
		property.replace('spring.security.oauth2.client.registration.', '')
	);
	return [
		`The sidecar launcher carries no OAuth client value for ${names.join(', ')}.`,
		'It was packaged without them (--allow-placeholder-oauth), so an installer built over it',
		'cannot sign in to a Google or Microsoft account: both reject the placeholder as an',
		'unknown client. A dev run is not affected; it takes the values from backend/.env.',
		'To bake them in, define them in backend/.env and rebuild:',
		'  npm run sidecar:rebuild:windows   (from frontend/)'
	].join('\n');
}
