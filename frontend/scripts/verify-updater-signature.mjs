/**
 * Release gate: proves that the updater signature shipped in `latest.json`
 * verifies against the public key shipped in the app's own config.
 *
 * This is the one release property that cannot be repaired afterwards. The
 * updater key pair lives in two unrelated CI secrets — TAURI_SIGNING_PRIVATE_KEY
 * produces the `.sig`, TAURI_UPDATER_PUBKEY is baked into the app — and until
 * this gate existed nothing compared them. A rotated or mistyped half let the
 * whole workflow finish green, and every installation that took the release
 * lost its updater for good: the app can only be repaired by an update it now
 * refuses to accept.
 *
 * Both inputs are read from the generated artifacts rather than from the
 * environment, so the gate judges what actually ships: the pubkey comes out of
 * the release config the bundle was built with (not the env var it came from),
 * and the signature out of the manifest the updater will fetch (not the `.sig`
 * file next to the installer). A mismatch anywhere along that chain fails the
 * build.
 *
 * Usage (from `frontend/`, after `tauri:generate-latest:windows`):
 *   node scripts/verify-updater-signature.mjs
 *   node scripts/verify-updater-signature.mjs --config … --manifest … --bundle-dir …
 */

import { readFile, readdir } from 'node:fs/promises';
import path from 'node:path';
import process from 'node:process';
import { parseArgs } from './lib/cli-args.mjs';
import { verifyMinisignSignature } from './lib/minisign.mjs';

const args = parseArgs(process.argv.slice(2));
const configPath = path.resolve(args.config ?? 'src-tauri/tauri.release.conf.json');
const manifestPath = path.resolve(args.manifest ?? 'src-tauri/target/release/latest.json');
const bundleDir = path.resolve(args.bundleDir ?? 'src-tauri/target/release/bundle');

const pubkey = await readPubkey(configPath);
const platforms = await readPlatforms(manifestPath);

for (const [platform, entry] of platforms) {
	if (!entry?.signature) {
		throw new Error(`Manifest platform ${platform} has no signature.`);
	}
	if (!entry.url) {
		throw new Error(`Manifest platform ${platform} has no url.`);
	}

	const assetName = decodeURIComponent(entry.url.split('/').pop() ?? '');
	const artifact = await findArtifact(bundleDir, assetName);
	const result = verifyMinisignSignature({
		publicKey: pubkey,
		signature: entry.signature,
		data: await readFile(artifact)
	});

	console.log(
		`${platform}: ${assetName} verifies against key ${result.keyIdHex} (${result.algorithm}).`
	);
	console.log(`  trusted comment: ${result.trustedComment}`);
}

console.log(
	`Updater signature check OK: ${platforms.length} platform(s) signed by the key this build ships.`
);

async function readPubkey(file) {
	const config = JSON.parse(await readFile(file, 'utf8'));
	const updater = config.plugins?.updater;
	const value = updater?.pubkey;
	if (!value) {
		throw new Error(
			`No plugins.updater.pubkey in ${file}. ` +
				'Run tauri:release-config:windows before this check.'
		);
	}
	// HTTPS-only is true today by default rather than by assertion: the flag is
	// env-gated in lib/tauri-config.mjs and nothing sets the env var. Checking it
	// on the generated config makes the guarantee explicit, and this is the step
	// that already reads that file. Recorded as an open note in
	// docs/UPDATER_AUDIT.md since v1.0.
	if (updater.dangerousInsecureTransportProtocol === true) {
		throw new Error(
			`${file} sets plugins.updater.dangerousInsecureTransportProtocol — a release ` +
				'must fetch its manifest over HTTPS. Unset ' +
				'TAURI_UPDATER_DANGEROUS_INSECURE_TRANSPORT_PROTOCOL and regenerate the config.'
		);
	}
	return value;
}

async function readPlatforms(file) {
	const manifest = JSON.parse(await readFile(file, 'utf8'));
	const platforms = Object.entries(manifest.platforms ?? {});
	if (platforms.length === 0) {
		throw new Error(`No platforms in ${file}; there is nothing to verify.`);
	}
	return platforms;
}

/**
 * Resolves the asset the manifest names to the file this build produced.
 * Failing here is a finding in its own right: the manifest would ship a
 * download url for something the release does not contain.
 */
async function findArtifact(root, assetName) {
	const entries = await readdir(root, { withFileTypes: true, recursive: true });
	const match = entries.find((entry) => entry.isFile() && entry.name === assetName);
	if (!match) {
		throw new Error(`Manifest names ${assetName}, but no such file exists under ${root}.`);
	}
	return path.join(match.parentPath, match.name);
}
