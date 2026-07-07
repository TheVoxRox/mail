import path from 'node:path';
import process from 'node:process';
import {
	buildUpdaterPlugin,
	buildWindowsSigningBundle,
	writeTauriConfig
} from './lib/tauri-config.mjs';

const outputPath = path.resolve(process.argv[2] ?? 'src-tauri/tauri.release.conf.json');

// This config is the SOURCE OF TRUTH for what a release actually ships: the
// updater endpoint + pubkey come from the CI TAURI_UPDATER_* env (buildUpdaterPlugin),
// NOT from the base tauri.conf.json, whose updater block is only a dev reference.
// The signed workflow always builds with `--config tauri.release.conf.json`; a bare
// `npm run tauri:build` would ship the base-config values and, since
// createUpdaterArtifacts is unset there, produce no `.sig` — such a build must never
// be published. See RELEASE_CHECKLIST §3a.
const bundle = { createUpdaterArtifacts: true };

// Authenticode signing is OPTIONAL: this is an open-source build with no paid
// code-signing certificate. When WINDOWS_CERTIFICATE_THUMBPRINT is set the
// installer is Authenticode-signed; otherwise the bundle ships unsigned and
// authenticity rests on the build-provenance attestation + updater signature.
// (The updater plugin below stays required — auto-update always needs it.)
if (process.env.WINDOWS_CERTIFICATE_THUMBPRINT?.trim()) {
	bundle.windows = buildWindowsSigningBundle();
} else {
	console.warn(
		'WINDOWS_CERTIFICATE_THUMBPRINT not set — building an unsigned (no Authenticode) bundle.'
	);
}

await writeTauriConfig(
	outputPath,
	{ bundle, plugins: { updater: buildUpdaterPlugin() } },
	'Windows release config'
);
